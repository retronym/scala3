package dotty.tools.benchlab

import io.github.retronym.perflab.jmh.Announce
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.FlagSet
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Phases.typerPhase
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.Type

/** Denotation transformation (`SingleDenotation.current`, `goForward`) in isolation. The corpus is compiled once; its
 *  classes' term members serve as templates. One op mints `symbols` fresh symbols, each a copy of a template (same
 *  owner, name, flags and typer-phase info, so the info transformers do their real work on the corpus's own types) at
 *  typer, and asks for its denotation at the last phase before the terminal one: the full chain of denotation
 *  transformers, every one of them, for every symbol.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms4G", "-Xmx4G", "-Xss2M"))
@Warmup(iterations = 10, time = 2)
@Measurement(iterations = 10, time = 2)
class GoForwardBenchmark:
  @Param(Array("dottyUtil", "scalaz"))
  var corpus: String = compiletime.uninitialized

  @Param(Array("2000"))
  var symbols: Int = compiletime.uninitialized

  private final class Template(val owner: Symbol, val name: Name, val flags: FlagSet, val info: Type)

  private var templates: Array[Template] = compiletime.uninitialized
  private var typerCtx: Context = compiletime.uninitialized
  private var lastCtx: Context = compiletime.uninitialized

  // flags copied from the template: what shapes the info transforms, nothing that ties a symbol to a tree or a
  // phase-specific role (constructors, parameters, modules, inline methods are skipped altogether)
  private val Copied: FlagSet =
    Flags.Method | Flags.Private | Flags.Protected | Flags.Final | Flags.Implicit | Flags.Given | Flags.Deferred |
    Flags.Override | Flags.Lazy | Flags.Mutable | Flags.Synthetic | Flags.StableRealizable | Flags.Accessor

  @Setup(Level.Trial)
  def setup(): Unit =
    Announce.jvm(s"GoForwardBenchmark($corpus)")
    val run = InMemoryCompiler(Corpus.load(corpus), Nil).compileRun()
    given runCtx: Context = run.runContext
    typerCtx = runCtx.fresh.setPhase(typerPhase)
    lastCtx = runCtx.fresh.setPhase(runCtx.lastPhaseId - 1) // the last phase before the terminal one
    val classes = for
      unit <- run.units
      cls <- tpd.topLevelClasses(unit.tpdTree)(using typerCtx)
    yield cls
    templates = (for
      cls <- classes
      decl <- cls.info.decls.toList(using typerCtx)
      if decl.isTerm && !decl.isClass && decl.name != nme.CONSTRUCTOR
      d = decl.denot.initial
      if !d.isOneOf(Flags.Module | Flags.Param | Flags.ParamAccessor | Flags.Inline | Flags.Package)(using typerCtx)
    yield Template(cls, decl.name, d.flags(using typerCtx) & Copied, d.info(using typerCtx))).toArray
    println(s"\n[bench-lab] $corpus: ${classes.size} top-level classes, ${templates.length} member templates")

  @Benchmark
  def mintAndForward(): Int =
    given Context = typerCtx
    var check = 0
    var i = 0
    while i < symbols do
      val t = templates(i % templates.length)
      val sym = newSymbol(t.owner, t.name, t.flags, t.info)
      check += sym.denot(using lastCtx).validFor.lastPhaseId
      i += 1
    check
