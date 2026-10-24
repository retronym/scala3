package dotty.tools.pc.tests.completion

import java.net.URI
import java.nio.file.Path

import scala.concurrent.duration.*
import scala.language.unsafeNulls
import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.metals.{CompilerOffsetParams, EmptyCancelToken}
import scala.meta.internal.pc.PresentationCompilerConfigImpl

import dotty.tools.pc.{CachingDriver, ScalaPresentationCompiler}
import dotty.tools.pc.base.BaseCompletionSuite

import org.junit.Test

/** A completion with an empty prefix after a space, an open parenthesis or
 *  on an empty line (`val y = @@`), or on a keyword, has the provider insert
 *  its cursor placeholder into the text (after an identifier or a `.` it
 *  does not). Stock takes a fresh driver for it, so
 *  the commonest member completion paid a cold compiler and a run of its own
 *  on every request. With targeted requests it goes through the session
 *  (I82) when the session keeps its run across an edit inside a body
 *  (`-Yinteractive-body-edits`), and the real content is restored once the
 *  completions are built; without body edits the stock fresh driver stays.
 */
abstract class CursorPlaceholderBase extends BaseCompletionSuite:

  protected val timeout = 60.seconds

  override protected def config: PresentationCompilerConfigImpl =
    super.config.copy(timeoutDelay = 120)

  protected val uri: URI = URI.create("file:///Placeholder.scala")

  /** 400 objects; object `at` has `val y = @@` in its method's body. */
  protected def source(at: Int): String =
    (0 until 400).map { i =>
      val extra = if i == at then "\n    val y = @@\n" else "\n"
      s"""|object M$i {
          |  def method$i(xs: List[Int]): Int = {$extra    xs.map(_ + $i).filter(_ > $i).sum
          |  }
          |}""".stripMargin
    }.mkString("\n")

  private def pc = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]

  protected def withDriver[T](f: CachingDriver => T): T =
    pc.compilerAccess.withNonInterruptableCompiler(null.asInstanceOf[T], EmptyCancelToken) { access =>
      f(access.compiler())
    }(using scala.meta.internal.metals.PcQueryContext(None, () => "")(using scala.meta.pc.reports.EmptyReportContext())).get(timeout.length, timeout.unit)

  protected def complete(query: String): List[String] =
    val (code, offset) = params(query)
    presentationCompiler
      .complete(CompilerOffsetParams(uri, code, offset, EmptyCancelToken))
      .get(timeout.length, timeout.unit)
      .getItems.asScala.map(_.getLabel).toList

  /** (run id, typed top-level statements, whether the session is on `code`, the last edit) */
  protected def state(code: String): (Int, Int, Boolean, String) = withDriver { d =>
    (d.runIdOf(uri), d.typedTopLevelCount(uri), d.compilationUnits.get(uri).exists(_.source.textContent() == code), d.lastEdit(uri))
  }

  protected def realContent(query: String): String = params(query)._1

  protected def timed[T](body: => T): (T, Long) =
    val start = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - start) / 1000000)

class CursorPlaceholderSuite extends CursorPlaceholderBase:

  override protected def scalacOptions(classpath: Seq[Path]): Seq[String] =
    super.scalacOptions(classpath) :+ "-Yinteractive-body-edits"

  @Test def `an-empty-prefix-is-served-in-the-session's-run` =
    val query = source(200)
    // a request with a prefix first: the session on the real content
    val (code, offset) = params(query)
    withDriver(_.runUpTo(uri, dotty.tools.dotc.util.SourceFile.virtual(uri, code), dotty.tools.dotc.util.Spans.Span(offset)))
    val (run0, typed0, on0, _) = state(code)
    assert(on0 && typed0 > 0 && typed0 < 800, s"a targeted session on the real content: typed $typed0")
    val (items, ms) = timed(complete(query))
    assert(items.exists(_.startsWith("xs")), s"the scope's names: ${items.take(10)}")
    val (run1, typed1, on1, edit1) = state(code)
    println(s"[cursor-placeholder] body edits: ${ms}ms, run $run0 -> $run1, typed $typed0 -> $typed1, last edit: $edit1")
    assertEquals(run0, run1, "the placeholder and its removal are edits inside a body: the same run")
    assert(edit1.contains("body edit"), s"the session saw the placeholder as a body edit: $edit1")
    assert(on1, "the real content is restored")
    assert(typed1 < 800, "never a whole-file run")

class CursorPlaceholderNoBodyEditsSuite extends CursorPlaceholderBase:

  @Test def `without-body-edits-the-session-is-left-alone` =
    val query = source(200)
    val (code, offset) = params(query)
    withDriver(_.runUpTo(uri, dotty.tools.dotc.util.SourceFile.virtual(uri, code), dotty.tools.dotc.util.Spans.Span(offset)))
    val (run0, typed0, on0, _) = state(code)
    assert(on0 && typed0 < 800)
    val (items, ms) = timed(complete(query))
    assert(items.exists(_.startsWith("xs")), s"the scope's names: ${items.take(10)}")
    val (run1, typed1, on1, _) = state(code)
    println(s"[cursor-placeholder] no body edits: ${ms}ms, run $run0 -> $run1, typed $typed0 -> $typed1")
    // the stock path: a fresh driver for the placeholder, this driver's run untouched
    assertEquals(run0, run1, "the session is left alone")
    assert(on1 && typed1 == typed0)
