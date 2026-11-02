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

  private def diagnosticsOf(diags: List[dotty.tools.dotc.reporting.Diagnostic]): String = SessionDiagnostics.of(diags)

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

  /** The typed body of an inline method is typed ahead by its completer and
   *  kept as an annotation; its spans after an edit before it (`Chars`, the
   *  oracle's `@switch` in `requiresFormat`).
   */
  @Test def `spans-after-an-edit-before-an-inline-method` =
    val content =
      """|import scala.annotation.switch
         |object I {
         |  def g(x: Int): Int = x + 1
         |  private inline def f(c: Char): Boolean = (c: @switch) match
         |    case 'a' => true
         |    case _ => false
         |  def h(c: Char): Boolean = f(c) || f('b')
         |}
         |""".stripMargin
    val driver = newDriver()
    driver.run(uri, source(content))
    val id = runId(driver)
    val edited = content.replace("x + 1", "x + 1 + x * 2")
    driver.runUpTo(uri, source(edited), at(edited, "f(c)"))
    Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).startsWith("body edit of g"))
    assertLikeFresh(driver, edited, "after an edit before an inline method")
    Assert.assertEquals(id, runId(driver))

  /** The knob: without `-Yinteractive-body-edits` an edit is a fresh run, as before. */
  @Test def `off-by-default` =
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    driver.runUpTo(uri, source(base), at(base, "A.m1", 3))
    val id = runId(driver)
    val edited = base.replace("x * 2", "x * 3")
    driver.runUpTo(uri, source(edited), at(edited, "A.m1", 3))
    Assert.assertNotEquals(id, runId(driver))
    Assert.assertFalse(driver.bodyEdits)


  // ---- Diagnostics are invalidated by what reported them, not where they are (docs §12, "Diagnostics", I69)

  /** An edit, a whole-file run of the edited content in the same run, and
   *  its diagnostics against a fresh run's.
   */
  private def assertEditedLikeFresh(content: String, from: String, to: String, what: String, expected: String): Unit =
    val driver = newDriver()
    driver.run(uri, source(content))
    val id = runId(driver)
    val edited = content.replace(from, to)
    Assert.assertNotEquals(what, content, edited)
    val fresh = diagnosticsOf(newDriver().run(uri, source(edited)))
    Assert.assertEquals(s"$what: the fresh run", expected, fresh)
    val obtained = diagnosticsOf(driver.run(uri, source(edited)))
    Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).startsWith("body edit"))
    Assert.assertEquals(s"$what: the session's run", id, runId(driver))
    Assert.assertEquals(s"$what [${driver.lastEdit(uri)}]", fresh, obtained)

  /** An error reported by typing `d`, positioned in the sibling it inlined
   *  from: the edit that removes the call removes the error.
   */
  @Test def `an-error-the-edited-body-inlined-from-a-sibling-goes` =
    val content =
      """|object A {
         |  transparent inline def need[T]: T = compiletime.summonInline[T]
         |  def d: Int = need[String].length
         |  def e: Int = 1
         |}
         |""".stripMargin
    assertEditedLikeFresh(content, "need[String].length", "1 + 2", "a call that inlined an error, removed", "")

  /** A check of the class (made when the class's statement was typed),
   *  positioned at the edited definition: the edit does not remove it.
   */
  @Test def `a-check-of-the-class-positioned-in-the-edited-definition-stays` =
    val content =
      """|object A {
         |  def f(x: Int): Int = x
         |  def f(x: Int): Int = x + 1
         |}
         |""".stripMargin
    val edited = content.replace("x + 1", "x + 22")
    val conflict = diagnosticsOf(newDriver().run(uri, source(edited)))
    Assert.assertTrue(conflict, conflict.contains("Conflicting definitions"))
    assertEditedLikeFresh(content, "x + 1", "x + 22", "a body edit of a conflicting definition", conflict)

  /** A `@nowarn` inside the edited body, removed: its suppression goes with it. */
  @Test def `a-nowarn-removed-by-the-edit-unsuppresses` =
    val content =
      """|object A {
         |  def d: Int = {
         |    @annotation.nowarn def g: Int = { 1; 2 }
         |    g
         |  }
         |  def e: Int = 3
         |}
         |""".stripMargin
    val edited = content.replace("@annotation.nowarn ", "")
    val warning = diagnosticsOf(newDriver().run(uri, source(edited)))
    Assert.assertTrue(warning, warning.contains("A pure expression does nothing"))
    assertEditedLikeFresh(content, "@annotation.nowarn ", "", "a @nowarn removed", warning)

  /** And one added: the old warning goes, the new suppression holds. */
  @Test def `a-nowarn-added-by-the-edit-suppresses` =
    val content =
      """|object A {
         |  def d: Int = {
         |    def g: Int = { 1; 2 }
         |    g
         |  }
         |}
         |""".stripMargin
    assertEditedLikeFresh(content, "def g", "@annotation.nowarn def g", "a @nowarn added", "")

  /** An error inside the body, fixed by the edit; one in a sibling after it, shifted. */
  @Test def `an-error-fixed-by-the-edit-goes-a-sibling-error-shifts` =
    val content =
      """|object A {
         |  def d: Int = "no"
         |  def e: Int = "still"
         |}
         |""".stripMargin
    val edited = content.replace("\"no\"", "1 + 2 + 3")
    val one = diagnosticsOf(newDriver().run(uri, source(edited)))
    Assert.assertTrue(one, one.contains("\"still\"") && !one.contains("\"no\""))
    assertEditedLikeFresh(content, "\"no\"", "1 + 2 + 3", "an error fixed", one)

  /** Until the edited definition is typed again its old diagnostics are
   *  published, mapped through the edit (no flicker); typing it replaces them.
   */
  @Test def `the-edited-definition-s-diagnostics-are-stale-until-it-is-typed` =
    val content =
      """|object A {
         |  def d: Int = { val k = 1; "s" }
         |  def e: Int = 2
         |}
         |""".stripMargin
    val driver = newDriver()
    val before = driver.run(uri, source(content)).filter(_.pos.exists)
    Assert.assertEquals(before.toString, 1, before.size)
    val edited = content.replace("val k = 1;", "val k = 12;")
    // a hover in `e`: `d` is not typed
    val stale = driver.runUpTo(uri, source(edited), at(edited, "2\n", 0)).filter(_.pos.exists)
    Assert.assertTrue(driver.lastEdit(uri), driver.lastEdit(uri).startsWith("body edit of d"))
    Assert.assertEquals(driver.untypedSpans(uri).toString, 1, driver.untypedSpans(uri).size)
    Assert.assertEquals("the old error, moved", List(before.head.pos.start + 1), stale.map(_.pos.start))
    // the whole-file run types `d`: its own diagnostics
    Assert.assertEquals(diagnosticsOf(newDriver().run(uri, source(edited))), diagnosticsOf(driver.run(uri, source(edited))))


  // ---- Edits at the end of an indentation-syntax body

  private val braceless =
    """|object M:
       |  val Reader = 1
       |  val Writer = 2
       |  def combine(a: Int, b: Int): Int = (a, b) match
       |    case (Reader, _) => Reader
       |    case (_, Reader) => Reader
       |
       |  def check(x: Int): Int = x match
       |    case 0 =>
       |      val y = x + 1
       |      y
       |    case _ =>
       |      val z = x * 2
       |      z
       |  def block(xs: List[Int]): Int =
       |    val n = xs.size
       |    n + 1
       |  def msg: String = "m"
       |  def show(err: String): String = msg + err
       |
       |  /** Next. */
       |  def next: Int = 1
       |""".stripMargin

  /** Successive edits from `content`, each a body edit of `name` in the session's run, its answers a fresh run's. */
  private def assertBodyEdits(content: String, name: String, edits: List[(String, String)]): Unit =
    val driver = newDriver()
    driver.run(uri, source(content))
    val id = runId(driver)
    var now = content
    for (from, to) <- edits do
      val edited = now.replace(from, to)
      Assert.assertNotEquals(from, now, edited)
      now = edited
      driver.runUpTo(uri, source(now), at(now, "def next", 4))
      Assert.assertTrue(s"$from -> $to: ${driver.lastEdit(uri)}", driver.lastEdit(uri).startsWith(s"body edit of $name"))
      Assert.assertEquals(id, runId(driver))
      assertLikeFresh(driver, now, s"after $from -> $to")
      Assert.assertEquals(id, runId(driver))

  /** A line appended to a braceless body: the common prefix takes the old
   *  last line's newline and the indentation, so the edit as found starts
   *  after the body; it is the body's, which the new parse extends.
   */
  @Test def `a-line-appended-to-a-braceless-body` =
    assertBodyEdits(braceless, "combine", List(
      "    case (_, Reader) => Reader\n" -> "    case (_, Reader) => Reader\n    case (Writer, _) => Writer\n",
      "    case (Writer, _) => Writer\n" -> "    case (Writer, _) => Writer\n    case _ => 0\n"))

  @Test def `a-line-appended-to-the-last-case-body-of-a-braceless-match` =
    assertBodyEdits(braceless, "check", List(
      "      z\n" -> "      z\n      z + 1\n",
      "      z + 1\n" -> "      z + 1\n    case 1 =>\n      x\n"))

  @Test def `lines-appended-to-a-braceless-block` =
    assertBodyEdits(braceless, "block", List(
      "    n + 1\n" -> "    n + 1\n    println(n)\n",
      "    println(n)\n" -> "    println(n)\n    n + 2\n"))

  @Test def `the-last-line-of-a-braceless-body-deleted` =
    assertBodyEdits(braceless, "combine", List("    case (_, Reader) => Reader\n" -> ""))
    assertBodyEdits(braceless, "block", List("    n + 1\n" -> ""))

  /** Lines that are a new sibling, not part of the body before them: a fresh run. */
  @Test def `a-member-appended-after-a-braceless-body-is-a-fresh-run` =
    for (what, from, to) <- List(
        ("a member", "    n + 1\n", "    n + 1\n  def added: Int = 2\n"),
        ("a statement of the object", "    n + 1\n", "    n + 1\n  println(1)\n")) do
      val driver = newDriver()
      driver.run(uri, source(braceless))
      val id = runId(driver)
      val edited = braceless.replace(from, to)
      driver.runUpTo(uri, source(edited), at(edited, "def next", 4))
      Assert.assertNotEquals(s"$what: ${driver.lastEdit(uri)}", id, runId(driver))
      assertLikeFresh(driver, edited, s"after $what")

  /** The completions at `offset`, `driver` on `content`. */
  private def completions(driver: InteractiveDriver, content: String, offset: Int): List[String] =
    driver.runUpTo(uri, source(content), Span(offset))
    val unit = driver.compilationUnits(uri)
    given Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using driver.currentCtx))
    dotty.tools.dotc.interactive.Completion.completions(dotty.tools.dotc.util.SourcePosition(unit.source, Span(offset)))._2
      .map(c => c.label + " " + c.description).sorted.distinct

  /** A dangling `qual.` at the end of a braceless body, what an editor
   *  sends on every `.`: the parser reports the missing identifier at the
   *  next member's token, which is the body's error.
   */
  @Test def `a-dangling-select-at-the-end-of-a-braceless-body` =
    val driver = newDriver()
    driver.run(uri, source(braceless))
    val id = runId(driver)
    var now = braceless
    for (from, to) <- List("msg + err\n" -> "msg + err.\n", "msg + err.\n" -> "msg + err.le\n", "msg + err.le\n" -> "msg + err.length.\n") do
      now = now.replace(from, to)
      val cursor = now.indexOf(to) + to.length - 1
      val obtained = completions(driver, now, cursor)
      Assert.assertTrue(s"$from -> $to: ${driver.lastEdit(uri)}", driver.lastEdit(uri).startsWith("body edit of show"))
      Assert.assertEquals(id, runId(driver))
      val expected = completions(newDriver(), now, cursor)
      Assert.assertTrue(s"$from -> $to: ${expected.take(5)}", expected.nonEmpty)
      Assert.assertEquals(s"$from -> $to", expected, obtained)
      assertLikeFresh(driver, now, s"after $from -> $to")
      Assert.assertEquals(id, runId(driver))
