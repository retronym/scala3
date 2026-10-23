package dotty.tools.dotc

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import core.Contexts.Context
import reporting.{Diagnostic, Reporter}
import interfaces.Diagnostic.ERROR
import dotty.tools.vulpix.TestConfiguration

/** Checks that MegaPhase fusion does not change the compiler's output.
 *
 *  Compiles each test in the given `tests/<category>` directories twice, normally and with
 *  every miniphase in a traversal of its own (the research plugin `plugins.UnfusedPhases`),
 *  and compares the output directories (class files, TASTy, SemanticDB)
 *  byte for byte. Tests are found as in Vulpix: a file, or a directory whose `*_<n>.scala`
 *  files are compiled as separate groups in order. `//> using options` are honoured.
 *
 *  Usage (from sbt):
 *
 *    scala3-compiler-nonbootstrapped/Test/runMain dotty.tools.dotc.FusionCrossCheck
 *      [--out <dir>] [--jobs <n>] [--filter <regex>] [--ycheck] [--run] [--keep] [--self] tests/pos tests/run ...
 *
 *  `--self` compiles both sides normally instead, which finds output that is not reproducible
 *  from one compile to the next in the same JVM, independently of fusion.
 *
 *  `--ycheck` adds `-Ycheck:all` to the fused compile, and to the unfused one for every phase
 *  except those between `flatten` and `restoreScopes`, whose intermediate trees are not expected
 *  to type check. `--run` runs `Test` from both outputs of a differing `run*` test and compares
 *  what it prints. Results go to `<out>/results.tsv`; anything other than `same` or a `fused-*`
 *  status (the test does not compile normally under this driver) is printed as it happens.
 */
object FusionCrossCheck:
  final case class Conf(
    categories: List[Path] = Nil, out: Path = repoRoot.resolve("out/fusion-crosscheck"), jobs: Int = 4,
    filter: Option[String] = None, ycheck: Boolean = false, run: Boolean = false, keep: Boolean = false,
    self: Boolean = false, timeoutSec: Int = 600)

  final case class Group(files: List[Path], options: List[String])
  final case class Test(category: String, name: String, root: Path, groups: List[Group])
  final case class Outcome(status: String, detail: String, millis: Long = 0)

  /** Vulpix's options without -Ycheck (TestConfiguration.checkOptions, noCheckOptions). */
  private val baseOptions = List("-indent", "-Yno-deep-subtypes", "-Yno-double-bindings", "-Yforce-sbt-phases",
    "-Xsemanticdb", "-Xverify-signatures", "-pagewidth", "120", "-Xtarget", "17", "-nowarn", "-color:never")

  /** Flatten leaves owner scopes inconsistent until RestoreScopes repairs them. */
  private val notCheckableUnfused = Set("flatten", "transformWildcards", "moveStatic", "expandPrivate")

  /** Unfusing gives more phase ids than capture checking can encode for its recheck phases. */
  private val unfusedLimits = List("outside range 0..63, cannot use Long bits encoding")

  private val libraryClasspath = TestConfiguration.basicClasspath

  /** sbt forks `Test/runMain` in `compiler/`; relative paths are taken relative to the repository root. */
  private lazy val repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    if Files.isDirectory(cwd.resolve("tests")) then cwd else cwd.getParent

  def main(args: Array[String]): Unit =
    val conf = parse(args.toList, Conf())
    val tests = conf.categories.flatMap(discover)
      .filter(t => conf.filter.forall(r => s"${t.category}/${t.name}".matches(r)))
    Files.createDirectories(conf.out)
    val phaseNames = (new Compiler).phases.flatten.map(_.phaseName).distinct
    val fusedOpts = if conf.ycheck then List("-Ycheck:all") else Nil
    val unfusedOpts =
      if conf.self then fusedOpts
      else if conf.ycheck then List(phaseNames.filterNot(notCheckableUnfused).mkString("-Ycheck:", ",", "")) else Nil
    println(s"[fusion-crosscheck] ${tests.length} tests, ${conf.jobs} jobs, results in ${conf.out.toAbsolutePath}/results.tsv")

    val pool = Executors.newFixedThreadPool(conf.jobs)
    val pending = tests.map: t =>
      t -> pool.submit: () =>
        val t0 = System.nanoTime
        crossCheck(t, conf, fusedOpts, unfusedOpts).copy(millis = (System.nanoTime - t0) / 1000000)
    val w = Files.newBufferedWriter(conf.out.resolve("results.tsv"))
    w.write("category\ttest\tstatus\tmillis\tdetail\n")
    val counts = collection.mutable.TreeMap[String, Int]()
    for ((t, f), i) <- pending.zipWithIndex do
      val o =
        try f.get(conf.timeoutSec.toLong, TimeUnit.SECONDS)
        catch
          case _: TimeoutException => f.cancel(true); Outcome("timeout", "")
          case e: java.util.concurrent.ExecutionException => Outcome("harness-error", e.getCause.toString)
      counts(o.status) = counts.getOrElse(o.status, 0) + 1
      w.write(s"${t.category}\t${t.name}\t${o.status}\t${o.millis}\t${o.detail.replaceAll("[\t\n]", " ").take(2000)}\n")
      w.flush()
      if o.status != "same" && !o.status.startsWith("fused-") then
        println(s"[fusion-crosscheck] ${o.status} ${t.category}/${t.name}: ${o.detail.take(300)}")
      if (i + 1) % 500 == 0 then println(s"[fusion-crosscheck] ${i + 1}/${tests.length} ${counts.mkString(" ")}")
    w.close()
    pool.shutdownNow()
    println(s"[fusion-crosscheck] done: ${counts.mkString(" ")}")
    System.exit(0) // do not wait for compiles abandoned after a timeout

  // --- discovery -----------------------------------------------------------------------------

  private val GroupSuffix = """.*_(\d+)\.(scala|java)""".r

  def discover(dir: Path): List[Test] =
    val category = dir.getFileName.toString
    val root = dir.toAbsolutePath
    Files.list(dir).iterator.asScala.toList.sortBy(_.getFileName.toString).flatMap: p =>
      val name = p.getFileName.toString
      if Files.isDirectory(p) then
        val files = Files.walk(p).iterator.asScala
          .filter(f => f.toString.endsWith(".scala") || f.toString.endsWith(".java")).toList.sorted
        val groups = files.groupBy(_.getFileName.toString match
          case GroupSuffix(n, _) => n.nn.toInt
          case _ => 0
        ).toList.sortBy(_._1).map((_, fs) => Group(fs, fs.flatMap(usingOptions).distinct))
        if files.isEmpty then Nil else List(Test(category, name, root, groups))
      else if name.endsWith(".scala") then List(Test(category, name, root, List(Group(List(p), usingOptions(p)))))
      else Nil

  private def usingOptions(f: Path): List[String] =
    if !f.toString.endsWith(".scala") then Nil
    else
      try
        Files.readAllLines(f).asScala.iterator.map(_.trim).takeWhile(l => l.isEmpty || l.startsWith("//"))
          .filter(_.startsWith("//> using options"))
          .flatMap(_.stripPrefix("//> using options").trim.split("\\s+").filter(_.nonEmpty)).toList
      catch case NonFatal(_) => Nil

  // --- one test ------------------------------------------------------------------------------

  def crossCheck(t: Test, conf: Conf, fusedOpts: List[String], unfusedOpts: List[String]): Outcome =
    val base = conf.out.resolve("work").resolve(t.category).resolve(t.name)
    val (fusedDir, unfusedDir) = (base.resolve("fused"), base.resolve("unfused"))
    deleteRecursively(base)
    var keep = false
    try
      compileAll(t, fusedDir, fusedOpts) match
        case Some(err) => Outcome(if err.startsWith("crash") then "fused-crash" else "fused-error", err)
        case None =>
          compileAll(t, unfusedDir, unfusedOpts, unfused = !conf.self) match
            case Some(err) if unfusedLimits.exists(err.contains) => Outcome("unfused-limit", err)
            case Some(err) => Outcome(if err.startsWith("crash") then "UNFUSED-CRASH" else "UNFUSED-ERROR", err)
            case None =>
              val diffs = compareTrees(fusedDir, unfusedDir)
              if diffs.isEmpty then Outcome("same", "")
              else
                keep = conf.keep
                val ran = if conf.run && t.category.startsWith("run") then runBoth(fusedDir, unfusedDir) + "; " else ""
                Outcome("DIFF", ran + diffs.mkString(","))
    finally if !keep then deleteRecursively(base)

  private final class ErrorCollector extends Reporter:
    val errors = collection.mutable.ListBuffer[String]()
    def doReport(dia: Diagnostic)(using Context): Unit =
      if dia.level == ERROR && errors.length < 3 then
        val pos = dia.position.map(p => s"${p.source.name}:${p.line + 1}").orElse("")
        errors += s"$pos ${dia.msg.message.linesIterator.nextOption().getOrElse("")}"

  /** Compiles the groups in order, each with the earlier ones on the classpath. */
  private def compileAll(t: Test, out: Path, extra: List[String], unfused: Boolean = false): Option[String] =
    var classpath = libraryClasspath
    var failure: Option[String] = None
    for (g, i) <- t.groups.zipWithIndex if failure.isEmpty do
      val dir = if t.groups.length == 1 then out else out.resolve(s"g$i")
      Files.createDirectories(dir)
      val args = baseOptions ++ g.options ++ extra ++ List(
        "-classpath", classpath, "-d", dir.toString,
        // SemanticDB paths are relative to -sourceroot; keep them inside the output directory
        "-sourceroot", t.root.toString, "-semanticdb-target", dir.resolve("semanticdb").toString
      ) ++ g.files.map(_.toString)
      val reporter = ErrorCollector()
      try
        (if unfused then plugins.UnfusedPhases.driver else Main).process(args.toArray, reporter)
        if reporter.hasErrors then failure = Some("errors: " + reporter.errors.mkString(" | "))
      catch case e: (Exception | AssertionError | StackOverflowError) =>
        failure = Some(s"crash: ${e.toString.linesIterator.next().take(500)}")
      classpath = classpath + java.io.File.pathSeparator + dir
    failure

  private def compareTrees(a: Path, b: Path): List[String] =
    def files(root: Path) =
      if !Files.exists(root) then Set.empty[Path]
      else Files.walk(root).iterator.asScala.filter(Files.isRegularFile(_)).map(root.relativize).toSet
    val (fa, fb) = (files(a), files(b))
    (fa -- fb).toList.sorted.map(p => s"only-fused:$p")
      ++ (fb -- fa).toList.sorted.map(p => s"only-unfused:$p")
      ++ (fa & fb).toList.sorted.filter(p => Files.mismatch(a.resolve(p), b.resolve(p)) != -1L).map(_.toString)

  private def runBoth(a: Path, b: Path): String =
    val javaCmd = Paths.get(sys.props("java.home"), "bin", "java").toString
    def run(root: Path): (Int, String) =
      val groups = Files.list(root).iterator.asScala.filter(p => p.getFileName.toString.matches("g\\d+")).toList.sorted
      val cp = ((if groups.isEmpty then List(root) else groups).map(_.toString) :+ libraryClasspath)
        .mkString(java.io.File.pathSeparator)
      val p = ProcessBuilder(javaCmd, "-cp", cp, "Test").redirectErrorStream(true).start()
      val out = String(p.getInputStream.readAllBytes())
      if p.waitFor(60, TimeUnit.SECONDS) then (p.exitValue, out) else { p.destroyForcibly(); (-1, "timeout") }
    val (ra, rb) = (run(a), run(b))
    if ra == rb then "run-same"
    else s"RUN-DIFF (exit ${ra._1}/${rb._1}; fused: ${ra._2.take(200)} | unfused: ${rb._2.take(200)})"

  private def deleteRecursively(p: Path): Unit =
    if Files.exists(p) then Files.walk(p).iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)

  private def parse(args: List[String], c: Conf): Conf = args match
    case Nil => c
    case "--out" :: v :: rest => parse(rest, c.copy(out = repoRoot.resolve(v)))
    case "--jobs" :: v :: rest => parse(rest, c.copy(jobs = v.toInt))
    case "--filter" :: v :: rest => parse(rest, c.copy(filter = Some(v)))
    case "--timeout" :: v :: rest => parse(rest, c.copy(timeoutSec = v.toInt))
    case "--ycheck" :: rest => parse(rest, c.copy(ycheck = true))
    case "--run" :: rest => parse(rest, c.copy(run = true))
    case "--keep" :: rest => parse(rest, c.copy(keep = true))
    case "--self" :: rest => parse(rest, c.copy(self = true))
    case dir :: rest => parse(rest, c.copy(categories = c.categories :+ repoRoot.resolve(dir)))
end FusionCrossCheck
