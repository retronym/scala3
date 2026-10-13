package dotty.tools.benchlab

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.config.Feature
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.picklerPhase
import dotty.tools.dotc.core.Symbols.ClassSymbol
import dotty.tools.dotc.core.tasty.{Attributes, ScratchData, TastyPickler, TreePickler}

/** Parsing only: one op parses every source of the corpus, in a fresh run. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms4G", "-Xmx4G", "-Xss2M"))
@Warmup(iterations = 20, time = 2)
@Measurement(iterations = 10, time = 2)
class ParserBenchmark:
  @Param(Array("dottyUtil", "re2s", "scalaz"))
  var corpus: String = compiletime.uninitialized

  private var compiler: InMemoryCompiler = compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    Announce.jvm(s"ParserBenchmark($corpus)")
    compiler = InMemoryCompiler(Corpus.load(corpus), List("-Ystop-after:parser"))

  @Benchmark
  def parse(): Unit = compiler.compile()

/** TASTy pickling: the corpus is compiled up to the pickler once; one op pickles (and compactifies) every top-level
 *  class again, as the `Pickler` phase does, on the same trees.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms4G", "-Xmx4G", "-Xss2M"))
@Warmup(iterations = 20, time = 2)
@Measurement(iterations = 10, time = 2)
class PicklerBenchmark:
  @Param(Array("dottyUtil", "re2s", "scalaz"))
  var corpus: String = compiletime.uninitialized

  private final class Item(val unit: CompilationUnit, val cls: ClassSymbol, val tree: tpd.Tree, val ctx: Context)

  private var items: Array[Item] = compiletime.uninitialized
  private val scratch = new ScratchData

  @Setup(Level.Trial)
  def setup(): Unit =
    Announce.jvm(s"PicklerBenchmark($corpus)")
    val run = InMemoryCompiler(Corpus.load(corpus), List("-Ystop-after:pickler")).compileRun()
    val runCtx = run.runContext
    items = (for
      unit <- run.units
      unitCtx = runCtx.fresh.setCompilationUnit(unit).setPhase(picklerPhase(using runCtx))
      cls <- tpd.topLevelClasses(unit.tpdTree)(using unitCtx)
      tree <- tpd.sliceTopLevel(unit.tpdTree, cls)(using unitCtx)
    yield Item(unit, cls, tree, unitCtx)).toArray
    println(s"\n[bench-lab] $corpus: ${run.units.size} units, ${items.length} top-level classes")

  @Benchmark
  def pickleAndCompactify(): Int =
    var total = 0
    var i = 0
    while i < items.length do
      val it = items(i)
      given Context = it.ctx
      val attributes = Attributes(
        sourceFile = it.unit.source.path, scala2StandardLibrary = false, explicitNulls = false,
        captureChecked = Feature.ccEnabled, withPureFuns = Feature.pureFunsEnabled, isJava = false, isOutline = false)
      val treePkl = new TreePickler(new TastyPickler(it.cls, isBestEffortTasty = false), attributes)
      treePkl.pickle(it.tree :: Nil)
      scratch.reset()
      treePkl.compactify(scratch)
      total += treePkl.buf.length
      i += 1
    total
