package dotty.tools.pc.xray

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

import dotty.tools.dotc.{LockStats, ParallelBatch}
import dotty.tools.dotc.reporting.StoreReporter

/** The two granularities of the completion claim (`-Yinteractive-completion-lock:roots|global`) under the parallel
 *  batch typer (`-Yparallel-typer`, parallel index and signatures): the compiler's own sources compiled to the
 *  pickler in this JVM with the classpath, sources and output in memory (`VirtualIO`, as `BatchDemo`), for each
 *  thread count, after one untimed warm-up run per mode. The compiler's sources have many members with inferred
 *  result types, so a member's signature completion types its body: with one key every such completion runs alone.
 *
 *  Per run: wall time, process CPU, GC pauses, errors, the readers' waits on claims (`-Ddotc.interactive.lockStats`:
 *  count, total, the longest, and by what the waiter wanted and the holder was doing) and, from a
 *  `ParallelBatch.Timeline`, the wall and summed CPU of each step (parse, index, class headers, signatures, bodies,
 *  the later phases).
 *
 *    -Dblm.modes=roots,global -Dblm.threads=1,2,4,8 -Dblm.runs=3 -Dblm.warmup=1 -Dblm.stopAfter=pickler
 *    -Dblm.parallelSignatures=true -Dblm.parallelIndex=true -Dblm.limit=<files> -Dblm.root=<source tree>
 *
 *  Run from the worktree root with the presentation compiler's test classpath (README of the x-ray), with
 *  `-Ddotc.interactive.lockStats` for the wait statistics.
 */
object BatchLockModes:
  private val os = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
  private val root = Paths.get(sys.props.getOrElse("blm.root", "compiler/src/dotty/tools/dotc"))

  final case class Step(wallMs: Double, cpuMs: Double, spans: Int)
  final case class Run(mode: String, threads: Int, wallMs: Double, cpuMs: Double, gcMs: Double, errors: Int,
      waits: Long, waitMs: Double, longestWaitMs: Double, steps: Map[String, Step], lockStats: String)

  private def gcMs: Double = ManagementFactory.getGarbageCollectorMXBeans.asScala.map(_.getCollectionTime.toDouble).sum

  def once(mode: String, n: Int, paths: List[String], flags0: List[String], timed: Boolean): Run =
    System.setProperty("dotc.parallelTyper.threads", n.toString)
    val flags = flags0 ++ List(ParallelBatch.flag, s"-Yinteractive-completion-lock:$mode")
    System.gc()
    val tl = if timed then ParallelBatch.Timeline() else null
    ParallelBatch.timeline = tl
    if LockStats.on then LockStats.reset()
    val reporter = new StoreReporter(null)
    val cpu0 = os.getProcessCpuTime
    val gc0 = gcMs
    val t0 = System.nanoTime()
    try VirtualIO.VirtualDriver(VirtualIO.classpath).compile(flags.toArray, VirtualIO.sources(root, paths), reporter)
    finally
      ParallelBatch.timeline = null
      if tl != null then tl.close()
    val wall = (System.nanoTime() - t0) / 1e6
    val steps =
      if tl == null then Map.empty[String, Step]
      else tl.spans.asScala.toList.filter(s => s.step != "gc" && s.step != "wait").groupBy(s => if s.step == "phase" then s"phase ${s.label}" else s.step).view.mapValues { ss =>
        Step((ss.map(_.endNs).max - ss.map(_.startNs).min) / 1e6, ss.map(_.cpuNs).filter(_ >= 0).sum / 1e6, ss.size)
      }.toMap
    Run(mode, n, wall, (os.getProcessCpuTime - cpu0) / 1e6, gcMs - gc0, reporter.errorCount,
      if LockStats.on then LockStats.totalCount else -1, if LockStats.on then LockStats.totalNanos / 1e6 else Double.NaN,
      if LockStats.on then LockStats.maxNanos / 1e6 else Double.NaN, steps, if LockStats.on then LockStats.summary else "")

  def main(args: Array[String]): Unit =
    val modes = sys.props.getOrElse("blm.modes", "roots,global").split(',').toList
    val threads = sys.props.getOrElse("blm.threads", "1,2,4,8").split(',').map(_.toInt).toList
    val runs = sys.props.getOrElse("blm.runs", "3").toInt
    val warmup = sys.props.getOrElse("blm.warmup", "1").toInt
    val stopAfter = sys.props.getOrElse("blm.stopAfter", "pickler")
    def switch(k: String, default: String) = if sys.props.getOrElse(s"blm.$k", default).toBoolean then System.setProperty(s"dotc.parallelTyper.$k", "") else System.clearProperty(s"dotc.parallelTyper.$k")
    switch("parallelSignatures", "true"); switch("parallelIndex", "true")
    val files0 = Throughput.loadFiles(root)
    val files = sys.props.get("blm.limit").map(k => files0.take(k.toInt)).getOrElse(files0)
    val paths = files.map(f => root.resolve(f.rel).toString)
    VirtualIO.classpath; VirtualIO.sources(root, paths)
    val flags = XrayServer.baseSettings ++ List("-d", Files.createTempDirectory("blm").toString) ++ (if stopAfter == "all" then Nil else List("-Ystop-after:" + stopAfter))
    println(f"[blm] ${paths.size} files; ${Runtime.getRuntime.availableProcessors} processors; load ${os.getSystemLoadAverage}%.1f; lockStats ${LockStats.on}; parallelSignatures ${sys.props.contains("dotc.parallelTyper.parallelSignatures")}; parallelIndex ${sys.props.contains("dotc.parallelTyper.parallelIndex")}; stop after $stopAfter")
    val results = mutable.ListBuffer.empty[Run]
    val stepOrder = List("parse", "index", "class headers", "signatures", "bodies")
    def show(r: Run, tag: String): Unit =
      val steps = (stepOrder ++ r.steps.keys.filterNot(stepOrder.contains).toList.sorted).flatMap(k => r.steps.get(k).map(s => f"$k ${s.wallMs}%.0f/${s.cpuMs}%.0f")).mkString(", ")
      println(f"[blm] $tag ${r.mode} threads=${r.threads}: wall ${r.wallMs}%.0f ms, CPU ${r.cpuMs}%.0f ms, GC ${r.gcMs}%.0f ms, errors ${r.errors}; waits ${r.waits}, waited ${r.waitMs}%.0f ms, longest ${r.longestWaitMs}%.1f ms; steps (wall/CPU ms): $steps; load ${os.getSystemLoadAverage}%.1f")
      if r.lockStats.nonEmpty then println(r.lockStats.linesIterator.map("[blm]   " + _).mkString("\n"))
    for mode <- modes; i <- 1 to warmup do show(once(mode, threads.head, paths, flags, timed = false), s"warmup $i")
    for i <- 1 to runs; n <- threads; mode <- modes do
      val r =
        try once(mode, n, paths, flags, timed = true)
        catch case ex: (Exception | StackOverflowError | AssertionError) =>
          println(s"[blm] run $i $mode threads=$n FAILED: $ex")
          ex.getStackTrace.iterator.filter(_.getClassName.startsWith("dotty")).take(30).foreach(f => println(s"[blm]     at $f"))
          Run(mode, n, Double.NaN, Double.NaN, Double.NaN, -1, 0, Double.NaN, Double.NaN, Map.empty, "")
      results += r
      show(r, s"run $i")
    def med(xs: Seq[Double]) = if xs.isEmpty then Double.NaN else xs.sorted.apply(xs.size / 2)
    println("\n| mode | threads | wall ms | CPU ms | GC ms | signatures wall / CPU ms | bodies wall / CPU ms | waits | waited ms | longest wait ms | errors |")
    println("|---|---|---|---|---|---|---|---|---|---|---|")
    for ((n, mode), rs0) <- results.groupBy(r => (r.threads, r.mode)).toList.sortBy((k, _) => (k._1, modes.indexOf(k._2))) do
      val rs = rs0.filter(r => !r.wallMs.isNaN).toList
      def step(k: String, f: Step => Double) = med(rs.flatMap(_.steps.get(k)).map(f))
      println(f"| $mode | $n | ${med(rs.map(_.wallMs))}%.0f | ${med(rs.map(_.cpuMs))}%.0f | ${med(rs.map(_.gcMs))}%.0f | ${step("signatures", _.wallMs)}%.0f / ${step("signatures", _.cpuMs)}%.0f | ${step("bodies", _.wallMs)}%.0f / ${step("bodies", _.cpuMs)}%.0f | ${med(rs.map(_.waits.toDouble)).toLong} | ${med(rs.map(_.waitMs))}%.0f | ${med(rs.map(_.longestWaitMs))}%.1f | ${rs.map(_.errors).maxOption.getOrElse(-1)} |")
