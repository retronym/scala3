package dotty.tools.benchlab

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

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

  private var compiler: InMemoryCompiler = compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    Announce.jvm(s"CompileBenchmark($corpus)")
    compiler = InMemoryCompiler(Corpus.load(corpus), scalacOptions.split(" ").toList.filter(_.nonEmpty), shared = context == "shared")
    compiler.compile()
    println(s"\n[bench-lab] $corpus: ${compiler.corpus.files.size} sources -> ${compiler.outputCount} output files; classpath in memory: ${InMemoryClasspath.default}")

  @Benchmark
  def compile(): Unit = compiler.compile()

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
    println(s"classpath in memory: ${InMemoryClasspath.default}")
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
