package dotty.tools.dotc
package profile

import java.io.IOException
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
  /** A compilation unit in one phase, outside the frames below */
  case Unit extends CostKind("unit", true)
  /** Typing a definition in the typer (`typedValDef`, `typedDefDef`, `typedTypeDef`, `typedClassDef`) */
  case TypedDef extends CostKind("def", false)
  /** Typing a definition after the typer: erasure and the other `ReTyper`s, and rechecks (capture checking) */
  case Retype extends CostKind("retype", true)
  /** A definition's subtree in a tree transform: a MegaPhase, or a MacroTransform such as inlining or posttyper */
  case Transform extends CostKind("transform", true)
  /** Completing a symbol from source (`Namer.Completer`): its signature, and its body when its type is inferred */
  case Completion extends CostKind("complete", false)
  /** Completing a symbol from a classfile or TASTy (`SymbolLoader`) */
  case Load extends CostKind("load", false)
  /** An implicit search, charged to the innermost definition (or unit) */
  case Implicit extends CostKind("implicit", true)
  /** An inline expansion, charged to the innermost definition (or unit) */
  case Inline extends CostKind("inline", true)

/** The cost of one kind of work for one key (a symbol, a compilation unit or a phase) in one phase (`NoPhase` for the
 *  kinds that are not per phase), summed over its frames. Only with `-Yprofile-defs`. */
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
final class CostFrame private[profile] ()(using ictx: Context):
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
  def label: String = DefCosts.labelOf(myKind, myKey)

/** Told about each frame as it is entered and left, on the compiling thread: e.g. to record async-profiler spans
 *  nested as the frames are. Named by `-Yprofile-defs-listener`; needs a public no-argument constructor.
 */
abstract class CostListener:
  /** `frame` was entered, inside its parent */
  def enter(frame: CostFrame): Unit
  /** `frame` was left; it is no longer on the stack */
  def exit(frame: CostFrame): Unit
  def finished(): Unit = ()

/** Exclusive cost per definition. A stack of frames is kept in step with the profiler's hooks: a phase, a compilation
 *  unit in a phase, typing a definition (in the typer, or retyping or rechecking after it), completing a symbol from
 *  source or loading one from a classfile or TASTy, an implicit search, an inline expansion, and a definition's subtree
 *  in a tree transform. A frame's self time is its time minus that of the frames nested in it, so the completion of
 *  `b` that happens while typing `a` (to infer `b`'s result type, say) is charged to `b`, not to `a`.
 *
 *  Only class members and top-level definitions get frames of their own (including members of local and anonymous
 *  classes); local definitions are part of the definition they are in. Implicit searches and inline expansions are
 *  charged to the innermost frame's key. Only the thread that created the tracker is measured.
 *
 *  With a listener alone, a frame is its kind, key, phase and parent; clocks and the accumulators are only kept for
 *  the table (`path` non-empty).
 */
final class DefCosts(listener: CostListener | Null, path: String)(using ictx: Context):
  import DefCosts.*

  private val thread = Thread.currentThread()
  private val table = path.nonEmpty
  // per kind, per phase (NoPhase for kinds not per phase), per key; only for the table
  private val accs =
    if table then Array.fill(CostKind.values.length)(new IdentityHashMap[Phase, IdentityHashMap[AnyRef, CostAcc]]())
    else null
  private var top: CostFrame | Null = null
  // frames by depth, reused
  private var pool = new Array[CostFrame | Null](64)
  private var depth = 0

  /** The frame for `kind` and `key`, or null if this operation gets no frame of its own. `phase` is the phase of a
   *  `Phase` or `Unit` frame; other frames that are per phase take the innermost unit frame's (inside a MegaPhase,
   *  `ctx.phase` is not the MegaPhase). For implicit searches and inline expansions the key is the innermost frame's. */
  def enter(kind0: CostKind, key: AnyRef, span: Span, phase: Phase)(using Context): CostFrame | Null =
    if Thread.currentThread() ne thread then return null
    val kind = if kind0 == CostKind.TypedDef && !ctx.phase.isTyper then CostKind.Retype else kind0
    val k: AnyRef = kind match
      case CostKind.Implicit | CostKind.Inline =>
        val t = top
        if t == null then NoSymbol else t.key
      case CostKind.Unit | CostKind.Phase => key
      case _ =>
        key match
          case sym: Symbol if sym.exists && isTracked(sym) => sym
          case _ => return null
    val ph =
      if !kind.perPhase then NoPhase
      else if kind == CostKind.Unit || kind == CostKind.Phase then phase
      else unitPhase(ctx.phase)
    if depth == pool.length then pool = java.util.Arrays.copyOf(pool, depth * 2).asInstanceOf[Array[CostFrame | Null]]
    val pooled = pool(depth)
    val frame =
      if pooled != null then pooled
      else
        val f = CostFrame()
        pool(depth) = f
        f
    frame.init(kind, k, ph, top)
    if table then
      val acc = accFor(kind, k, ph)
      if span.exists then acc.chars = math.max(acc.chars, span.end - span.start)
      frame.acc = acc
      frame.startNs = System.nanoTime()
      frame.startAlloc = allocated()
    depth += 1
    top = frame
    if listener != null then
      var entered = false
      try
        listener.enter(frame)
        entered = true
      finally if !entered then pop(frame)  // the caller's finally never sees this frame
    frame

  private def accFor(kind: CostKind, k: AnyRef, ph: Phase): CostAcc =
    val byPhase = accs.nn(kind.ordinal)
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

  private def pop(frame: CostFrame): Unit =
    assert(frame eq top, "cost frames left out of order")
    top = frame.parent
    depth -= 1

  def exit(frame: CostFrame): Unit =
    pop(frame)
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
    if listener != null then listener.exit(frame)

  /** One row per kind, phase and key, tab-separated, sorted by kind, phase and name: kind, phase (empty for kinds not
   *  per phase), name (a symbol's full name, a unit's source path, a phase's name), file, line, chars, calls, self and
   *  total time in µs, the longest single self time in µs, and self allocation in bytes. */
  def write(path: String): Unit =
    val rows = for
      byPhase <- accs.nn.toList
      byKey <- byPhase.values.nn.toArray(Array.empty[IdentityHashMap[AnyRef, CostAcc]]).toList
      acc <- byKey.nn.values.nn.toArray(Array.empty[CostAcc]).toList
    yield (acc.kind.ordinal, phaseName(acc.phase), nameOf(acc.key), acc)
    val sb = StringBuilder("kind\tphase\tsymbol\tfile\tline\tchars\tcalls\tself_us\ttotal_us\tmax_self_us\tself_alloc\n")
    for (_, phase, name, acc) <- rows.sortBy(r => (r._1, r._2, r._3)) do
      val (file, line) = where(acc.key)
      sb.append(acc.kind.label).append('\t').append(phase).append('\t').append(name).append('\t')
        .append(file).append('\t').append(line).append('\t').append(acc.chars).append('\t').append(acc.calls).append('\t')
        .append(acc.selfNs / 1000).append('\t').append(acc.totalNs / 1000).append('\t').append(acc.maxSelfNs / 1000)
        .append('\t').append(acc.selfAlloc).append('\n')
    Files.write(Paths.get(path), sb.toString.getBytes(StandardCharsets.UTF_8))

  def finished(): Unit =
    if listener != null then listener.finished()
    if table then
      // A later run of the same compiler (a unit suspended for a macro, the REPL, a compile server) writes its own
      // file beside the first, unless the target is not a regular file (e.g. /dev/null)
      val p = Paths.get(path)
      val regular = !Files.exists(p) || Files.isRegularFile(p)
      val target = if ictx.runId > core.Periods.InitialRunId + 1 && regular then s"$path.${ictx.runId - 1}" else path
      try write(target)
      catch case ex: IOException => report.warning(s"-Yprofile-defs: cannot write $target: $ex")(using ictx)

  private def where(key: AnyRef): (String, String) = key match
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

  /** A frame's short label: a string the compiler already holds */
  private[profile] def labelOf(kind: CostKind, key: AnyRef)(using Context): String = kind match
    case CostKind.Implicit | CostKind.Inline => kind.label
    case _ => key match
      case sym: Symbol => if sym.exists then sym.name.toString else "<outside a definition>"  // a field of the name
      case other => keyName(other)

  /** A key's name for the table: a symbol's full name */
  private def nameOf(key: AnyRef)(using Context): String = key match
    case sym: Symbol => if sym.exists then sym.fullName.toString else "<outside a definition>"
    case other => keyName(other)

  private def keyName(key: AnyRef): String = key match
    case phase: Phase => phase.phaseName
    case unit: CompilationUnit => unit.source.path
    case other => String.valueOf(other)

  /** A tracker for this run, or null if neither `-Yprofile-defs` nor `-Yprofile-defs-listener` is set (or the listener
   *  cannot be created, which is reported). */
  def apply()(using Context): DefCosts | Null =
    val path = ctx.settings.YprofileDefs.value
    val listenerClass = ctx.settings.YprofileDefsListener.value
    val listener =
      if listenerClass.isEmpty then null
      else
        try
          val loader = Option(Thread.currentThread().getContextClassLoader).getOrElse(getClass.getClassLoader)
          Class.forName(listenerClass, true, loader).getDeclaredConstructor().newInstance().asInstanceOf[CostListener]
        catch case ex: (ReflectiveOperationException | ClassCastException) =>
          report.error(s"-Yprofile-defs-listener: cannot create $listenerClass (a CostListener with a public no-argument constructor): $ex")
          null
    if path.isEmpty && listener == null then null
    else new DefCosts(listener, path)
