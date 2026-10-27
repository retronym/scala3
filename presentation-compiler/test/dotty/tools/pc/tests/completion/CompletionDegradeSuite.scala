package dotty.tools.pc.tests.completion

import java.net.URI

import scala.collection.mutable
import scala.language.unsafeNulls
import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.metals.{CompilerOffsetParams, EmptyCancelToken, PcQueryContext}
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.reports.EmptyReportContext

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.{CachingDriver, ScalaPresentationCompiler}
import dotty.tools.pc.base.BaseCompletionSuite

import org.junit.Test

/** Graceful degradation in interactive mode: a cyclic reference, or a
 *  symbol left `Touched` by an earlier unwind, costs the request the one
 *  symbol, not the whole answer, and taints the run so that the next request
 *  on the same content starts fresh instead of resuming from degraded state.
 */
class CompletionDegradeSuite extends BaseCompletionSuite:

  private val uri = URI.create("file:///A.scala")

  override protected def config: PresentationCompilerConfigImpl =
    super.config.copy(timeoutDelay = 120)

  private def pc = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]
  private given PcQueryContext = PcQueryContext(None, () => "")(using EmptyReportContext())

  private def withDriver[T](f: CachingDriver => T): T =
    pc.compilerAccess.withNonInterruptableCompiler(null.asInstanceOf[T], EmptyCancelToken) { access =>
      f(access.compiler())
    }.get(60, java.util.concurrent.TimeUnit.SECONDS)

  private def complete(query: String): List[String] =
    val (code, offset) = params(query)
    presentationCompiler
      .complete(CompilerOffsetParams(uri, code, offset, EmptyCancelToken))
      .get(60, java.util.concurrent.TimeUnit.SECONDS)
      .getItems.asScala.map(_.getLabel).toList.sorted

  private val source =
    """|object Cyc {
       |  def pAlpha(x: Int): Int = x
       |  def pBeta = pGamma
       |  def pGamma = pBeta
       |  def pDelta(s: String): String = s
       |}
       |object Main {
       |  Cyc.p@@
       |}
       |""".stripMargin

  @Test def `a-true-cycle-costs-the-cyclic-members-only` =
    val items = complete(source)
    println(s"[degrade] true cycle: $items")
    val labels = items.map(_.takeWhile(c => c.isLetter))
    assert(labels.contains("pAlpha") && labels.contains("pDelta"), s"the other members must be listed: $items")
    val (runId, tainted, cycles) = withDriver(d => (d.runIdOf(uri), d.isTainted(uri), d.readerOfLastRequest.cyclesPrevented.size))
    println(s"[degrade] after the cycle: runId=$runId tainted=$tainted cyclesPrevented=$cycles")
    assert(cycles > 0, "the cycle is recorded on the run")
    assert(!tainted, "a prevented cyclic reference does not taint the run: its symbols are left recomputable")
    // The same content again: the same run serves it, with the same answer
    val items2 = complete(source)
    val runId2 = withDriver(_.runIdOf(uri))
    println(s"[degrade] the next request on the same content: runId=$runId2")
    assert(runId2 == runId, "the run is resumed")
    assert(items == items2, "the same answer")

  @Test def `a-leaked-touched-flag-costs-one-item` =
    val plain =
      """|object Leak {
         |  def pAlpha(x: Int): Int = x
         |  def pBeta(y: Int): Int = y
         |  def pGamma(z: Int): Int = z
         |}
         |object Main {
         |  Leak.p@@
         |}
         |""".stripMargin
    val all = complete(plain)
    println(s"[degrade] before the leak: $all")
    // Plant the leak: `beta` is marked as being completed, as an exception that
    // unwound through its completer would have left it before this change
    withDriver { d =>
      given Context = d.currentCtx
      val leak = defn.EmptyPackageClass.info.decls.lookup("Leak".toTermName).moduleClass
      val beta = leak.info.decls.lookup("pBeta".toTermName)
      dotty.tools.dotc.interactive.TestLeaks.leaveTouched(beta)
      println(s"[degrade] planted on run ${ctx.runId}: pBeta isCompleting=${beta.isCompleting}")
    }
    // The same content (and a non-empty prefix: for an empty one the provider
    // uses a fresh driver): served by the same run, which meets the leaked flag
    val degraded =
      try complete(plain)
      catch case scala.util.control.NonFatal(ex) => List(s"<request failed: ${ex.getClass.getSimpleName}>")
    println(s"[degrade] with pBeta Touched: $degraded")
    val labels = degraded.map(_.takeWhile(c => c.isLetter))
    assert(labels.contains("pAlpha") && labels.contains("pGamma"), s"the request must still list the other members: $degraded")
    // No completion of pBeta was in progress: the flag was a leak, healed on the spot, and pBeta
    // completed through the planted completer (which gives it `Int`) instead of being dropped
    assert(degraded.exists(_.startsWith("pBeta: Int")), s"the leaked flag is healed, not reported as a cycle: $degraded")
    val (tainted, healed) = withDriver(d => (d.isTainted(uri), d.readerOfLastRequest.leaksHealed.size))
    println(s"[degrade] tainted=$tainted leaksHealed=$healed")
    assert(healed > 0, "the healing is recorded on the run")
    assert(!tainted, "a healed leak does not taint the run")
