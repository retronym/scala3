package dotty.tools.dotc
package profile

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.IdentityHashMap

import core.Contexts.*
import core.Symbols.*
import core.Flags
import util.Spans.Span

/** What a frame on the cost stack measures. */
enum CostKind(val label: String):
  /** Type checking a definition (`typedValDef`, `typedDefDef`, `typedTypeDef`, `typedClassDef`) in the typer */
  case TypedDef extends CostKind("def")
  /** The same methods after the typer: retyping in erasure, the inliner and other `ReTyper`s */
  case Retype extends CostKind("retype")
  /** Completing a symbol from source (`Namer.Completer`): its signature, and its body when the result type is inferred */
  case Completion extends CostKind("complete")
  /** Completing a symbol from a classfile or TASTy (`SymbolLoader`) */
  case Load extends CostKind("load")
  /** An implicit search, charged to the definition it is made for */
  case Implicit extends CostKind("implicit")
  /** An inline expansion, charged to the definition it is made in */
  case Inline extends CostKind("inline")

/** The cost of one kind of work for one symbol, summed over its frames. */
final class CostAcc(val kind: CostKind, val symbol: Symbol):
  var calls: Int = 0
  var selfNs: Long = 0L
  var selfAlloc: Long = 0L
  var totalNs: Long = 0L
  var maxSelfNs: Long = 0L
  /** The size of the definition's tree in characters, when known (`TypedDef` only) */
  var chars: Int = 0
  /** `<kind>:<full name>`, for listeners; set when a listener is present */
  var label: String | Null = null

/** A region on the cost stack: from entering a hooked operation to leaving it. */
final class CostFrame(val acc: CostAcc, val parent: CostFrame | Null, val startNs: Long, val startAlloc: Long):
  var childNs: Long = 0L
  var childAlloc: Long = 0L
  /** For a listener's own use, e.g. when the frame's current span segment started */
  var listenerData: Long = 0L
  def kind: CostKind = acc.kind
  def symbol: Symbol = acc.symbol
  /** `<kind>:<full name of the symbol>`, built once per symbol and kind; only available to listeners */
  def label: String = acc.label.nn

/** Notified as frames are entered and left, on the compiling thread: e.g. to record each frame's exclusive
 *  intervals as async-profiler spans. Named by `-Yprofile-defs-listener`; needs a public no-argument constructor.
 */
abstract class CostListener:
  /** `frame` was entered; its parent, if any, was interrupted */
  def enter(frame: CostFrame): Unit
  /** `frame` is being left; its parent, if any, resumes */
  def exit(frame: CostFrame): Unit
  def finished(): Unit = ()

/** Exclusive cost per definition. A stack of frames is kept in step with the hooked operations (type checking a
 *  definition, completing a symbol, an implicit search, an inline expansion). A frame's self time is its time minus
 *  that of the frames nested in it, so the completion of `b` that happens while typing `a` (to infer `b`'s result
 *  type, say) is charged to `b`, not to `a`.
 *
 *  Only class members and top-level definitions get frames of their own; local definitions are part of the
 *  definition they are in. Implicit searches and inline expansions are charged to the innermost definition.
 *  Only the thread that created the tracker is measured.
 */
final class DefCosts(listener: CostListener | Null)(using ictx: Context):
  import DefCosts.*

  private val thread = Thread.currentThread()
  private val byKind = Array.fill(CostKind.values.length)(new IdentityHashMap[Symbol, CostAcc]())
  private var top: CostFrame | Null = null

  /** The frame for `kind` and `sym` (or, for implicit searches and inline expansions, the innermost definition), or
   *  null if this operation gets no frame of its own. */
  def enter(kind: CostKind, sym: Symbol, span: Span): CostFrame | Null =
    if Thread.currentThread() ne thread then return null
    val owner = kind match
      case CostKind.Implicit | CostKind.Inline =>
        val t = top
        if t == null then NoSymbol else t.symbol
      case _ =>
        if !sym.exists || !isTracked(sym) then return null
        sym
    val accs = byKind(kind.ordinal)
    val known = accs.get(owner)
    val acc =
      if known != null then known
      else
        val a = CostAcc(kind, owner)
        if listener != null then a.label = s"${kind.label}:${nameOf(owner)}"
        accs.put(owner, a)
        a
    if span.exists then acc.chars = math.max(acc.chars, span.end - span.start)
    val frame = CostFrame(acc, top, System.nanoTime(), allocated())
    top = frame
    if listener != null then listener.enter(frame)
    frame

  def exit(frame: CostFrame): Unit =
    if listener != null then listener.exit(frame)
    val total = System.nanoTime() - frame.startNs
    val alloc = allocated() - frame.startAlloc
    val self = total - frame.childNs
    val acc = frame.acc
    acc.calls += 1
    acc.selfNs += self
    acc.selfAlloc += alloc - frame.childAlloc
    acc.totalNs += total
    if self > acc.maxSelfNs then acc.maxSelfNs = self
    val parent = frame.parent
    if parent != null then
      parent.childNs += total
      parent.childAlloc += alloc
    top = parent

  /** One row per kind and symbol, tab-separated: kind, symbol, file, line, chars, calls, self and total time in µs,
   *  the longest single self time in µs, and self allocation in bytes. */
  def write(path: String): Unit =
    val sb = StringBuilder("kind\tsymbol\tfile\tline\tchars\tcalls\tself_us\ttotal_us\tmax_self_us\tself_alloc\n")
    for accs <- byKind; acc <- accs.values.nn.toArray(Array.empty[CostAcc]) do
      val (file, line) = where(acc.symbol)
      sb.append(acc.kind.label).append('\t').append(nameOf(acc.symbol)).append('\t').append(file).append('\t')
        .append(line).append('\t').append(acc.chars).append('\t').append(acc.calls).append('\t')
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

  private def where(sym: Symbol): (String, String) =
    if !sym.exists || !sym.span.exists then ("", "")
    else
      val pos = sym.sourcePos(using ictx)
      if pos.exists then (pos.source.path, (pos.line + 1).toString) else ("", "")

object DefCosts:
  private val threadMx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private def allocated(): Long = threadMx.getCurrentThreadAllocatedBytes

  /** Class members and top-level definitions; local definitions are part of the definition they are in */
  private def isTracked(sym: Symbol)(using Context): Boolean =
    val owner = sym.maybeOwner
    owner.exists && (owner.isClass || owner.is(Flags.Package))

  private def nameOf(sym: Symbol)(using Context): String =
    if !sym.exists then "<none>" else sym.fullName.toString

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
