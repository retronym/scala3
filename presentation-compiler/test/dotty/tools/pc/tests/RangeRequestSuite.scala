package dotty.tools.pc.tests

import java.net.URI

import scala.concurrent.duration.*
import scala.language.unsafeNulls
import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.metals.{CompilerInlayHintsParams, CompilerRangeParams, CompilerVirtualFileParams, EmptyCancelToken, PcQueryContext}
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.reports.EmptyReportContext

import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.{NoSpan, Span}
import dotty.tools.pc.{CachingDriver, ScalaPresentationCompiler}
import dotty.tools.pc.base.BasePCSuite

import org.junit.Test

/** Inlay hints and semantic tokens for a range (`textDocument/inlayHint`,
 *  `textDocument/semanticTokens/range`) type only the statements that touch
 *  the range (`InteractiveDriver.runUpTo` with a range target), at each level,
 *  and answer for the range what a whole-file run answers.
 */
class RangeRequestSuite extends BasePCSuite:

  private val timeout = 60.seconds

  override protected def config: PresentationCompilerConfigImpl =
    super.config.copy(timeoutDelay = 120)

  private val uri = URI.create("file:///Range.scala")

  /** 400 top-level objects with something to hint and to colour; object
   *  `giantAt` has a method of 200 local vals, typed explicitly so that the
   *  block's result `a0 + a199` does not type them all through their
   *  completers. `tag` comes last, so that
   *  contents with different tags have the same offsets.
   */
  private def bigSource(tag: String, giantAt: Int = 50) =
    val objects = (0 until 400).map { i =>
      val giant =
        if i != giantAt then ""
        else
          val stats = (0 until 200).map { j =>
            val prev = if j == 0 then "0" else s"a${j - 1}"
            s"    val a$j: Int = xs.map(_ + $j + $prev).filter(_ > $j).sum"
          }.mkString("\n")
          s"\n  def giant(xs: List[Int]): Int = {\n$stats\n    a0 + a199\n  }"
      s"""|object M$i {
          |  def method$i(xs: List[Int]) = xs.map(_ + $i).filter(_ > $i).sum$giant
          |  val r = method$i(List($i, 1))
          |}""".stripMargin
    }.mkString("\n")
    s"$objects\n// $tag\n"

  /** The range from the start of object `from` to the end of object `to`. */
  private def objectsRange(code: String, from: Int, to: Int): Span =
    Span(code.indexOf(s"object M$from {"), code.indexOf(s"object M${to + 1} {") - 1)

  private def pc = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]
  private given PcQueryContext = PcQueryContext(None, () => "")(using EmptyReportContext())

  private def withDriver[T](f: CachingDriver => T): T =
    pc.compilerAccess.withNonInterruptableCompiler(null.asInstanceOf[T], EmptyCancelToken) { access =>
      f(access.compiler())
    }.get(timeout.length, timeout.unit)

  /** (run id, typed top-level statements, skipped statements at any level, trees typed by the last request: a fresh run here) */
  private def state(): (Int, Int, Int, Int) =
    withDriver(d => (d.runIdOf(uri), d.typedTopLevelCount(uri), d.untypedSpans(uri).size, d.readerOfLastRequest.typedTrees))

  private def wholeFile(code: String): Int =
    withDriver { d =>
      d.run(uri, SourceFile.virtual(uri, code))
      d.readerOfLastRequest.typedTrees
    }

  private def inlayHints(code: String, range: Span): List[String] =
    val params = CompilerInlayHintsParams(
      rangeParams = CompilerRangeParams(uri, code, range.start, range.end),
      inferredTypes = true,
      typeParameters = true,
      implicitParameters = true,
      hintsXRayMode = true,
      byNameParameters = true,
      implicitConversions = true,
      namedParameters = true,
      hintsInPatternMatch = true,
      closingLabels = true
    )
    pc.inlayHints(params).get(timeout.length, timeout.unit).asScala.toList.map(_.toString)

  private def semanticTokens(code: String, range: Span): List[(Int, Int, Int, Int)] =
    val params = if range.exists then CompilerRangeParams(uri, code, range.start, range.end) else CompilerVirtualFileParams(uri, code)
    pc.semanticTokens(params).get(timeout.length, timeout.unit).asScala.toList
      .map(n => (n.start(), n.end(), n.tokenType(), n.tokenModifier()))

  @Test def `range-types-only-the-touching-statements` =
    val code = bigSource("range")
    val range = objectsRange(code, 100, 104)
    val hints = inlayHints(code, range)
    val (runId, typedTop, skipped, trees) = state()
    println(s"[range-types-only-the-touching-statements] objects 100-104: runId=$runId typedTop=$typedTop skipped=$skipped trees=$trees, ${hints.size} hints")
    assertEquals(10, typedTop, "the five objects touching the range are typed (an object counts as its module val and class)")
    assertEquals(395, skipped, "the other objects are skipped")
    assert(hints.nonEmpty, "the range has hints")
    val tokens = semanticTokens(code, range)
    assertEquals((runId, typedTop, skipped), { val (r, t, s, _) = state(); (r, t, s) }, "the tokens of the same range are served")
    assert(tokens.nonEmpty, "the range has tokens")
    // The same range, served from a whole-file run of the same offsets
    val whole = bigSource("whole")
    val wholeTrees = wholeFile(whole)
    println(s"[range-types-only-the-touching-statements] whole file: trees=$wholeTrees")
    assert(trees * 20 < wholeTrees, s"the range types a small part of the file: $trees of $wholeTrees trees")
    assertEquals(hints, inlayHints(whole, range), "the hints of the range, from a whole-file run")
    assertEquals(tokens, semanticTokens(whole, range), "the tokens of the range, from a whole-file run")

  @Test def `range-inside-a-method-body` =
    val code = bigSource("in-body")
    val giant = Span(code.indexOf("def giant("), code.indexOf("a0 + a199"))
    val range = Span(code.indexOf("val a10:"), code.indexOf("val a15:") - 1)
    val hints = inlayHints(code, range)
    val tokens = semanticTokens(code, range)
    val untyped = withDriver(_.untypedSpans(uri))
    val (_, typedTop, _, trees) = state()
    val inBody = untyped.count(s => giant.contains(s))
    println(s"[range-inside-a-method-body] vals 10-14 of giant: typedTop=$typedTop trees=$trees, skipped in giant's body: $inBody, ${hints.size} hints, ${tokens.size} tokens")
    assertEquals(2, typedTop, "only object 50 is typed")
    // a10 to a14 are typed; a0 to a9 are needed by them; a15 to a199 are skipped
    assertEquals(185, inBody, "the locals after the range are skipped")
    assert(hints.nonEmpty && tokens.nonEmpty)
    assert(trees < 1000, s"the range types a few locals: $trees trees")
    val whole = bigSource("in-body-whole")
    wholeFile(whole)
    assertEquals(hints, inlayHints(whole, range), "the hints of the range, from a whole-file run")
    assertEquals(tokens, semanticTokens(whole, range), "the tokens of the range, from a whole-file run")

  @Test def `a-second-range-resumes` =
    val code = bigSource("second")
    inlayHints(code, objectsRange(code, 100, 104))
    val (runId1, typedTop1, skipped1, _) = state()
    // the editor scrolls: the tokens of another visible range
    val range2 = objectsRange(code, 200, 202)
    val tokens = semanticTokens(code, range2)
    val (runId2, typedTop2, skipped2, _) = state()
    println(s"[a-second-range-resumes] after objects 100-104: runId=$runId1 typedTop=$typedTop1 skipped=$skipped1; after 200-202: runId=$runId2 typedTop=$typedTop2 skipped=$skipped2")
    assertEquals(runId1, runId2, "the second range resumes the run")
    assertEquals(typedTop1 + 6, typedTop2, "objects 200-202 are typed in addition")
    assertEquals(skipped1 - 3, skipped2, "objects 200-202 are no longer skipped")
    val whole = bigSource("second-whole")
    wholeFile(whole)
    assertEquals(tokens, semanticTokens(whole, range2), "the tokens of the range, from a whole-file run")

  @Test def `whole-file-range-is-the-whole-file` =
    val code =
      """|package a
         |object A {
         |  def f(xs: List[Int]) = xs.map(_ + 1)
         |  val x = f(List(1))
         |  class B[T](t: T) { def g = t }
         |}
         |""".stripMargin
    val all = semanticTokens(code + "// virtual\n", NoSpan)
    assert(all.nonEmpty)
    assertEquals(all, semanticTokens(code + "// ranged\n", Span(0, code.length)), "a range over the whole file is the whole file")
