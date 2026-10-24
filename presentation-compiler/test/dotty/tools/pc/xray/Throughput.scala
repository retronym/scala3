package dotty.tools.pc.xray

import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{ConcurrentLinkedDeque, CountDownLatch}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

/** Throughput of several readers over one run (the multi-reader notes, §8):
 *  the whole of `compiler/src/dotty/tools/dotc` opened in one run with every
 *  top-level statement skipped (`InteractiveDriver.openAll`), then typed by
 *  k reader threads, each doing for a file what the x-ray's background pass
 *  does (the visible lines, then `readAll`), with `-Yinteractive-readers`.
 *  Each thread starts with the files a policy gives it and, when its list is
 *  empty, steals the last file of the longest list left, so that a policy's
 *  effect shows in the wall time without an idle tail.
 *
 *  Policies: `rr` round-robin by file; `pkg` by directory (`core`, `typer`,
 *  ...), packages assigned largest first to the least loaded thread;
 *  `islands` files clustered by their references to each other's top-level
 *  names (from a parse), split greedily into k groups of similar size with
 *  few edges between them; `warm` round-robin after one thread has typed
 *  every file's imports (the classpath roots the set imports), to separate
 *  root contention from the file graph's.
 *
 *  Baselines per round: the batch compiler on the same files up to the
 *  typer (`-Ystop-after:typer`), a whole-set run in the driver with one
 *  reader and nothing skipped, and k=1 round-robin in the serialised mode.
 *  Each measurement on a fresh driver; the diagnostics of every file
 *  compared with the whole-set run's.
 *
 *    -Dtp.ks=1,2,4,8 -Dtp.policies=rr,pkg,islands,warm -Dtp.rounds=3 -Dtp.warmup=1
 *    -Dtp.limit=<files> (the first N, for a quick check) -Dtp.out=<results.json>
 *
 *  Run from the worktree root with the presentation compiler's test
 *  classpath (README of the x-ray).
 */
object Throughput:

  private val root = Paths.get("compiler/src/dotty/tools/dotc")
  private val settings = XrayServer.baseSettings

  final case class FileJob(rel: String, uri: URI, source: SourceFile, size: Int):
    lazy val visible: Span =
      val lines = source.textContent().split('\n')
      val first = lines.length / 3
      val from = lines.take(first).map(_.length + 1).sum
      Span(from, from + lines.slice(first, first + 60).map(_.length + 1).sum)

  private def loadFiles(): List[FileJob] =
    val s = Files.walk(root)
    val rels = try s.iterator().asScala.filter(_.toString.endsWith(".scala")).map(p => root.relativize(p).toString).toList.sorted finally s.close()
    val limited = sys.props.get("tp.limit").map(n => rels.take(n.toInt)).getOrElse(rels)
    limited.map { rel =>
      val code = Files.readString(root.resolve(rel))
      val uri = URI.create(s"file:///tp/$rel")
      FileJob(rel, uri, SourceFile.virtual(uri, code), code.length)
    }

  // ---- policies

  /** The top-level names each file defines and the names it refers to, from
   *  a parse: the edges between files for `islands`.
   */
  private def edges(files: List[FileJob]): Map[(Int, Int), Int] =
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    given Context = driver.currentCtx
    val defined = mutable.HashMap.empty[String, mutable.Set[Int]]
    val used = files.indices.map(_ => mutable.HashMap.empty[String, Int]).toArray
    for (f, i) <- files.zipWithIndex do
      val tree = new Parsers.Parser(f.source)(using ctx.fresh.setSource(f.source)).parse()
      def top(t: untpd.Tree): Unit = t match
        case untpd.PackageDef(_, stats) => stats.foreach(top)
        case d: untpd.MemberDef => defined.getOrElseUpdate(d.name.toString.stripSuffix("$"), mutable.Set.empty) += i
        case _ =>
      top(tree)
      val acc = new untpd.UntypedTreeTraverser:
        def traverse(t: untpd.Tree)(using Context): Unit =
          t match
            case untpd.Ident(n) => used(i).updateWith(n.toString)(c => Some(c.getOrElse(0) + 1))
            case untpd.Select(_, n) => used(i).updateWith(n.toString)(c => Some(c.getOrElse(0) + 1))
            case _ =>
          traverseChildren(t)
      acc.traverse(tree)
    val out = mutable.HashMap.empty[(Int, Int), Int]
    for i <- files.indices; (name, n) <- used(i); j <- defined.getOrElse(name, Nil) if j != i do
      val k = if i < j then (i, j) else (j, i)
      out(k) = out.getOrElse(k, 0) + n
    out.toMap

  private def assign(policy: String, k: Int, files: List[FileJob], graph: => Map[(Int, Int), Int]): Array[List[FileJob]] =
    val groups = Array.fill(k)(mutable.ListBuffer.empty[FileJob])
    val load = Array.fill(k)(0L)
    def leastLoaded = load.indices.minBy(load(_))
    policy match
      case "rr" | "warm" =>
        files.zipWithIndex.foreach((f, i) => groups(i % k) += f)
      case "pkg" =>
        val byPkg = files.groupBy(f => if f.rel.contains('/') then f.rel.takeWhile(_ != '/') else "dotc")
        for (_, fs) <- byPkg.toList.sortBy(-_._2.map(_.size).sum) do
          val g = leastLoaded
          groups(g) ++= fs; load(g) += fs.map(_.size).sum
      case "islands" =>
        val idx = files.zipWithIndex.toMap
        val adj = mutable.HashMap.empty[Int, mutable.HashMap[Int, Int]]
        for ((a, b), w) <- graph do
          adj.getOrElseUpdate(a, mutable.HashMap.empty)(b) = w
          adj.getOrElseUpdate(b, mutable.HashMap.empty)(a) = w
        val cap = files.map(_.size.toLong).sum / k * 115 / 100
        val groupOf = mutable.HashMap.empty[Int, Int]
        for f <- files.sortBy(-_.size) do
          val i = idx(f)
          val toGroup = Array.fill(k)(0L)
          adj.getOrElse(i, mutable.HashMap.empty).foreach((j, w) => groupOf.get(j).foreach(g => toGroup(g) += w))
          val open = load.indices.filter(g => load(g) + f.size <= cap)
          val g = (if open.nonEmpty then open else load.indices).maxBy(g => (toGroup(g), -load(g)))
          groups(g) += f; load(g) += f.size; groupOf(i) = g
      case other => throw new IllegalArgumentException(s"no policy $other")
    groups.map(_.toList)

  // ---- one measurement

  final case class Cell(what: String, k: Int, wallMs: Double, indexMs: Double, warmMs: Double, workerCpuMs: Double, processCpuMs: Double,
      rootWaitMs: Double, rootWaits: Int, waitsByKind: Map[String, (Int, Double)], statementWaitMs: Double, backoffs: Int,
      steals: Int, diagnostics: Int, diagnosticsDiffer: Int, filesPerThread: List[Int], failure: String = "", cyclesRaised: Int = 0)

  private val showErrors = sys.props.getOrElse("tp.showErrors", "0").toInt
  private val threads = ManagementFactory.getThreadMXBean
  private val os = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]

  private def kindOf(root: String): String =
    List("pass types statement at line" -> "pass statement", "Namer signature" -> "Namer", "unpickle TASTy" -> "TASTy", "load classpath" -> "loader",
      "load source" -> "loader", "inherited cache" -> "inherited", "lazy TASTy read" -> "TASTy", "bring forward" -> "bring forward",
      "lazy reference" -> "lazy ref", "wait for a completion" -> "peek")
      .collectFirst { case (p, k) if root.startsWith(p) => k }.getOrElse(root.takeWhile(_ != ' '))

  private def freshDriver(readers: Boolean): InteractiveDriver =
    System.gc()
    val d = new InteractiveDriver(settings ++ (if readers then List("-Yinteractive-readers") else Nil), CachedLogicalPackage.none)
    d.run(XrayServer.generatedUri, SourceFile.virtual(XrayServer.generatedUri, XrayServer.generatedSource("warmup", 5)))
    d

  /** A file's errors, as `line: first line of the message`, sorted. */
  private def errorsOf(ds: List[dotty.tools.dotc.reporting.Diagnostic]): List[String] =
    ds.filter(_.level >= 2).map(d => s"${d.pos.line + 1}: ${d.msg.message.linesIterator.nextOption().getOrElse("").take(200)}").sorted

  def measure(what: String, k: Int, lists: Array[List[FileJob]], readers: Boolean, warm: Boolean, files: List[FileJob], reference: Map[URI, List[String]]): Cell =
    val driver = freshDriver(readers)
    val cpu0 = os.getProcessCpuTime
    val t0 = System.nanoTime()
    driver.openAll(files.map(f => f.uri -> f.source))
    val tIndex = System.nanoTime()
    if warm then
      // one reader types every file's imports: the roots the set imports
      for f <- files do
        val unit = driver.compilationUnits(f.uri)
        def imports(t: untpd.Tree): List[untpd.Tree] = t match
          case untpd.PackageDef(_, stats) => stats.flatMap(imports)
          case i: untpd.Import => List(i)
          case _ => Nil
        driver.withCancelChecker(() => false) {
          imports(unit.untpdTree).foreach(i => driver.readUpTo(f.uri, f.source, Span(i.span.start))((_, _, _) => ()))
        }
    val tWarm = System.nanoTime()
    val queues = lists.map(l => new ConcurrentLinkedDeque[FileJob](l.asJava))
    val start = new CountDownLatch(1)
    val readersSeen = new java.util.concurrent.ConcurrentLinkedQueue[dotty.tools.dotc.Reader]()
    val diags = new java.util.concurrent.ConcurrentHashMap[URI, List[String]]()
    val cpu = new java.util.concurrent.atomic.AtomicLong
    val steals = new java.util.concurrent.atomic.AtomicInteger
    val done = new java.util.concurrent.atomic.AtomicIntegerArray(k)
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val ts = (0 until k).map { t =>
      val th = new Thread((() => {
        val current = new java.util.concurrent.atomic.AtomicReference[String]("")
        start.await()
        val c0 = threads.getCurrentThreadCpuTime
        try
          var next = queues(t).pollFirst()
          while next != null do
            val f = next
            current.set(f.rel)
            driver.withCancelChecker(() => false) {
              readersSeen.add(driver.readerOfThisThread)
              driver.readUpTo(f.uri, f.source, Span(f.visible.start))((_, _, _) => ())
              val ds = driver.readAll(f.uri, f.source, f.visible)((_, _, ds) => ds)
              val got = errorsOf(ds)
              diags.put(f.uri, got)
              val want = reference.getOrElse(f.uri, Nil)
              if showErrors > 0 && got != want then
                val (extra, missing) = (got.diff(want), want.diff(got))
                println(s"[tp] DIVERGENCE $what k=$k thread=$t ${f.rel}: extra ${extra.size}, missing ${missing.size}")
                extra.take(showErrors).foreach { e =>
                  val full = ds.find(d => d.level >= 2 && s"${d.pos.line + 1}: ${d.msg.message.linesIterator.nextOption().getOrElse("").take(200)}" == e)
                    .map(d => d.msg.message.linesIterator.take(4).mkString(" / ")).getOrElse(e)
                  println(s"[tp]   + ${e.takeWhile(_ != ':')}: $full")
                }
                missing.take(showErrors).foreach(e => println(s"[tp]   - $e"))
            }
            done.incrementAndGet(t)
            next = queues(t).pollFirst()
            if next == null then
              // steal the last file of the longest list left
              val longest = queues.maxBy(_.size)
              next = longest.pollLast()
              if next != null then steals.incrementAndGet()
        catch case ex: (Exception | StackOverflowError | AssertionError | dotty.tools.dotc.CompilationUnit.CancelledCompilation) =>
          failure.compareAndSet(null, new RuntimeException(s"$what k=$k on ${current.get}: $ex", ex))
        finally cpu.addAndGet(threads.getCurrentThreadCpuTime - c0)
      }): Runnable, s"tp-reader-$t")
      th.setDaemon(true); th.start(); th
    }
    start.countDown()
    ts.foreach(_.join())
    val t1 = System.nanoTime()
    val cpu1 = os.getProcessCpuTime
    if failure.get != null then throw failure.get
    val rs = readersSeen.asScala.toList
    val byKind = rs.flatMap(_.lockWaits.asScala).groupBy(w => kindOf(w.root)).view.mapValues(ws => (ws.size, ws.map(_.nanos).sum / 1e6)).toMap
    val differ = files.count(f => !Option(diags.get(f.uri)).contains(reference.getOrElse(f.uri, Nil)))
    Cell(what, k, (t1 - t0) / 1e6, (tIndex - t0) / 1e6, (tWarm - tIndex) / 1e6, cpu.get / 1e6, (cpu1 - cpu0) / 1e6,
      rs.map(_.lockWaitNanos).sum / 1e6, rs.map(_.lockWaitCount).sum, byKind, rs.map(_.statementWaitNanos).sum / 1e6,
      rs.map(_.waitCyclesBackedOff).sum, steals.get, diags.values.asScala.map(_.size).sum, differ, (0 until k).map(done.get).toList, cyclesRaised = rs.map(_.cyclesRaised).sum)

  /** The whole set in one run with nothing skipped, one reader: the
   *  reference diagnostics, and a baseline. */
  def wholeSet(files: List[FileJob]): (Cell, Map[URI, List[String]]) =
    val driver = freshDriver(readers = false)
    val cpu0 = os.getProcessCpuTime
    val t0 = System.nanoTime()
    val ds = driver.withCancelChecker(() => false)(driver.openAll(files.map(f => f.uri -> f.source), typeAll = true))
    val t1 = System.nanoTime()
    val ref = ds.view.mapValues(errorsOf).toMap
    (Cell("whole-set run, one reader", 1, (t1 - t0) / 1e6, (t1 - t0) / 1e6, 0, (os.getProcessCpuTime - cpu0) / 1e6, (os.getProcessCpuTime - cpu0) / 1e6,
      0, 0, Map.empty, 0, 0, 0, ref.values.map(_.size).sum, 0, List(files.size)), ref)

  /** The batch compiler on the same files, up to the typer. */
  def batch(files: List[FileJob]): Cell =
    System.gc()
    val tmp = Files.createTempDirectory("tp-batch")
    val paths = files.map(f => root.resolve(f.rel).toString)
    val args = (settings.filterNot(_ == "-Ximport-suggestion-timeout").filterNot(_ == "0") ++ List("-Ystop-after:typer", "-d", tmp.toString) ++ paths).toArray
    val cpu0 = os.getProcessCpuTime
    val t0 = System.nanoTime()
    val reporter = new dotty.tools.dotc.Driver().process(args)
    val t1 = System.nanoTime()
    Cell("batch compiler to the typer", 1, (t1 - t0) / 1e6, 0, 0, (os.getProcessCpuTime - cpu0) / 1e6, (os.getProcessCpuTime - cpu0) / 1e6,
      0, 0, Map.empty, 0, 0, 0, reporter.errorCount, 0, List(files.size))

  def main(args: Array[String]): Unit =
    val ks = sys.props.getOrElse("tp.ks", "1,2,4,8").split(',').map(_.toInt).toList
    val policies = sys.props.getOrElse("tp.policies", "rr,pkg,islands,warm").split(',').toList
    val rounds = sys.props.getOrElse("tp.rounds", "3").toInt
    // `tp.loop=N`: the race hunt. One reference run, then N rounds of the
    // readers' cells only, every divergent file's errors diffed against the
    // reference and every failure's stack printed.
    val loop = sys.props.get("tp.loop").map(_.toInt)
    val warmup = sys.props.getOrElse("tp.warmup", "1").toInt
    val files = loadFiles()
    println(f"[tp] ${files.size} files, ${files.map(_.size.toLong).sum / 1e6}%.1f MB; ${Runtime.getRuntime.availableProcessors} processors; load ${os.getSystemLoadAverage}%.1f")
    lazy val graph = edges(files)
    loop.foreach { n =>
      val (_, reference) = wholeSet(files)
      var divergent = 0; var failed = 0
      for i <- 1 to n; k <- ks; p <- policies do
        try
          val c = measure(p, k, assign(p, k, files, graph), readers = true, warm = p == "warm", files, reference)
          divergent += c.diagnosticsDiffer
          println(f"[tp] loop $i $p k=$k: wall ${c.wallMs}%.0f ms, ${c.diagnosticsDiffer} files differ, backoffs ${c.backoffs}, cycles raised ${c.cyclesRaised}, root waits ${c.rootWaits}")
        catch case ex: RuntimeException =>
          failed += 1
          // a backed-off Namer completion left an `UnwoundCompletion`: the run is tainted (I53's rule), see §8
          val unwound = Iterator.iterate[Throwable | Null](ex)(_.nn.getCause).takeWhile(_ != null).exists(_.isInstanceOf[dotty.tools.dotc.CompilationUnit.CancelledCompilation])
          println(s"[tp] loop $i $p k=$k ${if unwound then "UNWOUND" else "FAILED"}: ${ex.getMessage}")
          var c: Throwable | Null = ex.getCause
          while c != null && c.getCause != null && (c.getCause ne c) do c = c.getCause
          if c != null then c.getStackTrace.iterator.filter(_.getClassName.startsWith("dotty")).take(40).foreach(f => println(s"[tp]     at $f"))
      println(s"[tp] loop done: $n rounds, $divergent divergent files, $failed failed cells")
      sys.exit(0)
    }
    val results = mutable.ListBuffer.empty[(Int, Cell)]
    def report(round: Int, c: Cell): Unit =
      results += ((round, c))
      println(f"[tp] round $round ${c.what} k=${c.k}: wall ${c.wallMs}%.0f ms (index ${c.indexMs}%.0f, warm ${c.warmMs}%.0f), worker CPU ${c.workerCpuMs}%.0f ms, process CPU ${c.processCpuMs}%.0f ms, " +
        f"root waits ${c.rootWaits} / ${c.rootWaitMs}%.0f ms ${c.waitsByKind.toList.sortBy(-_._2._2).map((k, v) => f"$k ${v._1}/${v._2}%.0f").mkString("[", ", ", "]")}, statement waits ${c.statementWaitMs}%.0f ms, backoffs ${c.backoffs}, steals ${c.steals}, " +
        f"errors ${c.diagnostics} (${c.diagnosticsDiffer} files differ), files per thread ${c.filesPerThread.mkString(",")}; load ${os.getSystemLoadAverage}%.1f")
    for round <- (1 - warmup) to rounds do
      val (whole, reference) = wholeSet(files)
      report(round, whole)
      report(round, batch(files))
      report(round, measure("serialised, round-robin", 1, assign("rr", 1, files, graph), readers = false, warm = false, files, reference).copy(what = "serialised rr"))
      for k <- ks; p <- policies do
        // a reader that fails ends the cell, not the campaign: recorded, and counted in the table
        val c =
          try measure(p, k, assign(p, k, files, graph), readers = true, warm = p == "warm", files, reference)
          catch case ex: RuntimeException =>
            Cell(p, k, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, 0, 0, Map.empty, 0, 0, 0, 0, 0, Nil, ex.getMessage.take(300))
        if c.failure.nonEmpty then println(s"[tp] round $round $p k=$k FAILED: ${c.failure}")
        report(round, c)
    // medians over the measured rounds
    val measured = results.filter(_._1 >= 1).map(_._2).toList
    println("\n| run | k | wall s | index s | worker CPU s | process CPU s | root waits (n / s) | backoffs | steals | files differ (max) | failed |")
    println("|---|---|---|---|---|---|---|---|---|---|---|")
    def med(xs: Seq[Double]) = if xs.isEmpty then Double.NaN else xs.sorted.apply(xs.size / 2)
    for ((what, k), all) <- measured.groupBy(c => (c.what, c.k)).toList.sortBy((wk, _) => (wk._2, wk._1)) do
      val cs = all.filter(_.failure.isEmpty)
      println(f"| $what | $k | ${med(cs.map(_.wallMs)) / 1e3}%.1f | ${med(cs.map(_.indexMs)) / 1e3}%.1f | ${med(cs.map(_.workerCpuMs)) / 1e3}%.1f | ${med(cs.map(_.processCpuMs)) / 1e3}%.1f | " +
        f"${med(cs.map(_.rootWaits.toDouble)).toInt} / ${med(cs.map(_.rootWaitMs)) / 1e3}%.1f | ${med(cs.map(_.backoffs.toDouble)).toInt} | ${med(cs.map(_.steals.toDouble)).toInt} | ${cs.map(_.diagnosticsDiffer).maxOption.getOrElse(0)} | ${all.size - cs.size} of ${all.size} |")
    sys.props.get("tp.out").foreach { out =>
      val json = measured.map(c => s"""{"what":"${c.what}","k":${c.k},"wallMs":${c.wallMs},"indexMs":${c.indexMs},"warmMs":${c.warmMs},"workerCpuMs":${c.workerCpuMs},"processCpuMs":${c.processCpuMs},"rootWaitMs":${c.rootWaitMs},"rootWaits":${c.rootWaits},"statementWaitMs":${c.statementWaitMs},"backoffs":${c.backoffs},"steals":${c.steals},"diagnostics":${c.diagnostics},"differ":${c.diagnosticsDiffer},"byKind":{${c.waitsByKind.map((k, v) => s""""$k":[${v._1},${v._2}]""").mkString(",")}}}""").mkString("[\n", ",\n", "\n]")
      Files.writeString(Paths.get(out), json)
    }
