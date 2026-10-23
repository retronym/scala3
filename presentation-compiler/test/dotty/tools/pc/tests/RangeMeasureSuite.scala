package dotty.tools.pc.tests

import java.nio.file.{Files, Path, Paths}
import java.util as ju

import scala.language.unsafeNulls
import scala.meta.internal.metals.{CompilerInlayHintsParams, CompilerRangeParams, CompilerVirtualFileParams}
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.pc.SourcePathMode

import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.{NoSpan, Span}
import dotty.tools.pc.{CachingDriver, PcInlayHintsProvider, PcSemanticTokensProvider}
import dotty.tools.pc.base.BasePCSuite

import org.junit.Test

/** A measurement, not a check: inlay hints and semantic tokens for a visible
 *  range of `Types.scala` of this checkout (against the compiler's own
 *  classes on the test JVM's classpath), on fresh content, against a
 *  whole-file run of the same offsets; the answers for the range are
 *  compared. Skipped when the sources are not found.
 */
class RangeMeasureSuite extends BasePCSuite:

  private def typesScala: Option[Path] =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .map(_.resolve("compiler/src/dotty/tools/dotc/core/Types.scala")).find(Files.isRegularFile(_))

  @Test def `measure` =
    typesScala match
      case None => println("[range-measure] Types.scala not found, skipped")
      case Some(path) =>
        val settings = List("-color:never", "-Ximport-suggestion-timeout", "0",
          "-classpath", sys.props("java.class.path"))
        val driver = CachingDriver(settings, () => ju.List.of(), null, SourcePathMode.DISABLED)
        val base = Files.readString(path)
        val uri = path.toUri
        val lineStarts = 0 :: base.indices.filter(base(_) == '\n').map(_ + 1).toList
        // a screenful: 60 lines from `line`
        def visible(line: Int) = Span(lineStarts(line - 1), lineStarts(line + 59) - 1)
        def content(tag: String) = s"$base// $tag\n"
        def hints(code: String, range: Span) =
          val params = CompilerInlayHintsParams(
            rangeParams = CompilerRangeParams(uri, code, range.start, range.end),
            inferredTypes = true, typeParameters = true, implicitParameters = true,
            hintsXRayMode = false, byNameParameters = true, implicitConversions = true,
            namedParameters = true, hintsInPatternMatch = false, closingLabels = true)
          new PcInlayHintsProvider(driver, params, EmptySymbolSearch).provide().map(_.toString)
        def tokens(code: String, range: Span) =
          val params = if range.exists then CompilerRangeParams(uri, code, range.start, range.end) else CompilerVirtualFileParams(uri, code)
          new PcSemanticTokensProvider(driver, params).provide().map(n => (n.start(), n.end(), n.tokenType(), n.tokenModifier()))
        def timed[T](body: => T): (T, Long) =
          val t0 = System.nanoTime()
          val r = body
          (r, (System.nanoTime() - t0) / 1000000)
        def trees = driver.currentCtx.run.typedTrees
        // warm up
        for i <- 0 until 3 do
          driver.run(uri, SourceFile.virtual(uri, content(s"warm-whole-$i")))
          hints(content(s"warm-range-$i"), visible(1200))
        var mismatches = 0
        for (line, k) <- List(1200, 3600, 6200, 1200, 3600, 6200).zipWithIndex do
          val range = visible(line)
          val code = content(s"range-$k")
          val (h, hintsMs) = timed(hints(code, range))
          val hintsTrees = trees
          val (t, tokensMs) = timed(tokens(code, range))
          val tokensTrees = trees - hintsTrees
          val typedTop = driver.typedTopLevelCount(uri)
          val skipped = driver.untypedSpans(uri).size
          // the driver alone, on fresh content
          val (_, driverMs) = timed(driver.runUpTo(uri, SourceFile.virtual(uri, content(s"driver-$k")), range))
          val driverTrees = trees
          val whole = content(s"whole-$k")
          val (_, wholeMs) = timed(driver.run(uri, SourceFile.virtual(uri, whole)))
          val wholeTrees = trees
          val (h2, hints2Ms) = timed(hints(whole, range))
          val (t2, tokens2Ms) = timed(tokens(whole, range))
          val (all, allTokensMs) = timed(tokens(whole, NoSpan))
          if h != h2 || t != t2 then
            mismatches += 1
            println(s"[range-measure] lines $line-${line + 59}: MISMATCH hints ${h.size} vs ${h2.size} (${h.diff(h2).take(3)} / ${h2.diff(h).take(3)}), tokens ${t.size} vs ${t2.size} (${t.diff(t2).take(3)} / ${t2.diff(t).take(3)})")
          println(s"[range-measure] lines $line-${line + 59}: range: inlay hints ${hintsMs} ms ($hintsTrees trees, typedTop=$typedTop, skipped=$skipped, ${h.size} hints), semantic tokens ${tokensMs} ms ($tokensTrees trees, ${t.size} tokens), runUpTo alone ${driverMs} ms ($driverTrees trees); whole file: run ${wholeMs} ms ($wholeTrees trees), then inlay hints ${hints2Ms} ms, range tokens ${tokens2Ms} ms, whole-file tokens ${allTokensMs} ms (${all.size} tokens)")
        assertEquals(0, mismatches, "the range answers as the whole file")
