package dotty.tools.pc.tests.completion

import java.net.URI
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.language.unsafeNulls
import scala.util.Random

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.parsing.{Scanners, Tokens}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

import org.junit.{Assume, Test}

/** Edit, hover, whole-file run, on the compiler's own sources (what the
 *  editor does after each keystroke inside a method: `didChange`, a hover,
 *  then document highlight or semantic tokens), with and without
 *  `-Yinteractive-body-edits`. Not a test: it runs when `local/measure-edits`
 *  exists in the checkout; the numbers are in docs §12.
 */
class SessionEditMeasure:

  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0",
      "-classpath", sys.props.getOrElse("java.class.path", ""))

  private val sourcesRoot: Option[Path] =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .map(_.resolve("compiler/src/dotty/tools/dotc")).find(Files.isDirectory(_))

  /** The right-hand sides of the member definitions with a declared result type. */
  private def bodies(driver: InteractiveDriver, uri: URI): List[Span] =
    given Context = driver.currentCtx
    val out = mutable.ListBuffer.empty[Span]
    def stats(ts: List[tpd.Tree]): Unit = ts.foreach {
      case p: tpd.PackageDef => stats(p.stats)
      case t: tpd.TypeDef => t.rhs match
        case impl: tpd.Template => stats(impl.body)
        case _ =>
      case d: tpd.ValOrDefDef if d.span.exists && !d.span.isSynthetic && d.tpt.span.exists && !d.tpt.span.isSynthetic =>
        if d.rhs.span.exists && !d.rhs.span.isZeroExtent then out += d.rhs.span
      case _ =>
    }
    driver.compilationUnits.get(uri).foreach(u => stats(u.tpdTree :: Nil))
    out.toList

  private def ms(t0: Long) = (System.nanoTime() - t0) / 1e6

  private def summary(xs: Seq[Double]): String =
    val s = xs.sorted
    def q(p: Double) = s(math.min(s.size - 1, (p * s.size).toInt))
    f"median ${q(0.5)}%.1f p90 ${q(0.9)}%.1f max ${s.last}%.1f total ${s.sum}%.0f"

  private def measure(rel: String, edits: Int, bodyEdits: Boolean): String =
    val root = sourcesRoot.get
    val path = root.resolve(rel)
    val uri = path.toUri
    var content = Files.readString(path)
    val driver = new InteractiveDriver(if bodyEdits then settings :+ "-Yinteractive-body-edits" else settings, CachedLogicalPackage.none)
    driver.run(uri, SourceFile.virtual(uri, content))
    val rng = new Random(1)
    val hover, whole = mutable.ArrayBuffer.empty[Double]
    val outcomes = mutable.Map.empty[String, Int].withDefaultValue(0)
    for i <- 0 until edits do
      // an edit inside a body: an integer literal gets one more digit
      val bs = bodies(driver, uri)
      def tokens(c: String) =
        val scanner = new Scanners.Scanner(SourceFile.virtual(uri, c))(using driver.currentCtx)
        val out = mutable.ArrayBuffer.empty[(Int, Int, Boolean)]
        while scanner.token != Tokens.EOF do
          out += ((scanner.offset, scanner.token, scanner.isIdent))
          scanner.nextToken()
        out
      val lits = tokens(content).collect {
        case (o, Tokens.INTLIT, _) if bs.exists(_.contains(Span(o))) && content.charAt(o).isDigit
            && content.substring(o).takeWhile(_.isLetterOrDigit).forall(_.isDigit) && content.substring(o).takeWhile(_.isDigit).length < 5 => o
      }
      val lit = lits(rng.nextInt(lits.size))
      val len = content.substring(lit).takeWhile(_.isDigit).length
      val digits = content.substring(lit, lit + len)
      content = content.substring(0, lit) + (if digits == "0" then "10" else digits + "1") + content.substring(lit + len)
      // an identifier elsewhere
      val idents = tokens(content).collect { case (o, _, true) if math.abs(o - lit) > 200 => o }
      val at = idents(rng.nextInt(idents.size))
      val src = SourceFile.virtual(uri, content)
      val t0 = System.nanoTime()
      driver.runUpTo(uri, src, Span(at + 1))
      hover += ms(t0)
      outcomes(driver.lastEdit(uri).replaceAll(" of .*", "").replaceAll(" \\(.*", "")) += 1
      if bodyEdits && i % 9 == 0 then println(s"[measure] $rel #$i ${driver.lastEdit(uri)}")
      val t1 = System.nanoTime()
      driver.run(uri, src)
      whole += ms(t1)
    s"$rel ${if bodyEdits then "on " else "off"}: hover after edit ${summary(hover.drop(5).toSeq)} | whole-file run after ${summary(whole.drop(5).toSeq)} | ${outcomes.toList.sorted.map((k, n) => s"$n× ${if k.isEmpty then "fresh" else k}").mkString(", ")}"

  @Test def `edit-then-hover` =
    Assume.assumeTrue(sourcesRoot.exists(r => Files.exists(r.resolve("../../../../../local/measure-edits").normalize)))
    // warm up both configurations on a smaller file
    measure("core/Contexts.scala", 20, bodyEdits = false); measure("core/Contexts.scala", 20, bodyEdits = true)
    val lines = for
      rel <- List("core/Types.scala", "core/TypeComparer.scala", "core/Contexts.scala")
      on <- List(false, true)
    yield measure(rel, 45, on)
    lines.foreach(l => println(s"[measure] $l"))
