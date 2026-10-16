package dotty.tools.dotc
package profile

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.IdentityHashMap

import core.Contexts.*
import core.Symbols.*
import core.Phases.{Phase, NoPhase}
import core.Flags
import util.Spans.Span

/** What a frame on the cost stack measures. */
enum CostKind(val label: String, val perPhase: Boolean):
  /** A phase, outside its units */
  case Phase extends CostKind("phase", true)
  /** A compilation unit in one phase: the work on it outside the frames below */
  case Unit extends CostKind("unit", true)
  /** Type checking a definition (`typedValDef`, `typedDefDef`, `typedTypeDef`, `typedClassDef`) in the typer */
  case TypedDef extends CostKind("def", false)
  /** The same methods after the typer: retyping in erasure, the inliner and other `ReTyper`s */
  case Retype extends CostKind("retype", true)
  /** A definition's subtree in a MegaPhase: its miniphases' transforms */
  case Transform extends CostKind("transform", true)
  /** Completing a symbol from source (`Namer.Completer`): its signature, and its body when the result type is inferred */
  case Completion extends CostKind("complete", false)
  /** Completing a symbol from a classfile or TASTy (`SymbolLoader`) */
  case Load extends CostKind("load", false)
  /** An implicit search, charged to the innermost definition (or unit) */
  case Implicit extends CostKind("implicit", false)
  /** An inline expansion, charged to the innermost definition (or unit) */
  case Inline extends CostKind("inline", false)

/** The cost of one kind of work for one key (a symbol, or a compilation unit) in one phase (`NoPhase` for the kinds
 *  that are not per phase), summed over its frames. */
final class CostAcc(val kind: CostKind, val key: AnyRef, val phase: Phase):
  var calls: Int = 0
  var selfNs: Long = 0L
  var selfAlloc: Long = 0L
  var totalNs: Long = 0L
  var maxSelfNs: Long = 0L
  /** The size of the definition's tree in characters, when known */
  var chars: Int = 0

/** A region on the cost stack: from entering a hooked operation to leaving it. Frames are reused once left, so a
 *  listener must not keep one after `exit`. */
final class CostFrame private[profile] (costs: DefCosts):
  private var myKind: CostKind = CostKind.Phase
  private var myKey: AnyRef = NoSymbol
  private var myPhase: Phase = NoPhase
  private var myParent: CostFrame | Null = null
  // with -Yprofile-defs only: the accumulator, and the clocks
  private[profile] var acc: CostAcc | Null = null
  private[profile] var startNs: Long = 0L
  private[profile] var startAlloc: Long = 0L
  private[profile] var childNs: Long = 0L
  private[profile] var childAlloc: Long = 0L
  /** For a listener's own use, e.g. when the frame's span started */
  var listenerData: Long = 0L

  private[profile] def init(kind: CostKind, key: AnyRef, phase: Phase, parent: CostFrame | Null): Unit =
    myKind = kind; myKey = key; myPhase = phase; myParent = parent
    acc = null; childNs = 0L; childAlloc = 0L; listenerData = 0L

  def kind: CostKind = myKind
  /** A `Symbol`, a `CompilationUnit` for `CostKind.Unit`, a `Phase` for `CostKind.Phase` */
  def key: AnyRef = myKey
  /** The phase, for the kinds that are per phase; else `NoPhase` */
  def phase: Phase = myPhase
  def parent: CostFrame | Null = myParent
  /** A short name that needs no new string: the phase's name, the unit's source path, the symbol's (unqualified)
   *  name, or "implicit" / "inline". The frames enclosing it say what it is part of. */
  def label: String = costs.labelOf(myKind, myKey)

/** Notified as frames are entered and left, on the compiling thread: e.g. to record each frame's exclusive
 *  intervals as async-profiler spans. Named by `-Yprofile-defs-listener`; needs a public no-argument constructor.
 */
abstract class CostListener:
  /** `frame` was entered; its parent, if any, was interrupted */
  def enter(frame: CostFrame): Unit
  /** `frame` is being left; its parent, if any, resumes */
  def exit(frame: CostFrame): Unit
  def finished(): Unit = ()

/** Exclusive cost per definition. A stack of frames is kept in step with the hooked operations (a compilation unit in
 *  a phase, type checking a definition, completing a symbol, an implicit search, an inline expansion, a definition's
 *  subtree in a MegaPhase). A frame's self time is its time minus that of the frames nested in it, so the completion
 *  of `b` that happens while typing `a` (to infer `b`'s result type, say) is charged to `b`, not to `a`.
 *
 *  Only class members and top-level definitions get frames of their own; local definitions are part of the
 *  definition they are in. Implicit searches and inline expansions are charged to the innermost frame's key.
 *  Only the thread that created the tracker is measured.
 */
final class DefCosts(listener: CostListener | Null)(using ictx: Context):
  import DefCosts.*

  private val thread = Thread.currentThread()
  // The table (-Yprofile-defs) needs clocks and an accumulator per key; a listener alone needs only the frames
  private val table = ictx.settings.YprofileDefs.value.nonEmpty
  // per kind, per phase (NoPhase for kinds not per phase), per key
  private val accs = Array.fill(CostKind.values.length)(new IdentityHashMap[Phase, IdentityHashMap[AnyRef, CostAcc]]())
  private var top: CostFrame | Null = null
  // frames by depth, reused
  private var pool = new Array[CostFrame | Null](64)
  private var depth = 0

  /** The frame for `kind` and `key` in `phase`, or null if this operation gets no frame of its own. For implicit
   *  searches and inline expansions the key is the innermost frame's. */
  def enter(kind: CostKind, key: AnyRef, span: Span, phase: Phase): CostFrame | Null =
    if Thread.currentThread() ne thread then return null
    val k: AnyRef = kind match
      case CostKind.Implicit | CostKind.Inline =>
        val t = top
        if t == null then NoSymbol else t.key
      case CostKind.Unit | CostKind.Phase => key
      case _ =>
        key match
          case sym: Symbol if sym.exists && isTracked(sym) => sym
          case _ => return null
    // The phase of the innermost unit frame: inside a MegaPhase, `ctx.phase` is not the MegaPhase
    val ph =
      if !kind.perPhase then NoPhase
      else if kind == CostKind.Unit || kind == CostKind.Phase then phase
      else unitPhase(phase)
    if depth == pool.length then pool = java.util.Arrays.copyOf(pool, depth * 2).asInstanceOf[Array[CostFrame | Null]]
    val pooled = pool(depth)
    val frame =
      if pooled != null then pooled
      else
        val f = CostFrame(this)
        pool(depth) = f
        f
    depth += 1
    frame.init(kind, k, ph, top)
    if table then
      val acc = accFor(kind, k, ph)
      if span.exists then acc.chars = math.max(acc.chars, span.end - span.start)
      frame.acc = acc
      frame.startNs = System.nanoTime()
      frame.startAlloc = allocated()
    top = frame
    if listener != null then listener.enter(frame)
    frame

  private def accFor(kind: CostKind, k: AnyRef, ph: Phase): CostAcc =
    val byPhase = accs(kind.ordinal)
    val byKey0 = byPhase.get(ph)
    val byKey =
      if byKey0 != null then byKey0
      else
        val m = new IdentityHashMap[AnyRef, CostAcc]()
        byPhase.put(ph, m)
        m
    val known = byKey.get(k)
    if known != null then known
    else
      val a = CostAcc(kind, k, ph)
      byKey.put(k, a)
      a

  private def unitPhase(default: Phase): Phase =
    var f = top
    while f != null && f.kind != CostKind.Unit do f = f.parent
    if f == null then default else f.phase

  def exit(frame: CostFrame): Unit =
    if listener != null then listener.exit(frame)
    val acc = frame.acc
    if acc != null then
      val total = System.nanoTime() - frame.startNs
      val alloc = allocated() - frame.startAlloc
      val self = total - frame.childNs
      acc.calls += 1
      acc.selfNs += self
      acc.selfAlloc += alloc - frame.childAlloc
      acc.totalNs += total
      if self > acc.maxSelfNs then acc.maxSelfNs = self
      val parent = frame.parent
      if parent != null then
        parent.childNs += total
        parent.childAlloc += alloc
    top = frame.parent
    depth -= 1

  /** One row per kind, phase and key, tab-separated: kind, phase (empty for kinds not per phase), name (a symbol's
   *  full name, or a unit's source path), file, line, chars, calls, self and total time in µs, the longest single self
   *  time in µs, and self allocation in bytes. */
  def write(path: String): Unit =
    val sb = StringBuilder("kind\tphase\tsymbol\tfile\tline\tchars\tcalls\tself_us\ttotal_us\tmax_self_us\tself_alloc\n")
    for byPhase <- accs; byKey <- byPhase.values.nn.toArray(Array.empty[IdentityHashMap[AnyRef, CostAcc]]);
        acc <- byKey.nn.values.nn.toArray(Array.empty[CostAcc]) do
      val (file, line) = where(acc.key)
      sb.append(acc.kind.label).append('\t').append(phaseName(acc.phase)).append('\t').append(nameOf(acc.key)).append('\t')
        .append(file).append('\t').append(line).append('\t').append(acc.chars).append('\t').append(acc.calls).append('\t')
        .append(acc.selfNs / 1000).append('\t').append(acc.totalNs / 1000).append('\t').append(acc.maxSelfNs / 1000)
        .append('\t').append(acc.selfAlloc).append('\n')
    Files.write(Paths.get(path), sb.toString.getBytes(StandardCharsets.UTF_8))

  def finished(): Unit =
    if listener != null then listener.finished()
    val path = ictx.settings.YprofileDefs.value
    if path.nonEmpty then
      // Units that need another run (macros) get another tracker: keep each run's file
      val suffix = if ictx.runId > core.Periods.InitialRunId + 1 then s".${ictx.runId - 1}" else ""
      write(path + suffix)

  private[profile] def labelOf(kind: CostKind, key: AnyRef): String = kind match
    case CostKind.Implicit | CostKind.Inline => kind.label
    case _ => key match
      case phase: Phase => phase.phaseName
      case unit: CompilationUnit => unit.source.path
      case sym: Symbol => if sym.exists then sym.name.toString else "<outside a definition>"  // a field of the name
      case other => String.valueOf(other)

  private def where(key: AnyRef): (String, String) = key match
    case _: Phase => ("", "")
    case unit: CompilationUnit => (unit.source.path, "")
    case sym: Symbol if sym.exists && sym.span.exists =>
      val pos = sym.sourcePos(using ictx)
      if pos.exists then (pos.source.path, (pos.line + 1).toString) else ("", "")
    case _ => ("", "")

object DefCosts:
  private val threadMx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private def allocated(): Long = threadMx.getCurrentThreadAllocatedBytes

  /** Class members and top-level definitions; local definitions are part of the definition they are in */
  private def isTracked(sym: Symbol)(using Context): Boolean =
    val owner = sym.maybeOwner
    owner.exists && (owner.isClass || owner.is(Flags.Package))

  private def phaseName(phase: Phase): String = if phase eq NoPhase then "" else phase.phaseName

  private def nameOf(key: AnyRef)(using Context): String = key match
    case unit: CompilationUnit => unit.source.path
    case phase: Phase => phase.phaseName
    case sym: Symbol => if !sym.exists then "<outside a definition>" else sym.fullName.toString
    case other => String.valueOf(other)

  /** A tracker for this run, or null if neither `-Yprofile-defs` nor `-Yprofile-defs-listener` is set. */
  def apply()(using Context): DefCosts | Null =
    val path = ctx.settings.YprofileDefs.value
    val listenerClass = ctx.settings.YprofileDefsListener.value
    if path.isEmpty && listenerClass.isEmpty then null
    else
      val listener =
        if listenerClass.isEmpty then null
        else
          val loader = Option(Thread.currentThread().getContextClassLoader).getOrElse(getClass.getClassLoader)
          Class.forName(listenerClass, true, loader).getDeclaredConstructor().newInstance().asInstanceOf[CostListener]
      new DefCosts(listener)
