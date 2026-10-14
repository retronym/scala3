package dotty.tools.benchlab

import java.util.concurrent.TimeUnit

import scala.collection.mutable

import org.openjdk.jmh.annotations.*

import dotty.tools.dotc.Compiler
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.dotc.transform.PatternMatcher
import dotty.tools.dotc.transform.patmat.SpaceEngine

/** Exhaustivity and reachability checking (`SpaceEngine.checkMatch`) in isolation.
 *
 *  Setup compiles the corpus up to the pattern matcher with that phase replaced by one that records each match it is
 *  about to check, as `PatternMatcher.transformMatch` sees it (cases already transformed, nested matches translated)
 *  and the context it runs in. One op checks every recorded match again (or those `matches` selects), each in a fresh
 *  typer state and GADT state on top of the recorded context and with warnings discarded (see `checkContext`).
 *
 *  The run's root typer state is empty by the time the ops run (the pattern matcher's phase has ended), so type
 *  variables that checks leave in it during a compilation (scalaz, see retronym/scala3#22) are not reproduced: the
 *  benchmark measures the checks as they are with that leak fixed.
 *
 *  Per-match state of the space engine (spaces, their simplify and decompose memos, the isSubspace cache) is rebuilt per
 *  op as in the compiler; caches outside it (denotations, base types, member lookups) are warm after the first op,
 *  where the compiler meets them partly cold.
 *
 *  Corpora: those of `Corpus.load`, or synthetic sources to expose how the cost grows with the size of a match:
 *  `sealed<N>` (a sealed trait T with N case classes of two fields, matched with N constructor patterns, with N type
 *  patterns, as a pair `(T, T)` with N diagonal cases and a wildcard, and as an `Option[T]` with N `Some` cases) and `ints<N>` (an Int matched against N literals).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms4G", "-Xmx4G", "-Xss2M"))
@Warmup(iterations = 10, time = 2)
@Measurement(iterations = 10, time = 2)
class PatmatBenchmark:
  @Param(Array("tastyQuery", "scalaz"))
  var corpus: String = compiletime.uninitialized

  /** `all`, or a comma-separated list of `<file>:<line>` (the line of the `match`, 1-based) suffixes. For the synthetic
   *  corpora, `all` means the generated matches, not those in the case classes' synthetic methods.
   */
  @Param(Array("all"))
  var matches: String = compiletime.uninitialized

  private var recorded: Array[PatmatBenchmark.Recorded] = compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    Announce.jvm(s"PatmatBenchmark($corpus, $matches)")
    val all = PatmatBenchmark.record(PatmatBenchmark.corpus(corpus))
    recorded =
      if matches == "all" && PatmatBenchmark.isSynthetic(corpus) then
        all.filter { r => given Context = r.ctx; PatmatBenchmark.SyntheticMethods(ctx.owner.enclosingMethod.name.toString) }
      else if matches == "all" then all
      else
        val wanted = matches.split(",").toList.map(_.trim).filter(_.nonEmpty)
        val sel = all.filter(r => wanted.exists(r.pos.endsWith))
        if sel.isEmpty then sys.error(s"no match at $matches; e.g. ${all.take(5).map(_.pos).mkString(", ")}")
        sel
    val cases = recorded.iterator.map(_.tree.cases.size).sum
    println(s"\n[bench-lab] $corpus: ${all.length} matches recorded, ${recorded.length} selected ($cases cases)")

  @Benchmark
  def checkMatch(): Int =
    var exhaustive = 0
    var i = 0
    while i < recorded.length do
      val r = recorded(i)
      if SpaceEngine.checkMatch(r.tree)(using PatmatBenchmark.checkContext(r)) then
        exhaustive += 1
      i += 1
    exhaustive

object PatmatBenchmark:
  final class Recorded(val tree: tpd.Match, val ctx: Context, val pos: String)

  /** The context of one check: the recorded one with a fresh typer state and GADT state, so that nothing a check adds
   *  to them carries over to later ops (the recorded contexts share the run's root states: a check that leaves a GADT
   *  symbol there, as one in scalaz's Tree.scala does, would otherwise slow every later op), and warnings discarded.
   */
  def checkContext(r: Recorded): Context =
    r.ctx.fresh.setNewTyperState().setFreshGADTBounds.setReporter(Reporter.NoReporter)

  /** The pattern matcher, recording each match it checks before checking it. */
  final class RecordingPatternMatcher(sink: mutable.ArrayBuffer[Recorded]) extends PatternMatcher:
    override def transformMatch(tree: tpd.Match)(using Context): tpd.Tree =
      if !tree.isInstanceOf[tpd.InlineMatch] && !tree.isSubMatch then
        // a copy: the megaphase's context may be reused once this call returns
        sink += Recorded(tree, ctx.fresh, s"${ctx.source.file.nn.name}:${tree.srcPos.line + 1}")
      super.transformMatch(tree)

  def record(corpus: Corpus): Array[Recorded] =
    val sink = mutable.ArrayBuffer[Recorded]()
    val compiler = new InMemoryCompiler(corpus, List("-Ystop-after:patternMatcher")):
      override protected def newCompiler(using Context): Compiler = new Compiler:
        override def phases: List[List[Phase]] = super.phases.map(_.map {
          case _: PatternMatcher => RecordingPatternMatcher(sink)
          case p => p
        })
    compiler.compileRun()
    sink.toArray

  val SyntheticMethods = Set("ctor", "tpe", "ints", "tup", "opt")
  def isSynthetic(name: String): Boolean = name.matches("(sealed|ints)[0-9]+")

  def corpus(name: String): Corpus =
    def n(prefix: String) = name.stripPrefix(prefix).toInt
    if name.startsWith("sealed") then Corpus.inline(name, "Sealed.scala" -> sealedSource(n("sealed")))
    else if name.startsWith("ints") then Corpus.inline(name, "Ints.scala" -> intsSource(n("ints")))
    else Corpus.load(name)

  def sealedSource(n: Int): String =
    val classes = (0 until n).map(i => s"final case class C$i(a: Int, b: T) extends T").mkString("\n")
    val ctorCases = (0 until n).map(i => s"    case C$i(_, _) => $i").mkString("\n")
    val typeCases = (0 until n).map(i => s"    case _: C$i => $i").mkString("\n")
    val tupleCases = (0 until n).map(i => s"    case (C$i(_, _), C$i(_, _)) => $i").mkString("\n")
    val optionCases = (0 until n).map(i => s"    case Some(C$i(_, _)) => $i").mkString("\n")
    s"""package synthetic
       |sealed trait T
       |$classes
       |object M:
       |  def ctor(t: T): Int = t match
       |$ctorCases
       |  def tpe(t: T): Int = t match
       |$typeCases
       |  def tup(a: T, b: T): Int = (a, b) match
       |$tupleCases
       |    case _ => -1
       |  def opt(o: Option[T]): Int = o match
       |$optionCases
       |    case None => -1
       |""".stripMargin

  def intsSource(n: Int): String =
    val cases = (0 until n).map(i => s"    case $i => ${i + 1}").mkString("\n")
    s"""package synthetic
       |object M:
       |  def ints(x: Int): Int = x match
       |$cases
       |    case _ => 0
       |""".stripMargin

/** `PatmatLoop [corpus] [passes]`: checks every recorded match of the corpus in a loop outside JMH, printing each
 *  pass's time and the size of state that could carry over between passes, to find what makes passes slower.
 */
object PatmatLoop:
  def main(args: Array[String]): Unit =
    // <corpus>[:<method>]: for the synthetic corpora, only the match in that method (ctor, tpe, tup, opt, ints)
    val (corpus, method) = args.headOption.getOrElse("scalaz").split(":") match
      case Array(c, m) => (c, Some(m))
      case Array(c) => (c, None)
      case _ => sys.error("<corpus>[:<method>]")
    val passes = args.lift(1).fold(10)(_.toInt)
    val all = PatmatBenchmark.record(PatmatBenchmark.corpus(corpus))
    val recorded =
      if PatmatBenchmark.isSynthetic(corpus) then
        all.filter { r =>
          given Context = r.ctx
          val m = ctx.owner.enclosingMethod.name.toString
          method.fold(PatmatBenchmark.SyntheticMethods(m))(_ == m)
        }
      else all
    val states = recorded.map(_.ctx.typerState).distinct
    val gadts = recorded.map(_.ctx.gadtState).distinct
    println(s"$corpus: ${recorded.length} matches, ${states.length} typer states, ${gadts.length} GADT states")
    def sizes(): String =
      val binders = states.iterator.map(_.constraint.domainLambdas.size).sum
      val owned = states.iterator.map(_.ownedVars.size).sum
      val gadtSyms = gadts.iterator.map(_.gadt.symbols.size).sum
      s"binders $binders, owned vars $owned, GADT symbols $gadtSyms"
    println(s"before: ${sizes()}")
    for pass <- 1 to passes do
      val t0 = System.nanoTime
      for r <- recorded do
        val before = r.ctx.gadtState.gadt.symbols
        SpaceEngine.checkMatch(r.tree)(using PatmatBenchmark.checkContext(r))
        val added = r.ctx.gadtState.gadt.symbols.filterNot(before.contains)
        if pass == 1 && added.nonEmpty then println(s"  ${r.pos}: GADT symbols added to the recorded context's state: ${added.map(_.showFullName(using r.ctx)).mkString(", ")}")
      println(f"pass $pass%3d: ${(System.nanoTime - t0) / 1e6}%8.1f ms; ${sizes()}")
