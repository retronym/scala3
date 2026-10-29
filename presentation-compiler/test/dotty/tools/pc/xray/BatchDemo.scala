package dotty.tools.pc.xray

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

import com.sun.net.httpserver.HttpExchange

import dotty.tools.dotc.{Driver, ParallelBatch}
import dotty.tools.dotc.reporting.StoreReporter

/** The batch mode of the x-ray's thread-scaling view: the batch compiler under `-Yparallel-typer` over every file of
 *  the source set, in this JVM, for each thread count in `threads`: one untimed warm-up run first, then `runs` runs
 *  per thread count, each with a `ParallelBatch.Timeline`: a span per unit of
 *  each step (parse, index, class headers, signatures, bodies) on the thread that did it, the later phases on the
 *  run's thread, the waits on completion roots and the GC pauses, each with the thread's CPU time and its time
 *  blocked on monitors or parked. The page draws a lane per worker. Runs on its own thread, never the HTTP
 *  dispatcher's; the switches are the compiler's system properties (`dotc.parallelTyper.*`), set for the run.
 */
object BatchDemo:
  import XrayServer.{json, reply, field}

  final case class Options(threads: List[Int], parallelIndex: Boolean, parallelSignatures: Boolean, stopAfter: String, runs: Int):
    def toMap: Map[String, Any] = Map("threads" -> threads, "parallelIndex" -> parallelIndex,
      "parallelSignatures" -> parallelSignatures, "stopAfter" -> stopAfter, "runs" -> runs)

  /** A run (`index` counts every run of the sweep; `run` is its number among those with `n` threads): its wall time,
   *  errors and warnings, the CPU time in its spans, the GC pauses and the waits on completion roots, and the spans
   *  as `[thread, step, label, startMs, endMs, cpuMs, blockedMs, waitedMs]` (-1 where not measured). */
  final case class RunResult(index: Int, n: Int, run: Int, wallMs: Double, errors: Int, warnings: Int, spans: List[List[Any]]):
    private def sum(p: List[Any] => Boolean, f: List[Any] => Double) = math.round(spans.filter(p).map(f).sum)
    private def step(s: List[Any]) = s(1).asInstanceOf[String]
    private def d(x: Any) = x.asInstanceOf[Double]
    def toMap: Map[String, Any] = Map("index" -> index, "n" -> n, "run" -> run, "wallMs" -> wallMs, "errors" -> errors, "warnings" -> warnings,
      "cpuMs" -> sum(s => step(s) != "gc" && step(s) != "wait" && d(s(5)) >= 0, s => d(s(5))),
      "gcMs" -> sum(s => step(s) == "gc", s => d(s(4)) - d(s(3))),
      "waits" -> spans.count(s => step(s) == "wait"),
      "spans" -> spans)

  @volatile private var state = "idle" // idle, warming, running, done, cancelled, failed
  @volatile private var options = Options(List(1, 2, 4, 8), true, true, "pickler", 1)
  @volatile private var results: List[RunResult] = Nil
  @volatile private var files = 0
  @volatile private var error = ""
  @volatile private var currentN = 0
  @volatile private var currentRun = 0
  private val stopRequested = new AtomicBoolean(false)
  @volatile private var runner: Thread | Null = null

  def start(root: Path, opts: Options): Map[String, Any] = synchronized {
    if running then status() + ("error" -> "a batch run is in progress")
    else if ThroughputDemo.running then status() + ("error" -> "an interactive run is in progress")
    else
      options = opts; results = Nil; error = ""; currentN = 0; currentRun = 0; stopRequested.set(false)
      state = "warming"
      val t = new Thread((() => run(root)): Runnable, "xray-batch")
      t.setDaemon(true); runner = t; t.start()
      status()
  }

  def stop(): Unit = stopRequested.set(true)

  def running: Boolean = { val t = runner; t != null && t.isAlive }

  private def diskIO = sys.props.contains("xray.batch.diskIO")

  private val switches = List("parallelSignatures", "parallelIndex", "threads")

  @scala.annotation.nowarn("msg=Catching Throwable") // reported as the run's failure, not rethrown
  private def run(root: Path): Unit =
    val saved = switches.map(k => k -> sys.props.get(s"dotc.parallelTyper.$k"))
    try
      val paths = Throughput.loadFiles(root).map(f => root.resolve(f.rel).toString)
      files = paths.size
      // the classpath and sources in memory, read before the first run's timeline (`VirtualIO`)
      if !diskIO then { VirtualIO.classpath; VirtualIO.sources(root, paths) }
      def set(k: String, on: Boolean, value: String = "") =
        if on then System.setProperty(s"dotc.parallelTyper.$k", value) else System.clearProperty(s"dotc.parallelTyper.$k")
      set("parallelSignatures", options.parallelSignatures)
      set("parallelIndex", options.parallelIndex)
      def once(n: Int, timed: Boolean): (Double, Int, Int, List[List[Any]]) =
        set("threads", true, n.toString)
        val out = Files.createTempDirectory("xray-batch")
        val stop = if options.stopAfter == "all" then Nil else List("-Ystop-after:" + options.stopAfter)
        val flags = XrayServer.baseSettings ++ List(ParallelBatch.flag, "-d", out.toString) ++ stop
        // a clean heap for each run, before the timeline starts: the GC is not the compiler's
        System.gc()
        val tl = if timed then ParallelBatch.Timeline() else null
        ParallelBatch.timeline = tl
        val reporter = new StoreReporter(null)
        val t0 = System.nanoTime()
        val r =
          try
            if diskIO then new Driver().process((flags ++ paths).toArray, reporter)
            else VirtualIO.VirtualDriver(VirtualIO.classpath).compile(flags.toArray, VirtualIO.sources(root, paths), reporter)
          finally
            ParallelBatch.timeline = null
            if tl != null then tl.close()
        val wallMs = (System.nanoTime() - t0) / 1e6
        def ms(ns: Long) = math.round(ns / 1e3) / 1e3
        val spans = if tl == null then Nil else tl.spans.asScala.toList.sortBy(_.startNs).map(s =>
          List(s.thread, s.step, s.label, ms(s.startNs), ms(s.endNs), if s.cpuNs < 0 then -1.0 else ms(s.cpuNs), s.blockedMs, s.waitedMs))
        (math.round(wallMs * 10) / 10.0, r.errorCount, r.warningCount, spans)
      // the warm-up: the first thread count, not recorded, so that the first recorded run is not the cold one
      once(options.threads.head, timed = false)
      state = "running"
      var index = 0
      for n <- options.threads; i <- 1 to options.runs if !stopRequested.get do
        currentN = n; currentRun = i
        val (wallMs, errors, warnings, spans) = once(n, timed = true)
        index += 1
        results = results :+ RunResult(index, n, i, wallMs, errors, warnings, spans)
      state = if stopRequested.get then "cancelled" else "done"
    // every failure, a `LinkageError` too (the server's compiler classes rebuilt under it), so that a dead run says so
    catch case ex: Throwable =>
      error = ex.toString; state = "failed"
    finally
      for (k, v) <- saved do v match
        case Some(value) => System.setProperty(s"dotc.parallelTyper.$k", value)
        case None => System.clearProperty(s"dotc.parallelTyper.$k")

  /** The status; the runs' spans only with `spans`, for the run asked for. */
  def status(withSpansOf: Int = 0): Map[String, Any] =
    Map("state" -> state, "options" -> options.toMap, "files" -> files, "currentN" -> currentN, "currentRun" -> currentRun, "error" -> error,
      "processors" -> Runtime.getRuntime.availableProcessors, "build" -> XrayServer.gitTip,
      "runs" -> results.map(r => if r.index == withSpansOf then r.toMap else r.toMap - "spans"))

  def route(ex: HttpExchange, path: String, root: Option[Path]): Unit =
    val q = Option(ex.getRequestURI.getRawQuery).getOrElse("") + "&" + (if ex.getRequestMethod == "POST" then XrayServer.readBody(ex) else "")
    def flag(name: String, default: Boolean) = field(q, name).map(v => v == "1" || v == "true").getOrElse(default)
    path match
      case "/batch/run" =>
        val opts = Options(
          threads = field(q, "threads").map(ThroughputDemo.threadCounts).filter(_.nonEmpty).getOrElse(List(1, 2, 4, 8)),
          parallelIndex = flag("parallelIndex", true),
          parallelSignatures = flag("parallelSignatures", true),
          stopAfter = field(q, "stopAfter").filter(Set("typer", "pickler", "all")).getOrElse("pickler"),
          runs = field(q, "runs").flatMap(_.toIntOption).filter(n => n >= 1 && n <= 10).getOrElse(1))
        root match
          case Some(dir) => reply(ex, 200, "application/json", json(start(dir, opts)))
          case None => reply(ex, 400, "application/json", json(Map("error" -> "no source set")))
      case "/batch/stop" => stop(); reply(ex, 200, "application/json", json(status()))
      case "/batch/status" => reply(ex, 200, "application/json", json(status(field(q, "spans").flatMap(_.toIntOption).getOrElse(0))))
      case _ => reply(ex, 404, "text/plain", "not found")
