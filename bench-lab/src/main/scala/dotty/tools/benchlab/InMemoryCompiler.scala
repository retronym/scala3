package dotty.tools.benchlab

import dotty.tools.dotc.{Compiler, Driver, Run}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.io.{AbstractFile, VirtualDirectory}

/** A warm compile server for one corpus, with no file I/O after construction.
 *
 *  Sources are `VirtualFile`s read up front. The classpath is jars, which dotc caches across runs (the jar classpath
 *  cache in `ZipAndJarClassPathFactory`), so each is opened once per JVM. Output goes to a `VirtualDirectory`: class
 *  files and TASTy are generated and serialized, just not written. All runs share one root context and `Compiler`, and
 *  each `compile()` is a fresh `Run`, which resets the per-run caches on the `ContextBase`.
 */
final class InMemoryCompiler(val corpus: Corpus, extraOptions: List[String] = Nil) extends Driver:
  val output: VirtualDirectory = dotty.tools.io.virtualDirectory("<bench-lab output>").asInstanceOf[VirtualDirectory]

  override protected def sourcesRequired: Boolean = false

  private val rootCtx: Context =
    val scalaLibrary = sys.props.getOrElse("benchlab.scalaLibrary", sys.error("-Dbenchlab.scalaLibrary=<jar> not set"))
    val args = ("-classpath" :: scalaLibrary :: corpus.options ::: extraOptions).map(_.nn).toArray
    val (_, ctx) = setup(args, initCtx).getOrElse(sys.error(s"bad options: ${args.mkString(" ")}"))
    ctx.fresh.setSetting(ctx.settings.outputDir, output)

  private val compiler: Compiler = newCompiler(using rootCtx)

  /** One full compilation of the corpus. */
  def compile(): Unit =
    val reporter = doCompile(compiler, corpus.files)(using rootCtx.fresh.setReporter(StoreReporter(null)))
    if reporter.hasErrors then
      throw new IllegalStateException(s"${corpus.name} failed to compile:\n${reporter.allErrors.take(10).mkString("\n")}")

  /** Compiles the corpus in a new run and returns it, to keep its units and context: the starting point for benchmarks
   *  of one phase, with the compiler stopped after the phase before (`-Ystop-after`).
   */
  def compileRun(): Run =
    given Context = rootCtx.fresh.setReporter(StoreReporter(null))
    val run = compiler.newRun
    run.compile(corpus.files)
    if ctx.reporter.hasErrors then
      throw new IllegalStateException(s"${corpus.name} failed to compile:\n${ctx.reporter.allErrors.take(10).mkString("\n")}")
    run

  /** Number of files in the output, to check the backend ran. */
  def outputCount: Int =
    def count(f: AbstractFile): Int = if f.isDirectory then f.iterator.map(count).sum else 1
    count(output)
