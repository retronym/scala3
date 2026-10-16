package dotty.tools.benchlab

import dotty.tools.dotc.profile.{CostFrame, CostListener}
import one.profiler.{Recording, Span}

/** Records the compiler's cost frames as async-profiler spans, so a sample taken while compiling lies in exactly one
 *  span: the innermost frame's. Spans nest by time only, so a span per frame would put the completion of `b`
 *  triggered while typing `a` inside `a`'s span too; instead each frame is recorded as segments, interrupted while
 *  nested frames run.
 *
 *  A segment's tag is the path of frames from the outermost, separated by ` > `, e.g.
 *  `unit@typer:scalaz/Foldable.scala > def:scalaz.Foldable.foldMap > complete:scalaz.Foldable.foldRight`.
 *  `jfrconv --spans` adds it as a leaf of each sample, for the flame graph's span panel; `jfrconv --tag '.*:\Qscalaz.Foldable.foldMap\E'` keeps
 *  the samples of one definition's own frames (the path ends with the innermost frame).
 *
 *    -p scalacOptions=-Yprofile-defs-listener:dotty.tools.benchlab.SpanCostListener
 *
 *  with an async-profiler that has the Span API recording JFR. Without a recording, a frame costs a string
 *  concatenation and a check of a static field.
 */
final class SpanCostListener extends CostListener:
  // the path of each open frame, innermost last
  private val paths = java.util.ArrayDeque[String]()

  def enter(frame: CostFrame): Unit =
    val parentPath = paths.peekLast()
    paths.addLast(if parentPath == null then frame.label else parentPath + " > " + frame.label)
    if Recording.state() == Recording.RUNNING then
      val now = Recording.timestamp()
      val parent = frame.parent
      if parent != null && parent.listenerData != 0 && parentPath != null then
        Span.emitIfProfiled(parent.listenerData, now, parentPath)
      frame.listenerData = now

  def exit(frame: CostFrame): Unit =
    val path = paths.pollLast()
    if Recording.state() == Recording.RUNNING then
      val now = Recording.timestamp()
      if frame.listenerData != 0 && path != null then Span.emitIfProfiled(frame.listenerData, now, path)
      val parent = frame.parent
      if parent != null then parent.listenerData = now

  override def finished(): Unit = paths.clear()
