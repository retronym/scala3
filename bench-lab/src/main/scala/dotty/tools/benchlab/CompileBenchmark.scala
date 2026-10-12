package dotty.tools.benchlab

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

/** One op is one full compilation of a corpus, in a warm compiler, with no file I/O. */
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

  private var compiler: InMemoryCompiler = compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    compiler = InMemoryCompiler(Corpus.load(corpus), scalacOptions.split(" ").toList.filter(_.nonEmpty))
    compiler.compile()
    println(s"\n[bench-lab] $corpus: ${compiler.corpus.files.size} sources -> ${compiler.outputCount} output files")

  @Benchmark
  def compile(): Unit = compiler.compile()

/** Compiles a corpus repeatedly outside JMH, printing each run's time, e.g. to attach a profiler or Instruments. */
object CompileLoop:
  def main(args: Array[String]): Unit =
    val corpus = args.headOption.getOrElse("dottyUtil")
    val n = args.lift(1).fold(20)(_.toInt)
    val compiler = InMemoryCompiler(Corpus.load(corpus), args.drop(2).toList)
    for i <- 1 to n do
      val t0 = System.nanoTime
      compiler.compile()
      println(f"$corpus run $i%3d: ${(System.nanoTime - t0) / 1e6}%8.1f ms")
    println(s"${compiler.outputCount} output files")
