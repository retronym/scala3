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
 *  typer state on top of the recorded context and with warnings discarded.
 *
 *  Per-match state of the space engine (spaces, their simplify and decompose memos, the isSubspace cache) is rebuilt per
 *  op as in the compiler; caches outside it (denotations, base types, member lookups) are warm after the first op,
 *  where the compiler meets them partly cold.
 *
 *  Corpora: those of `Corpus.load`, or synthetic sources to expose how the cost grows with the size of a match:
 *  `sealed<N>` (a sealed trait with N case classes of two fields, matched with N constructor patterns and with N type
 *  patterns) and `ints<N>` (an Int matched against N literals).
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
      if SpaceEngine.checkMatch(r.tree)(using r.ctx.fresh.setNewTyperState().setReporter(Reporter.NoReporter)) then
        exhaustive += 1
      i += 1
    exhaustive

object PatmatBenchmark:
  final class Recorded(val tree: tpd.Match, val ctx: Context, val pos: String)

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

  val SyntheticMethods = Set("ctor", "tpe", "ints")
  def isSynthetic(name: String): Boolean = name.startsWith("sealed") || name.startsWith("ints")

  def corpus(name: String): Corpus =
    def n(prefix: String) = name.stripPrefix(prefix).toInt
    if name.startsWith("sealed") then Corpus.inline(name, "Sealed.scala" -> sealedSource(n("sealed")))
    else if name.startsWith("ints") then Corpus.inline(name, "Ints.scala" -> intsSource(n("ints")))
    else Corpus.load(name)

  def sealedSource(n: Int): String =
    val classes = (0 until n).map(i => s"final case class C$i(a: Int, b: T) extends T").mkString("\n")
    val ctorCases = (0 until n).map(i => s"    case C$i(_, _) => $i").mkString("\n")
    val typeCases = (0 until n).map(i => s"    case _: C$i => $i").mkString("\n")
    s"""package synthetic
       |sealed trait T
       |$classes
       |object M:
       |  def ctor(t: T): Int = t match
       |$ctorCases
       |  def tpe(t: T): Int = t match
       |$typeCases
       |""".stripMargin

  def intsSource(n: Int): String =
    val cases = (0 until n).map(i => s"    case $i => ${i + 1}").mkString("\n")
    s"""package synthetic
       |object M:
       |  def ints(x: Int): Int = x match
       |$cases
       |    case _ => 0
       |""".stripMargin
