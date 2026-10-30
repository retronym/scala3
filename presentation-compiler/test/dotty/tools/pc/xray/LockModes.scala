package dotty.tools.pc.xray

import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls
import scala.util.Random

import dotty.tools.dotc.LockStats
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Interactive, InteractiveDriver}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

/** The two granularities of the completion claim (`-Yinteractive-completion-lock:roots|global`) on an editor-like
 *  workload: `compiler/src/dotty/tools/dotc` opened in one run with every statement skipped (`openAll`), then k reader
 *  threads issuing hover requests (`readUpTo` at an identifier, the type of the tree there) at random identifiers
 *  of random files, one request in ten a whole-file read (`readAll`, as inlay hints or diagnostics cost). The
 *  sequence of requests is a seed's, the same in every mode. The compiler's sources have many members with inferred
 *  result types, so a Namer completion here types bodies: what a global lock would serialise.
 *
 *  Per cell (mode, k): wall time per request (median, p90, max), the readers' waits on claims (`Reader.lockWaitNanos`:
 *  total, per request, the longest), cycle aborts and raised cycles, and, with `-Ddotc.interactive.lockStats`, the
 *  waits by what the waiter wanted and what the holder was doing. The hover answers are compared between the modes of
 *  a round, and the files whose answers differ counted.
 *
 *    -Dlm.modes=roots,global -Dlm.ks=2,4 -Dlm.requests=300 (per reader) -Dlm.rounds=2 -Dlm.warmup=1 -Dlm.seed=1
 *    -Dlm.limit=<files> -Dlm.root=<source tree> -Dlm.out=<results.json> -Dlm.wholeFileEvery=10 (0 for hovers only)
 *
 *  Run from the worktree root with the presentation compiler's test classpath (README of the x-ray).
 */
object LockModes:
  private val os = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
  private val settings = XrayServer.baseSettings
  private val root = Paths.get(sys.props.getOrElse("lm.root", "compiler/src/dotty/tools/dotc"))

  final case class Cell(mode: String, k: Int, round: Int, requests: Int, wholeFile: Int, wallMs: Double, openMs: Double,
      hoverMs: List[Double], wholeFileMs: List[Double], waitMs: Double, waits: Long, longestWaitMs: Double,
      cycleAborts: Int, cyclesRaised: Int, answersDiffer: Int, lockStats: String):
    def med(xs: List[Double]) = if xs.isEmpty then Double.NaN else xs.sorted.apply(xs.size / 2)
    def p90(xs: List[Double]) = if xs.isEmpty then Double.NaN else xs.sorted.apply(math.min(xs.size - 1, (xs.size * 0.9).toInt))
    def max(xs: List[Double]) = xs.maxOption.getOrElse(Double.NaN)

  final case class Request(file: Int, offset: Int, whole: Boolean)

  /** The identifier ends of `code`, hover targets (a lexical scan: keywords are hovers like any other). */
  private def identifierEnds(code: String): Array[Int] =
    val m = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(code)
    val out = mutable.ArrayBuffer.empty[Int]
    while m.find() do if !code.startsWith("//", math.max(0, code.lastIndexOf('\n', m.start()) + 1)) then out += m.end()
    out.toArray

  /** `requests` per reader for `k` readers: the request lists, from `seed`. */
  private def plan(files: List[Throughput.FileJob], idents: Array[Array[Int]], k: Int, requests: Int, seed: Int, wholeFileEvery: Int): Array[List[Request]] =
    Array.tabulate(k) { t =>
      val rng = new Random(seed * 31 + t)
      List.tabulate(requests) { i =>
        val f = rng.nextInt(files.size)
        Request(f, idents(f)(rng.nextInt(idents(f).length)), wholeFileEvery > 0 && i % wholeFileEvery == wholeFileEvery - 1)
      }
    }

  /** The type of the innermost typed tree at `offset`, the hover's answer. */
  private def describe(ctx0: Context, unit: dotty.tools.dotc.CompilationUnit, offset: Int): String =
    given Context = ctx0.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using ctx0))
    try
      Interactive.pathTo(unit.tpdTree, Span(offset)).headOption match
        case Some(t) if t.hasType => t.tpe.widenTermRefExpr.show
        case Some(_) => "<untyped>"
        case None => "<no tree>"
    catch case scala.util.control.NonFatal(ex) => s"<${ex.getClass.getSimpleName}>"

  def measure(mode: String, k: Int, round: Int, files: List[Throughput.FileJob], plans: Array[List[Request]],
      answers: mutable.Map[(Int, Request), String]): Cell =
    System.gc()
    val driver = new InteractiveDriver(settings ++ List("-Yinteractive-parallel", s"-Yinteractive-completion-lock:$mode"), CachedLogicalPackage.none)
    driver.run(XrayServer.generatedUri, SourceFile.virtual(XrayServer.generatedUri, XrayServer.generatedSource("warmup", 5)))
    // new content per round, so that the open is a fresh run of the files
    val sources = files.map(f => f.uri -> SourceFile.virtual(f.uri, f.source.textContent() + s"\n// round $round\n"))
    val t0 = System.nanoTime()
    driver.openAll(sources)
    val tOpen = System.nanoTime()
    if LockStats.on then LockStats.reset()
    val start = new CountDownLatch(1)
    val hoverMs = new java.util.concurrent.ConcurrentLinkedQueue[Double]()
    val wholeMs = new java.util.concurrent.ConcurrentLinkedQueue[Double]()
    val readers = new java.util.concurrent.ConcurrentLinkedQueue[dotty.tools.dotc.Reader]()
    val differ = new java.util.concurrent.atomic.AtomicInteger
    val failure = new AtomicReference[Throwable]()
    val ts = (0 until k).map { t =>
      val th = new Thread((() => {
        start.await()
        try
          for r <- plans(t) if failure.get == null do
            val (uri, src) = sources(r.file)
            val t1 = System.nanoTime()
            val answer = driver.withCancelChecker(() => false) {
              readers.add(driver.readerOfThisThread)
              if r.whole then { driver.readAll(uri, src)((_, _, _) => ()); "" }
              else driver.readUpTo(uri, src, Span(r.offset))((ctx, unit, _) => describe(ctx, unit, r.offset))
            }
            val ms = (System.nanoTime() - t1) / 1e6
            if r.whole then wholeMs.add(ms) else hoverMs.add(ms)
            if !r.whole then answers.synchronized {
              answers.get((t, r)) match
                case Some(a) => if a != answer then differ.incrementAndGet()
                case None => answers((t, r)) = answer
            }
        catch case ex: (Exception | StackOverflowError | AssertionError | dotty.tools.dotc.CompilationUnit.CancelledCompilation) =>
          failure.compareAndSet(null, new RuntimeException(s"$mode k=$k reader $t: $ex", ex))
      }): Runnable, s"lm-$mode-reader-$t")
      th.setDaemon(true); th.start(); th
    }
    start.countDown()
    ts.foreach(_.join())
    val t1 = System.nanoTime()
    if failure.get != null then throw failure.get
    val rs = readers.asScala.toSet.toList
    Cell(mode, k, round, plans.map(_.size).sum, plans.map(_.count(_.whole)).sum, (t1 - tOpen) / 1e6, (tOpen - t0) / 1e6,
      hoverMs.asScala.toList, wholeMs.asScala.toList,
      rs.map(_.lockWaitNanos).sum / 1e6, rs.map(_.lockWaitCount.toLong).sum, rs.map(_.lockWaitMaxNanos).maxOption.getOrElse(0L) / 1e6,
      rs.map(_.waitCyclesAborted).sum, rs.map(_.cyclesRaised).sum, differ.get, if LockStats.on then LockStats.summary else "")

  def main(args: Array[String]): Unit =
    val modes = sys.props.getOrElse("lm.modes", "roots,global").split(',').toList
    val ks = sys.props.getOrElse("lm.ks", "2,4").split(',').map(_.toInt).toList
    val requests = sys.props.getOrElse("lm.requests", "300").toInt
    val rounds = sys.props.getOrElse("lm.rounds", "2").toInt
    val warmup = sys.props.getOrElse("lm.warmup", "1").toInt
    val seed = sys.props.getOrElse("lm.seed", "1").toInt
    val wholeFileEvery = sys.props.getOrElse("lm.wholeFileEvery", "10").toInt
    val files0 = Throughput.loadFiles(root)
    val files = sys.props.get("lm.limit").map(n => files0.take(n.toInt)).getOrElse(files0)
    val idents = files.map(f => identifierEnds(f.source.textContent())).toArray
    println(f"[lm] ${files.size} files, ${idents.map(_.length.toLong).sum} identifiers; ${Runtime.getRuntime.availableProcessors} processors; load ${os.getSystemLoadAverage}%.1f; lockStats ${LockStats.on}")
    val results = mutable.ListBuffer.empty[Cell]
    for round <- (1 - warmup) to rounds; k <- ks do
      val plans = plan(files, idents, k, requests, seed + round, wholeFileEvery)
      val answers = mutable.Map.empty[(Int, Request), String]
      for mode <- modes do
        val c =
          try measure(mode, k, round, files, plans, answers)
          catch case ex: RuntimeException =>
            println(s"[lm] round $round $mode k=$k FAILED: ${ex.getMessage}")
            ex.getCause.getStackTrace.iterator.filter(_.getClassName.startsWith("dotty")).take(30).foreach(f => println(s"[lm]     at $f"))
            Cell(mode, k, round, 0, 0, Double.NaN, Double.NaN, Nil, Nil, Double.NaN, 0, Double.NaN, 0, 0, 0, "")
        if round >= 1 then results += c
        println(f"[lm] round $round $mode k=$k: ${c.requests} requests in ${c.wallMs}%.0f ms (open ${c.openMs}%.0f ms); hover ${c.med(c.hoverMs)}%.1f / ${c.p90(c.hoverMs)}%.1f / ${c.max(c.hoverMs)}%.0f ms (median / p90 / max), " +
          f"whole-file ${c.med(c.wholeFileMs)}%.0f / ${c.p90(c.wholeFileMs)}%.0f / ${c.max(c.wholeFileMs)}%.0f ms; waits ${c.waits} (${c.waits.toDouble / math.max(1, c.requests)}%.2f per request), waited ${c.waitMs}%.0f ms, longest ${c.longestWaitMs}%.1f ms; " +
          f"cycle aborts ${c.cycleAborts}, cycles raised ${c.cyclesRaised}, answers differing from the round's first mode ${c.answersDiffer}; load ${os.getSystemLoadAverage}%.1f")
        if c.lockStats.nonEmpty then println(c.lockStats.linesIterator.map("[lm]   " + _).mkString("\n"))
    println("\n| mode | k | requests | wall s | hover median ms | hover p90 ms | hover max ms | whole-file median ms | whole-file max ms | waits | waits / request | waited s | longest wait ms | cycle aborts | cycles raised | answers differ |")
    println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    def med(xs: Seq[Double]) = if xs.isEmpty then Double.NaN else xs.sorted.apply(xs.size / 2)
    for ((mode, k), cs0) <- results.groupBy(c => (c.mode, c.k)).toList.sortBy((mk, _) => (mk._2, modes.indexOf(mk._1))) do
      val cs = cs0.filter(_.requests > 0).toList
      println(f"| $mode | $k | ${cs.headOption.map(_.requests).getOrElse(0)} | ${med(cs.map(_.wallMs)) / 1e3}%.1f | ${med(cs.map(c => c.med(c.hoverMs)))}%.1f | ${med(cs.map(c => c.p90(c.hoverMs)))}%.1f | ${med(cs.map(c => c.max(c.hoverMs)))}%.0f | " +
        f"${med(cs.map(c => c.med(c.wholeFileMs)))}%.0f | ${med(cs.map(c => c.max(c.wholeFileMs)))}%.0f | ${med(cs.map(_.waits.toDouble)).toLong} | ${med(cs.map(c => c.waits.toDouble / math.max(1, c.requests)))}%.2f | ${med(cs.map(_.waitMs)) / 1e3}%.1f | ${med(cs.map(_.longestWaitMs))}%.0f | " +
        f"${cs.map(_.cycleAborts).sum} | ${cs.map(_.cyclesRaised).sum} | ${cs.map(_.answersDiffer).sum} |")
    sys.props.get("lm.out").foreach { out =>
      val json = results.map(c => s"""{"mode":"${c.mode}","k":${c.k},"round":${c.round},"requests":${c.requests},"wallMs":${c.wallMs},"openMs":${c.openMs},"hoverMs":[${c.hoverMs.mkString(",")}],"wholeFileMs":[${c.wholeFileMs.mkString(",")}],"waitMs":${c.waitMs},"waits":${c.waits},"longestWaitMs":${c.longestWaitMs},"cycleAborts":${c.cycleAborts},"cyclesRaised":${c.cyclesRaised},"answersDiffer":${c.answersDiffer}}""").mkString("[\n", ",\n", "\n]")
      Files.writeString(Paths.get(out), json)
    }
