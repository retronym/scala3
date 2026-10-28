package dotty.tools.pc.xray

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.{AtomicBoolean, AtomicIntegerArray}

import scala.language.unsafeNulls

import com.sun.net.httpserver.HttpExchange

/** The x-ray's throughput demo: for each N, a fresh driver over every file of
 *  the source set (`openAll`, every statement skipped, `-Yinteractive-parallel`,
 *  the build's options), typed by N reader threads with `Throughput`'s
 *  round-robin policy, or the largest files first
 *  (`big`, the default), and work stealing. One single-reader whole-set run
 *  first, as the warm-up and the reference for N=1; each later N is compared
 *  with N=1's diagnostics. The batch typer's time on the same files once, as
 *  a reference line. Runs on its own thread, never the HTTP dispatcher's
 *  (I61), and can be cancelled; the results stay for the server's life, so
 *  the page's plot survives a reload.
 */
object ThroughputDemo:
  import XrayServer.{json, reply, field}

  /** A point of the plot. `lanes`: per reader thread, its files as (file,
   *  start ms, end ms) from the start of the open; the wall split into the
   *  open (serial), typing until the first thread ran out of files, and the
   *  tail until the last one did. */
  final case class Point(n: Int, wallMs: Double, workerCpuMs: Double, rootWaits: Int, rootWaitMs: Double, differ: Int,
      cycleAborts: Int, cyclesRaised: Int, filesPerThread: List[Int], failure: String,
      openMs: Double = 0, typingMs: Double = 0, tailMs: Double = 0, lanes: List[List[(String, Double, Double)]] = Nil):
    def toMap: Map[String, Any] = Map("n" -> n, "wallMs" -> r(wallMs), "workerCpuMs" -> r(workerCpuMs), "rootWaits" -> rootWaits,
      "rootWaitMs" -> r(rootWaitMs), "differ" -> differ, "cycleAborts" -> cycleAborts, "cyclesRaised" -> cyclesRaised,
      "filesPerThread" -> filesPerThread, "failure" -> failure, "openMs" -> r(openMs), "typingMs" -> r(typingMs), "tailMs" -> r(tailMs),
      "lanes" -> lanes.map(_.map((f, a, b) => List(f, r(a), r(b)))))

  private def r(d: Double): Double = math.round(d * 10) / 10.0

  @volatile private var state = "idle" // idle, warming, batch, running, done, cancelled, failed
  @volatile private var points: List[Point] = Nil
  @volatile private var ns: List[Int] = List(1, 2, 4, 6, 8)
  @volatile private var policy = "big"
  @volatile private var currentN = 0
  @volatile private var progress: AtomicIntegerArray | Null = null
  @volatile private var files = 0
  @volatile private var batchMs: Double = Double.NaN
  @volatile private var warmMs: Double = Double.NaN
  @volatile private var error = ""
  @volatile private var startedAt = 0L
  private val stopRequested = new AtomicBoolean(false)
  @volatile private var runner: Thread | Null = null

  def start(root: Path, requested: List[Int], requestedPolicy: String): Map[String, Any] = synchronized {
    if runner != null && runner.isAlive then status() + ("error" -> "a throughput run is in progress")
    else
      ns = requested; policy = requestedPolicy; points = Nil; error = ""; currentN = 0; progress = null; stopRequested.set(false); startedAt = System.nanoTime()
      state = "warming" // before the reply: a page polling at once must not see the previous state
      val t = new Thread((() => run(root)): Runnable, "xray-throughput")
      t.setDaemon(true); runner = t; t.start()
      status()
  }

  def stop(): Unit = stopRequested.set(true)

  private def run(root: Path): Unit =
    try
      val jobs = Throughput.loadFiles(root)
      files = jobs.size
      state = "warming"
      // the warm-up: one reader, the whole set in one run, nothing skipped; the reference for N=1
      val t0 = System.nanoTime()
      val (_, wholeSetRef) = Throughput.wholeSet(jobs)
      warmMs = (System.nanoTime() - t0) / 1e6
      if batchMs.isNaN && !stopRequested.get then
        state = "batch"
        batchMs = Throughput.batch(jobs, root).wallMs
      var reference = wholeSetRef
      for n <- ns if !stopRequested.get do
        state = "running"; currentN = n
        val prog = new AtomicIntegerArray(n); progress = prog
        val diags = new java.util.concurrent.ConcurrentHashMap[URI, List[String]]()
        val timeline = Throughput.Timeline()
        val point =
          try
            val c = Throughput.measure(policy, n, Throughput.assign(policy, n, jobs, Map.empty), readers = true, warm = false, jobs, reference,
              cancelled = () => stopRequested.get, onProgress = (t, done) => prog.set(t, done), diagnosticsOut = diags, timeline = timeline)
            import scala.jdk.CollectionConverters.*
            val ms = (t: Long) => (t - timeline.start) / 1e6
            val byThread = timeline.files.asScala.toList.groupBy(_._1)
            val lanes = (0 until n).toList.map(t => byThread.getOrElse(t, Nil).sortBy(_._3).map((_, f, a, b) => (f, ms(a), ms(b))))
            val ends = lanes.map(l => l.lastOption.map(_._3).getOrElse(ms(timeline.opened)))
            val openMs = ms(timeline.opened)
            Point(n, c.wallMs, c.workerCpuMs, c.rootWaits, c.rootWaitMs, c.diagnosticsDiffer, c.cycleAborts, c.cyclesRaised, c.filesPerThread, "",
              openMs, ends.min - openMs, ends.max - ends.min, lanes)
          catch case ex: RuntimeException =>
            Point(n, Double.NaN, Double.NaN, 0, 0, 0, 0, 0, Nil, if stopRequested.get then "cancelled" else String.valueOf(ex.getMessage).take(300))
        points = points :+ point
        // the later Ns are compared with N=1's own diagnostics
        if n == 1 && point.failure.isEmpty then
          import scala.jdk.CollectionConverters.*
          reference = diags.asScala.toMap
      state = if stopRequested.get then "cancelled" else "done"
    catch case ex: (Exception | StackOverflowError | AssertionError | OutOfMemoryError) =>
      error = ex.toString; state = "failed"
    finally progress = null

  def status(): Map[String, Any] =
    val p = progress
    Map("state" -> state, "ns" -> ns, "policy" -> policy, "points" -> points.map(_.toMap), "currentN" -> currentN, "files" -> files,
      "progress" -> (if p == null then Nil else (0 until p.length).map(p.get).toList),
      "batchMs" -> (if batchMs.isNaN then -1.0 else r(batchMs)), "warmMs" -> (if warmMs.isNaN then -1.0 else r(warmMs)),
      "error" -> error, "elapsedMs" -> (if startedAt == 0L then 0L else (System.nanoTime() - startedAt) / 1000000),
      "build" -> XrayServer.gitTip, "processors" -> Runtime.getRuntime.availableProcessors, "jvm" -> jvm)

  /** The JVM's heap and GC flags and its collectors, for the page. */
  private lazy val jvm: Map[String, Any] =
    import java.lang.management.ManagementFactory
    import scala.jdk.CollectionConverters.*
    val flags = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList.filter(a => a.startsWith("-X") || a.startsWith("-XX"))
    Map("flags" -> flags.mkString(" "), "gc" -> ManagementFactory.getGarbageCollectorMXBeans.asScala.map(_.getName).mkString(", "),
      "maxHeapGb" -> r(Runtime.getRuntime.maxMemory / 1e9), "version" -> System.getProperty("java.version"))

  def route(ex: HttpExchange, path: String, root: Option[Path]): Unit =
    val q = Option(ex.getRequestURI.getRawQuery).getOrElse("") + "&" + (if ex.getRequestMethod == "POST" then XrayServer.readBody(ex) else "")
    path match
      case "/throughput/run" =>
        val requested = field(q, "ns").map(_.split(',').toList.flatMap(_.trim.toIntOption).filter(n => n >= 1 && n <= 64).distinct).filter(_.nonEmpty).getOrElse(List(1, 2, 4, 6, 8))
        root match
          case Some(dir) => reply(ex, 200, "application/json", json(start(dir, requested, field(q, "policy").filter(Set("big", "rr")).getOrElse("big"))))
          case None => reply(ex, 400, "application/json", json(Map("error" -> "no source set")))
      case "/throughput/stop" => stop(); reply(ex, 200, "application/json", json(status()))
      case "/throughput/status" => reply(ex, 200, "application/json", json(status()))
      case _ => reply(ex, 404, "text/plain", "not found")
