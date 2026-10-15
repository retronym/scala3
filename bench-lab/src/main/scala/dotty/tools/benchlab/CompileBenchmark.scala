package dotty.tools.benchlab

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType

/** One op is one full compilation of a corpus, from scratch in a warm JVM, with no file I/O (see `InMemoryCompiler`). */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms4G", "-Xmx4G", "-Xss2M"))
@Warmup(iterations = 20, time = 3)
@Measurement(iterations = 10, time = 3)
class CompileBenchmark:
  @Param(Array("dottyUtil", "re2s", "scalaz"))
  var corpus: String = compiletime.uninitialized

  /** Extra compiler options, space separated, e.g. `-Ystop-after:typer`. */
  @Param(Array(""))
  var scalacOptions: String = compiletime.uninitialized

  /** `fresh`: a new compiler per op; `shared`: one root context and `Compiler` for all ops, as a compile server (leaks). */
  @Param(Array("fresh"))
  var context: String = compiletime.uninitialized

  /** Variants compared within the fork, `<name>=<scalacOptions>|<name>=<scalacOptions>...`, the first the baseline
   *  (see `doc/paired-ab.md`). Each iteration, warmup included, runs the next variant in turn, the order changing
   *  from cycle to cycle (`CompileBenchmark.order`), so the variants share
   *  the fork's compiled code; `PairedProfiler` and `XctraceProfiler` report per variant. The options add to
   *  `scalacOptions`, and must be settings the compiler reads per run. JMH splits parameter values at commas: none here.
   */
  @Param(Array(""))
  var variants: String = compiletime.uninitialized

  /** With `variants`: a full collection before each iteration (outside the measured region), so each starts from a
   *  similar heap and fewer collections land in one variant's iterations by where it sits in a cycle. */
  @Param(Array("false"))
  var gcBetweenIterations: Boolean = false

  private var compiler: InMemoryCompiler = compiletime.uninitialized
  private var alternatives: Array[(String, InMemoryCompiler)] = Array()
  private var iteration = 0

  @Setup(Level.Trial)
  def setup(): Unit =
    Announce.jvm(s"CompileBenchmark($corpus)")
    val common = scalacOptions.split(" ").toList.filter(_.nonEmpty)
    val src = Corpus.load(corpus)
    compiler = InMemoryCompiler(src, common, shared = context == "shared")
    compiler.compile()
    println(s"\n[bench-lab] $corpus: ${compiler.corpus.files.size} sources -> ${compiler.outputCount} output files; classpath in memory: ${compiler.classpathSnapshot}")
    val parsed = variants.split('|').toList.map(_.trim).filter(_.nonEmpty).map { v =>
      val i = v.indexOf('=')
      if i <= 0 then sys.error(s"variant without a name: '$v' (expected <name>=<scalacOptions>)")
      v.take(i).trim -> v.drop(i + 1).split(" ").toList.filter(_.nonEmpty)
    }
    alternatives = parsed.map { (name, opts) =>
      val c = InMemoryCompiler(src, common ::: opts, shared = context == "shared", classpath = compiler.classpathSnapshot)
      c.compile()
      println(s"[bench-lab] variant $name: ${opts.mkString(" ")} -> ${c.outputCount} output files")
      name -> c
    }.toArray
    IterationLog.reset(java.util.List.of(alternatives.map(_._1)*))
    iteration = 0

  @Setup(Level.Iteration)
  def nextVariant(ip: IterationParams): Unit =
    if alternatives.nonEmpty then
      val n = alternatives.length
      val (name, c) = alternatives(CompileBenchmark.order(n, iteration / n)(iteration % n))
      if gcBetweenIterations then System.gc()
      compiler = c
      iteration += 1
      IterationLog.begin(name, ip.getType == IterationType.MEASUREMENT)

  @TearDown(Level.Iteration)
  def endVariant(): Unit = if alternatives.nonEmpty then IterationLog.end()

  @Benchmark
  def compile(): Unit = compiler.compile()

object CompileBenchmark:
  /** The order of `n` variants in cycle `k`: the rows of a Williams design, so over a whole number of its rows (`n`
   *  for even `n`, `2n` for odd) each variant runs in each position equally often, and right after each other variant
   *  equally often. A fixed order put one variant always after the same one and always at the same point in the heap's
   *  fill, where collections land (a 4-variant run measured 2% for a variant whose instructions fell 2.4%); A B, B A
   *  for two variants.
   */
  def order(n: Int, k: Int): IndexedSeq[Int] =
    // first row 0, 1, n-1, 2, n-2, ...; row r adds r; for odd n, the reversed rows too
    val first = (0 until n).map(i => if i == 0 then 0 else if i % 2 == 1 then (i + 1) / 2 else n - i / 2)
    val rows = if n % 2 == 0 then n else 2 * n
    val r = k % rows
    val row = first.map(x => (x + r % n) % n)
    if r >= n then row.reverse else row

/** Compiles a corpus repeatedly outside JMH, printing each run's time, e.g. to attach a profiler or Instruments. */
object CompileLoop:
  /** `CompileLoop [corpus] [runs] [scalac options...]`: compiles the corpus in a loop, printing each run's time and the
   *  live heap after it (after a full GC); `-Dbenchlab.shared` shares one compiler across runs. `-Dbenchlab.histoAt=2,10` also writes a class histogram of the live heap after
   *  those runs to `$TMPDIR/benchlab-histo-<corpus>-<run>.txt`, to find what survives from one run to the next.
   */
  def main(args: Array[String]): Unit =
    val corpus = args.headOption.getOrElse("dottyUtil")
    val n = args.lift(1).fold(20)(_.toInt)
    val histoAt = sys.props.get("benchlab.histoAt").toList.flatMap(_.split(",")).map(_.trim.toInt).toSet
    val compiler = InMemoryCompiler(Corpus.load(corpus), args.drop(2).toList, shared = sys.props.contains("benchlab.shared"))
    println(s"classpath in memory: ${compiler.classpathSnapshot}")
    val memory = java.lang.management.ManagementFactory.getMemoryMXBean
    for i <- 1 to n do
      val t0 = System.nanoTime
      compiler.compile()
      val ms = (System.nanoTime - t0) / 1e6
      System.gc()
      val live = memory.getHeapMemoryUsage.getUsed / 1e6
      println(f"$corpus run $i%3d: $ms%8.1f ms, live heap $live%7.0f MB")
      if histoAt(i) then
        val out = java.nio.file.Path.of(sys.props("java.io.tmpdir"), s"benchlab-histo-$corpus-$i.txt")
        java.nio.file.Files.writeString(out, histogram())
        println(s"  histogram: $out")
    println(s"${compiler.outputCount} output files")

  private def histogram(): String =
    val server = java.lang.management.ManagementFactory.getPlatformMBeanServer
    val name = javax.management.ObjectName("com.sun.management:type=DiagnosticCommand")
    server.invoke(name, "gcClassHistogram", Array[AnyRef](Array.empty[String]), Array("[Ljava.lang.String;")).asInstanceOf[String]
