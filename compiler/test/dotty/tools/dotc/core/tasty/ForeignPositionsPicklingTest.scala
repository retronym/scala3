package dotty.tools.dotc.core.tasty

import java.nio.file.{Files, Path}

import org.junit.Test
import org.junit.Assert.{assertArrayEquals, assertFalse}

import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.Diagnostic.ERROR
import dotty.tools.dotc.reporting.TestReporter

import dotty.tools.vulpix.TestConfiguration

/** The TASTy of a class must not depend on the layout of another source file,
 *  even if it contains annotated types that were copied from that file.
 */
class ForeignPositionsPicklingTest {

  private val parent =
    """package p
      |class foo(x: Int) extends scala.annotation.StaticAnnotation
      |abstract class P {
      |  def f: Int @foo(1)
      |  def tell(m: Any)(implicit s: P = null): Unit
      |}
      |object PO { def g: Int @foo(2) = 1 }
      |""".stripMargin

  // `f` and the default getter of `tell` inherit their result types from `P`,
  // `y` gets its type from `PO.g`.
  private val child =
    """package p
      |class Q extends P {
      |  def f = 1
      |  def tell(m: Any)(implicit s: P = null): Unit = ()
      |  val y = PO.g
      |}
      |""".stripMargin

  private def compileQ(parentPrefix: String): Array[Byte] =
    val dir = Files.createTempDirectory("foreign-positions")
    val src = Files.createDirectory(dir.resolve("src"))
    val out = Files.createDirectory(dir.resolve("out"))
    Files.writeString(src.resolve("P.scala"), parentPrefix + parent)
    Files.writeString(src.resolve("Q.scala"), child)
    val options = TestConfiguration.defaultOptions
      .and("-d", out.toString)
      .and("-sourceroot", src.toString)
      .and(src.resolve("P.scala").toString, src.resolve("Q.scala").toString)
    val reporter = TestReporter.reporter(System.out, logLevel = ERROR)
    assertFalse("Compilation failed.", Main.process(options.all, reporter).hasErrors)
    Files.readAllBytes(out.resolve("p/Q.tasty"))

  @Test def parentLayoutDoesNotAffectChildTasty(): Unit =
    val before = compileQ("")
    val after = compileQ("// a comment\n\n")
    assertArrayEquals(before, after)
}
