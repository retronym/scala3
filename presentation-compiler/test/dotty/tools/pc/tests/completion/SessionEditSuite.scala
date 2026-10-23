package dotty.tools.pc.tests.completion

import java.io.File
import java.net.URI

import scala.collection.mutable
import scala.language.unsafeNulls

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Interactive, InteractiveDriver}
import dotty.tools.dotc.parsing.{Scanners, Tokens}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.base.BasePCSuite
import dotty.tools.pc.base.TestResources

import org.junit.Assert
import org.junit.Test

/** An edit inside the body of a definition with a declared type keeps the
 *  session's run (`-Yinteractive-body-edits`, docs §12): the definition is
 *  re-indexed with its old symbols and typed when a request needs it, and
 *  everything else is kept with its spans shifted. Every answer after an
 *  edit must be the one a fresh whole-file run on the new content gives, at
 *  every identifier of the file; an edit anywhere else is a fresh run.
 */
class SessionEditSuite extends BasePCSuite:

  private val uri = URI.create("file:///Edit.scala")

  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))

  private def newDriver() = new InteractiveDriver(settings :+ "-Yinteractive-body-edits", CachedLogicalPackage.none)

  private val base =
    """|import scala.collection.mutable
       |object A {
       |  /** The sum, plus one each. */
       |  def m1(xs: List[Int]): Int = xs.map(_ + 1).sum
       |  def m2(xs: List[Int]): Int = {
       |    val y = xs.size
       |    y + 2
       |  }
       |  def inferred(xs: List[Int]) = xs.size
       |  inline def inl(x: Int): Int = x + 1
       |  val v: String = "v" * m1(Nil)
       |  def withDefault(x: Int, y: Int = 3): Int = x + y
       |}
       |class B {
       |  def b(x: Int): Int = x * 2
       |}
       |object C {
       |  def c(x: Int): Int = A.m1(List(x)) + new B().b(x) + A.withDefault(x)
       |  val buf: mutable.ListBuffer[Int] = mutable.ListBuffer(A.m2(Nil))
       |}
       |""".stripMargin

  private def source(content: String) = SourceFile.virtual(uri, content)

  private def identifierEnds(content: String)(using Context): List[Int] =
    val scanner = new Scanners.Scanner(source(content))
    val out = mutable.ListBuffer.empty[Int]
    while scanner.token != Tokens.EOF do
      if scanner.isIdent then out += scanner.offset + scanner.name.toString.length
      scanner.nextToken()
    out.toList

  /** The innermost typed tree at `offset`: class, span, type, symbol, and the span of the symbol's definition. */
  private def describe(driver: InteractiveDriver, offset: Int): String =
    driver.compilationUnits.get(uri) match
      case None => "<no unit>"
      case Some(unit) =>
        given Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using driver.currentCtx))
        Interactive.pathTo(unit.tpdTree, Span(offset)).headOption match
          case None => "<no tree>"
          case Some(t) =>
            val sym = t.symbol
            val tpe = if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>"
            val at = if sym.exists && sym.span.exists && sym.source.path == unit.source.path then s" defined@${sym.span.point}" else ""
            s"${t.getClass.getSimpleName}[${t.span.start},${t.span.end}) : $tpe${if sym.exists then s" @ ${sym.showFullName}$at" else ""}"

  /** The diagnostics with a position (a whole run adds its summary, "1 error found"). */
  private def diagnosticsOf(diags: List[dotty.tools.dotc.reporting.Diagnostic]): String =
    diags.filter(_.pos.exists).map(d => s"${d.level} ${d.pos.span} ${d.msg.message.linesIterator.next()}").sorted.mkString("\n")

  /** Every identifier of `content`, hovered on `driver` (targeted), against a fresh whole-file run. */
  private def assertLikeFresh(driver: InteractiveDriver, content: String, what: String): Unit =
    val fresh = newDriver()
    val freshDiags = fresh.run(uri, source(content))
    val offsets = identifierEnds(content)(using fresh.currentCtx)
    for o <- offsets do
      driver.runUpTo(uri, source(content), Span(o))
      val obtained = describe(driver, o)
      val expected = describe(fresh, o)
      Assert.assertEquals(s"$what: hover at $o (${content.substring(math.max(0, o - 20), o)}) [${driver.lastEdit(uri)}]", expected, obtained)
    Assert.assertEquals(s"$what: diagnostics of the completed session", diagnosticsOf(freshDiags), diagnosticsOf(driver.run(uri, source(content))))

  private def runId(driver: InteractiveDriver) = driver.runIdOf(uri)

  private def at(content: String, marker: String, delta: Int = 0) =
    val i = content.indexOf(marker)
    Assert.assertTrue(s"marker $marker", i >= 0)
    Span(i + delta)

  @Test def `body-edit-keeps-the-run` =
    val driver = newDriver()
    driver.run(uri, source(base))
    val id = runId(driver)
    val edited = base.replace("    y + 2\n", "    val z = y * 10\n    z + 2 + xs.headOption.getOrElse(0)\n")
    // a hover elsewhere: no fresh run, nothing typed but the request's own
    driver.runUpTo(uri, source(edited), at(edited, "A.m1", 3))
    Assert.assertEquals(driver.lastEdit(uri), id, runId(driver))
    Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).startsWith("body edit of m2 (typed)"))
    Assert.assertEquals("the edited definition is skipped until needed", 1, driver.untypedSpans(uri).size)
    // the whole-file run types the edited definition alone, in the same run
    driver.run(uri, source(edited))
    Assert.assertEquals(id, runId(driver))
    Assert.assertTrue(driver.isFullyTyped(uri))
    assertLikeFresh(driver, edited, "after a body edit")
    Assert.assertEquals(id, runId(driver))

  @Test def `body-edit-of-a-skipped-definition` =
    val driver = newDriver()
    // a targeted session: only `object C`'s `c` is typed, `object A` is skipped whole
    driver.runUpTo(uri, source(base), at(base, "A.m1", 3))
    val id = runId(driver)
    // m1 is in object A, skipped whole: a fresh run (first version)
    val inA = base.replace("xs.map(_ + 1).sum", "xs.map(_ + 10).sum")
    driver.runUpTo(uri, source(inA), at(inA, "A.m1", 3))
    Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).contains("skipped whole"))
    Assert.assertNotEquals(id, runId(driver))
    // `buf` is a skipped member of the typed object C
    val id2 = runId(driver)
    val inBuf = inA.replace("mutable.ListBuffer(A.m2(Nil))", "mutable.ListBuffer(A.m2(Nil), 7)")
    driver.runUpTo(uri, source(inBuf), at(inBuf, "A.m1", 3))
    Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).startsWith("body edit of buf (skipped)"))
    Assert.assertEquals(id2, runId(driver))
    assertLikeFresh(driver, inBuf, "after a body edit of a skipped member")
    Assert.assertEquals(id2, runId(driver))

  @Test def `successive-body-edits` =
    val driver = newDriver()
    driver.run(uri, source(base))
    val id = runId(driver)
    var content = base
    for (from, to) <- List(
        "xs.map(_ + 1).sum" -> "xs.map(_ + 1).sum + 1",
        "xs.map(_ + 1).sum + 1" -> "xs.map(_ + 1).su",            // a type error
        "xs.map(_ + 1).su" -> "xs.map(_ + 1).sum + v.length",
        "x * 2" -> "x * 2 * x",
        "\"v\" * m1(Nil)" -> "\"vv\" * m1(List(1, 2))") do
      content = content.replace(from, to)
      driver.runUpTo(uri, source(content), at(content, "new B", 4))
      Assert.assertEquals(s"$from -> $to: ${driver.lastEdit(uri)}", id, runId(driver))
      Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).startsWith("body edit"))
      assertLikeFresh(driver, content, s"after $from -> $to")
      Assert.assertEquals(id, runId(driver))

  @Test def `layout-edit-keeps-the-run` =
    val driver = newDriver()
    driver.run(uri, source(base))
    val id = runId(driver)
    val edited = base.replace("object A {\n", "object A {\n\n   // a comment\n")
    driver.runUpTo(uri, source(edited), at(edited, "A.m1", 3))
    Assert.assertEquals(driver.lastEdit(uri), id, runId(driver))
    Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).startsWith("layout edit"))
    assertLikeFresh(driver, edited, "after a layout edit")
    Assert.assertEquals(id, runId(driver))

  /** Edits that are not inside a body of a definition with a declared type: a fresh run. */
  @Test def `other-edits-are-fresh-runs` =
    for (what, from, to) <- List(
        ("a signature", "def m2(xs: List[Int]): Int", "def m2(xs: List[Int]): Long"),
        ("a parameter name", "def m2(xs: List[Int])", "def m2(ys: List[Int])"),
        ("an import", "import scala.collection.mutable", "import scala.collection.{mutable, immutable}"),
        ("an inferred result type", "def inferred(xs: List[Int]) = xs.size", "def inferred(xs: List[Int]) = xs.headOption"),
        ("an inline method", "inline def inl(x: Int): Int = x + 1", "inline def inl(x: Int): Int = x + 2"),
        ("a doc comment", "The sum, plus one each.", "The sum, plus one."),
        ("a new member", "  val v: String", "  def added: Int = 1\n  val v: String"),
        ("a default argument", "y: Int = 3", "y: Int = 4")) do
      val driver = newDriver()
      driver.run(uri, source(base))
      val id = runId(driver)
      val edited = base.replace(from, to)
      Assert.assertNotEquals(what, base, edited)
      driver.runUpTo(uri, source(edited), at(edited, "new B", 4))
      Assert.assertNotEquals(s"$what: ${driver.lastEdit(uri)}", id, runId(driver))
      assertLikeFresh(driver, edited, s"after an edit of $what")

  /** The knob: without `-Yinteractive-body-edits` an edit is a fresh run, as before. */
  @Test def `off-by-default` =
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    driver.runUpTo(uri, source(base), at(base, "A.m1", 3))
    val id = runId(driver)
    val edited = base.replace("x * 2", "x * 3")
    driver.runUpTo(uri, source(edited), at(edited, "A.m1", 3))
    Assert.assertNotEquals(id, runId(driver))
    Assert.assertFalse(driver.bodyEdits)
