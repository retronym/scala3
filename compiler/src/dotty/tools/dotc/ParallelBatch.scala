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

  /** What each thread did when, for a timeline of a run (the x-ray's batch view, `-Ddotc.parallelTyper.timeline=<file>`):
   *  a span per unit of each step (parse, index, class headers, signatures, bodies), per later phase, and the waits on
   *  completion roots. Off unless a `Timeline` is installed: one volatile read per span.
   */
  final class Timeline:
    val start: Long = System.nanoTime()
    /** A span: its thread, step and label, its wall interval from the timeline's start, and the CPU time the thread
     *  spent in it (-1 for waits and GC pauses): wall above CPU is time blocked or descheduled. */
    final case class Span(thread: String, step: String, label: String, startNs: Long, endNs: Long, cpuNs: Long,
        blockedMs: Long = -1L, waitedMs: Long = -1L)
    val spans = java.util.concurrent.ConcurrentLinkedQueue[Span]()
    def add(step: String, label: String, startNs: Long, endNs: Long, cpuNs: Long = -1L, blockedMs: Long = -1L, waitedMs: Long = -1L): Unit =
      spans.add(Span(Thread.currentThread.nn.getName.nn, step, label, startNs - start, endNs - start, cpuNs, blockedMs, waitedMs))
    def addOn(thread: String, step: String, label: String, startNs: Long, endNs: Long): Unit =
      spans.add(Span(thread, step, label, startNs - start, endNs - start, -1L))

    // GC pauses, as spans on a lane of their own: each one stops every thread's span
    private val gcListeners =
      import java.lang.management.ManagementFactory
      import javax.management.{NotificationEmitter, NotificationListener, Notification}
      import com.sun.management.GarbageCollectionNotificationInfo
      import javax.management.openmbean.CompositeData
      import scala.jdk.CollectionConverters.*
      val uptimeStartMs = ManagementFactory.getRuntimeMXBean.nn.getStartTime
      val jvmStartNs = System.nanoTime() - ManagementFactory.getRuntimeMXBean.nn.getUptime * 1000000L
      ManagementFactory.getGarbageCollectorMXBeans.nn.asScala.toList.collect { case e: NotificationEmitter =>
        val l: NotificationListener = (n: Notification | Null, _: Object | Null) =>
          if n != null && n.getType == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION then
            val info = GarbageCollectionNotificationInfo.from(n.getUserData.asInstanceOf[CompositeData]).nn
            val gi = info.getGcInfo.nn
            // concurrent cycles are not pauses: only the stop-the-world collections
            if !info.getGcCause.nn.contains("No GC") && !info.getGcName.nn.contains("Concurrent") then
              addOn("gc", "gc", s"${info.getGcName}: ${info.getGcCause} (${gi.getDuration} ms)",
                jvmStartNs + gi.getStartTime * 1000000L, jvmStartNs + gi.getEndTime * 1000000L)
        e.addNotificationListener(l, null, null)
        (e, l)
      }

    /** Stop listening for GC pauses. */
    def close(): Unit = gcListeners.foreach((e, l) => try e.removeNotificationListener(l) catch case _: Exception => ())

    /** As JSON: `{"spans": [[thread, step, label, startMs, endMs, cpuMs, blockedMs, waitedMs], ...]}`: blocked is
     *  time entering a monitor (`synchronized`), waited is parked (claims, latches, `Object.wait`). */
    def toJson: String =
      import scala.jdk.CollectionConverters.*
      def str(s: String) = "\"" + s.flatMap { case '"' => "\\\""; case '\\' => "\\\\"; case c if c < ' ' => " "; case c => c.toString } + "\""
      // times from the timeline's start (a GC pause can begin a little before it: negative); -1 for a CPU time not measured
      def ms(ns: Long) = f"${ns / 1e6}%.3f"
      def cpu(ns: Long) = if ns < 0 then "-1" else ms(ns)
      spans.asScala.map(s => s"[${str(s.thread)},${str(s.step)},${str(s.label)},${ms(s.startNs)},${ms(s.endNs)},${cpu(s.cpuNs)},${s.blockedMs},${s.waitedMs}]").mkString("{\"spans\":[", ",\n", "]}")

  @volatile var timeline: Timeline | Null = null

  private val threadBean = java.lang.management.ManagementFactory.getThreadMXBean.nn
  // blocked and waited times per thread: off by default in the JVM, on while a timeline is wanted
  if threadBean.isThreadContentionMonitoringSupported then threadBean.setThreadContentionMonitoringEnabled(true)

  /** `body` as a span of `step` on this thread, when a timeline is installed. */
  inline def span[T](inline step: String, inline label: String)(inline body: T): T =
    val tl = timeline
    if tl == null then body
    else
      val t0 = System.nanoTime()
      val c0 = cpuNow()
      val i0 = threadInfo()
      try body
      finally
        val i1 = threadInfo()
        tl.add(step, label, t0, System.nanoTime(), cpuNow() - c0,
          if i0 == null || i1 == null then -1L else i1.getBlockedTime - i0.getBlockedTime,
          if i0 == null || i1 == null then -1L else i1.getWaitedTime - i0.getWaitedTime)

  def cpuNow(): Long = threadBean.getCurrentThreadCpuTime
  def threadInfo(): java.lang.management.ThreadInfo | Null = threadBean.getThreadInfo(Thread.currentThread.nn.getId)

  /** A wait of `nanos` that ended now, when a timeline is installed. */
  def waited(nanos: Long, what: String): Unit =
    val tl = timeline
    if tl != null then
      val t1 = System.nanoTime()
      tl.add("wait", what, t1 - nanos, t1)

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
