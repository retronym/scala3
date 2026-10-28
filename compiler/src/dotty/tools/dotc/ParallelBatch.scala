package dotty.tools
package dotc

import scala.collection.mutable

import core.*
import Contexts.*
import reporting.*

/** Batch compilation with the typer's units typed in parallel (`-Yparallel-typer`, a spike): the base of
 *  `-Yinteractive-parallel` (`Sharing.Parallel`: a claim per completion root, other readers waiting for it),
 *  driven by the batch compiler. Parsing is parallel; indexing, and then the completion of every member's
 *  signature, serial; typechecking the bodies parallel with a unit per task; the phases after the typer run on
 *  one thread as before (`TyperPhase.typeCheckInParallel`).
 */
object ParallelBatch:
  val flag = "-Yparallel-typer"

  def requested(args: Array[String]): Boolean =
    args.exists(a => a == flag || a == s"$flag:true")

/** A batch base with a reader per thread, as `InteractiveContextBase` has. */
class ParallelBatchContextBase extends ContextBase(Sharing.Parallel):
  private lazy val threadReader: ThreadLocal[Reader] = ThreadLocal.withInitial(() => newReader(Reader.neverCancelled))
  override def reader: Reader = threadReader.get.nn

/** The run's reporter with readers on several threads: every report is under its monitor. While `buffering`, the
 *  diagnostics are kept and then issued in a fixed order (by file, then position, then arrival), so that a
 *  parallel typecheck reports as a serial one would, but for the order between units.
 */
class ParallelBatchReporter(val underlying: Reporter) extends Reporter with UniqueMessagePositions with HideNonSensicalMessages:
  private val buffer = mutable.ArrayBuffer.empty[Diagnostic]
  private var buffering = false

  // the message is rendered before the monitor is taken: rendering can complete symbols (import suggestions),
  // and so wait for another reader, which may be waiting for this monitor
  override def report(dia: Diagnostic)(using Context): Unit =
    dia.msg.message
    synchronized(super.report(dia))

  def doReport(dia: Diagnostic)(using Context): Unit = synchronized:
    if buffering then buffer += dia else underlying.doReport(dia)

  def startBuffering(): Unit = synchronized { buffering = true }

  def issueBuffered()(using Context): Unit = synchronized:
    buffering = false
    val ordered = buffer.zipWithIndex.sortBy { (d, i) =>
      val pos = d.pos
      val path = if pos.exists then pos.source.path else ""
      (path, if pos.exists then pos.point else -1, i)
    }
    buffer.clear()
    ordered.foreach((d, _) => underlying.doReport(d))

  override def flush()(using Context): Unit = synchronized(super.flush())
