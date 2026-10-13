package dotty.tools.benchlab

import dotty.tools.dotc.{Compiler, Driver, Run}
import dotty.tools.dotc.classpath.ClassPath
import dotty.tools.dotc.config.{JavaPlatform, Platform}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.io.AbstractFile

/** Compiles one corpus, again and again, with no file I/O after construction.
 *
 *  Sources are `VirtualFile`s read up front, the classpath is an `InMemoryClasspath` snapshot, and output goes to an
 *  in-memory directory: class files and TASTy are generated and serialized, just not written.
 *
 *  By default each compilation starts from scratch, as a fresh `ContextBase`, platform and `Compiler`. It pays for
 *  everything a cold compile does (definitions, classpath indexing, symbol loading) except reading files and JIT warmup,
 *  and nothing survives from one compilation to the next but JVM-wide state (the name table). With `shared = true`, all
 *  compilations share one root context and `Compiler`, each a new `Run`, as a warm compile server would; dotc then
 *  retains every earlier `Run` through state on the root context and in phases, so the heap grows with each compilation.
 */
final class InMemoryCompiler(val corpus: Corpus, extraOptions: List[String] = Nil, shared: Boolean = false,
    classpath: InMemoryClasspath = InMemoryClasspath.default) extends Driver:

  /** The output of the latest compilation. */
  var output: AbstractFile = dotty.tools.io.virtualDirectory("<bench-lab output>")

  override protected def sourcesRequired: Boolean = false

  // The platform's classpath is the snapshot, instead of what PathResolver finds from the settings (jars and the jrt
  // file system, both cached process-wide).
  override protected def initCtx: Context =
    val base = new ContextBase:
      override protected def newPlatform(using Context): Platform = new JavaPlatform:
        private val cp = classpath.classPath
        override def classPath(using Context): ClassPath = cp
    base.initialCtx

  private def newRootContext(out: AbstractFile): Context =
    val args = (corpus.options ::: extraOptions).toArray
    val (_, ctx) = setup(args, initCtx).getOrElse(sys.error(s"bad options: ${args.mkString(" ")}"))
    ctx.fresh.setSetting(ctx.settings.outputDir, out)

  private lazy val sharedRoot: (Context, Compiler) =
    val ctx = newRootContext(output)
    (ctx, newCompiler(using ctx))

  /** A root context and compiler for the next compilation, and where it writes. */
  private def prepare(): (Context, Compiler) =
    if shared then sharedRoot
    else
      output = dotty.tools.io.virtualDirectory("<bench-lab output>")
      val ctx = newRootContext(output)
      (ctx, newCompiler(using ctx))

  /** One full compilation of the corpus. */
  def compile(): Unit =
    val (root, compiler) = prepare()
    val store = StoreReporter(null)
    val reporter =
      try doCompile(compiler, corpus.files)(using root.fresh.setReporter(store))
      catch case e: Exception =>
        throw new IllegalStateException(s"${corpus.name}: compiler crashed after:\n${store.removeBufferedMessages(using root).take(10).mkString("\n")}", e)
    if reporter.hasErrors then
      throw new IllegalStateException(s"${corpus.name} failed to compile:\n${reporter.allErrors.take(10).mkString("\n")}")

  /** Compiles the corpus in a new run and returns it, to keep its units and context: the starting point for benchmarks
   *  of one phase, with the compiler stopped after the phase before (`-Ystop-after`).
   */
  def compileRun(): Run =
    val (root, compiler) = prepare()
    given Context = root.fresh.setReporter(StoreReporter(null))
    val run = compiler.newRun
    run.compile(corpus.files)
    if ctx.reporter.hasErrors then
      throw new IllegalStateException(s"${corpus.name} failed to compile:\n${ctx.reporter.allErrors.take(10).mkString("\n")}")
    run

  /** Number of files in the latest output, to check the backend ran. */
  def outputCount: Int =
    def count(f: AbstractFile): Int = if f.isDirectory then f.iterator.map(count).sum else 1
    count(output)
