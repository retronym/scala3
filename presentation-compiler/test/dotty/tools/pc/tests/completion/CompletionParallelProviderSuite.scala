package dotty.tools.pc.tests.completion

import java.nio.file.Path

import dotty.tools.pc.base.BaseCompletionSuite

import org.junit.Test

/** Finding I88: with `-Yinteractive-parallel` the run's root typer state is
 *  read-only, and a provider that reads the typed tree in `driver.currentCtx`
 *  after `runUpTo` applied an extension method or an implicit conversion to
 *  the receiver there: the commit asserted, the provider caught it and
 *  answered nothing.
 */
class CompletionParallelProviderSuite extends BaseCompletionSuite:

  override protected def scalacOptions(classpath: Seq[Path]): Seq[String] =
    super.scalacOptions(classpath) :+ "-Yinteractive-parallel"

  @Test def `extension-method` =
    check(
      """|package example
         |
         |object enrichments:
         |  extension (num: Int)
         |    def incr: Int = num + 1
         |
         |def main = 100.i@@
         |""".stripMargin,
      """|incr: Int (extension)
         |""".stripMargin,
      filter = _.contains("(extension)")
    )

  @Test def `implicit-class` =
    check(
      """|package example
         |
         |object enrichments:
         |  implicit class TestOps(a: Int):
         |    def testOps(b: Int): String = ???
         |
         |def main = 100.t@@
         |""".stripMargin,
      """|testOps(b: Int): String (implicit)
         |""".stripMargin,
      filter = _.contains("(implicit)")
    )
