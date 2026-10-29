package dotty.tools.pc.xray

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

import com.sun.net.httpserver.HttpExchange

import dotty.tools.dotc.{Driver, ParallelBatch}
import dotty.tools.dotc.reporting.StoreReporter

/** The x-ray's batch view: the batch compiler under `-Yparallel-typer` over every file of the source set, in this
 *  JVM, run `runs` times so that the later runs are warm, each with a `ParallelBatch.Timeline`: a span per unit of
 *  each step (parse, index, class headers, signatures, bodies) on the thread that did it, the later phases on the
 *  run's thread, the waits on completion roots and the GC pauses, each with the thread's CPU time and its time
 *  blocked on monitors or parked. The page draws a lane per worker. Runs on its own thread, never the HTTP
 *  dispatcher's; the switches are the compiler's system properties (`dotc.parallelTyper.*`), set for the run.
 */
object BatchDemo:
  import XrayServer.{json, reply, field}

  final case class Options(threads: Int, parallelIndex: Boolean, parallelSignatures: Boolean, stopAfter: String, runs: Int):
    def toMap: Map[String, Any] = Map("threads" -> threads, "parallelIndex" -> parallelIndex,
      "parallelSignatures" -> parallelSignatures, "stopAfter" -> stopAfter, "runs" -> runs)

  /** A run: its wall time, errors and warnings, and the spans as
   *  `[thread, step, label, startMs, endMs, cpuMs, blockedMs, waitedMs]` (-1 where not measured). */
  final case class RunResult(index: Int, wallMs: Double, errors: Int, warnings: Int, spans: List[List[Any]]):
    def toMap: Map[String, Any] = Map("index" -> index, "wallMs" -> wallMs, "errors" -> errors, "warnings" -> warnings, "spans" -> spans)

  @volatile private var state = "idle" // idle, running, done, cancelled, failed
  @volatile private var options = Options(Runtime.getRuntime.availableProcessors, true, true, "pickler", 3)
  @volatile private var results: List[RunResult] = Nil
  @volatile private var files = 0
  @volatile private var error = ""
  @volatile private var current = 0
  private val stopRequested = new AtomicBoolean(false)
  @volatile private var runner: Thread | Null = null

  def start(root: Path, opts: Options): Map[String, Any] = synchronized {
    if runner != null && runner.isAlive then status() + ("error" -> "a batch run is in progress")
    else
      options = opts; results = Nil; error = ""; current = 0; stopRequested.set(false)
      state = "running"
      val t = new Thread((() => run(root)): Runnable, "xray-batch")
      t.setDaemon(true); runner = t; t.start()
      status()
  }

  def stop(): Unit = stopRequested.set(true)

  private val switches = List("parallelSignatures", "parallelIndex", "threads")

  private def run(root: Path): Unit =
    val saved = switches.map(k => k -> sys.props.get(s"dotc.parallelTyper.$k"))
    try
      val paths = Throughput.loadFiles(root).map(f => root.resolve(f.rel).toString)
      files = paths.size
      def set(k: String, on: Boolean, value: String = "") =
        if on then System.setProperty(s"dotc.parallelTyper.$k", value) else System.clearProperty(s"dotc.parallelTyper.$k")
      set("parallelSignatures", options.parallelSignatures)
      set("parallelIndex", options.parallelIndex)
      set("threads", true, options.threads.toString)
      for i <- 1 to options.runs if !stopRequested.get do
        current = i
        val out = Files.createTempDirectory("xray-batch")
        val stop = if options.stopAfter == "all" then Nil else List("-Ystop-after:" + options.stopAfter)
        val args = (XrayServer.baseSettings ++ List(ParallelBatch.flag, "-d", out.toString) ++ stop ++ paths).toArray
        // a clean heap for each run, before the timeline starts: the GC is not the compiler's
        System.gc()
        val tl = ParallelBatch.Timeline()
        ParallelBatch.timeline = tl
        val reporter = new StoreReporter(null)
        val t0 = System.nanoTime()
        val r =
          try new Driver().process(args, reporter)
          finally
            ParallelBatch.timeline = null
            tl.close()
        val wallMs = (System.nanoTime() - t0) / 1e6
        def ms(ns: Long) = math.round(ns / 1e3) / 1e3
        val spans = tl.spans.asScala.toList.sortBy(_.startNs).map(s =>
          List(s.thread, s.step, s.label, ms(s.startNs), ms(s.endNs), if s.cpuNs < 0 then -1.0 else ms(s.cpuNs), s.blockedMs, s.waitedMs))
        results = results :+ RunResult(i, math.round(wallMs * 10) / 10.0, r.errorCount, r.warningCount, spans)
      state = if stopRequested.get then "cancelled" else "done"
    catch case ex: (Exception | StackOverflowError | AssertionError | OutOfMemoryError) =>
      error = ex.toString; state = "failed"
    finally
      for (k, v) <- saved do v match
        case Some(value) => System.setProperty(s"dotc.parallelTyper.$k", value)
        case None => System.clearProperty(s"dotc.parallelTyper.$k")

  /** The status; the runs' spans only with `spans`, for the run asked for. */
  def status(withSpansOf: Int = 0): Map[String, Any] =
    Map("state" -> state, "options" -> options.toMap, "files" -> files, "current" -> current, "error" -> error,
      "processors" -> Runtime.getRuntime.availableProcessors, "build" -> XrayServer.gitTip,
      "runs" -> results.map(r => if r.index == withSpansOf then r.toMap else r.toMap - "spans"))

  def route(ex: HttpExchange, path: String, root: Option[Path]): Unit =
    val q = Option(ex.getRequestURI.getRawQuery).getOrElse("") + "&" + (if ex.getRequestMethod == "POST" then XrayServer.readBody(ex) else "")
    def flag(name: String, default: Boolean) = field(q, name).map(v => v == "1" || v == "true").getOrElse(default)
    path match
      case "/batch/run" =>
        val opts = Options(
          threads = field(q, "threads").flatMap(_.toIntOption).filter(n => n >= 1 && n <= 64).getOrElse(Runtime.getRuntime.availableProcessors),
          parallelIndex = flag("parallelIndex", true),
          parallelSignatures = flag("parallelSignatures", true),
          stopAfter = field(q, "stopAfter").filter(Set("typer", "pickler", "all")).getOrElse("pickler"),
          runs = field(q, "runs").flatMap(_.toIntOption).filter(n => n >= 1 && n <= 10).getOrElse(3))
        root match
          case Some(dir) => reply(ex, 200, "application/json", json(start(dir, opts)))
          case None => reply(ex, 400, "application/json", json(Map("error" -> "no source set")))
      case "/batch/stop" => stop(); reply(ex, 200, "application/json", json(status()))
      case "/batch/status" => reply(ex, 200, "application/json", json(status(field(q, "spans").flatMap(_.toIntOption).getOrElse(0))))
      case _ => reply(ex, 404, "text/plain", "not found")
