package dotty.tools.backend.jvm

import dotty.DottyBytecodeTest
import org.junit.Assert.*
import org.junit.Test

class StringInterpolatorOptTest extends DottyBytecodeTest {
  import dotty.AsmConverters.*

  @Test def testRawInterpolator = {
    val source =
      """
        |class Foo {
        |  val one = 1
        |  val two = "two"
        |  val three = 3.0
        |
        |  def meth1: String = raw"$one plus $two$three\n"
        |  def meth2: String = "" + one + " plus " + two + three + "\\n"
        |}
      """.stripMargin

    checkBCode(source) { dir =>
      val clsIn   = lookupClass(dir, "Foo.class")
      val clsNode = loadClassNode(clsIn)
      val meth1   = getMethod(clsNode, "meth1")
      val meth2   = getMethod(clsNode, "meth2")

      val instructions1 = instructionsFromMethod(meth1)
      val instructions2 = instructionsFromMethod(meth2)

      assert(instructions1 == instructions2,
        "the `` string interpolator incorrectly converts to string concatenation\n" +
          diffInstructions(instructions1, instructions2))
    }
  }

  @Test def testSInterpolator = {
    val source =
      """
         |class Foo {
         |  val one = 1
         |  val two = "two"
         |  val three = 3.0
         |
         |  def meth1: String = s"$one plus $two$three\n"
         |  def meth2: String = "" + one + " plus " + two + three + "\n"
         |}
      """.stripMargin

    checkBCode(source) { dir =>
      val clsIn   = lookupClass(dir, "Foo.class")
      val clsNode = loadClassNode(clsIn)
      val meth1   = getMethod(clsNode, "meth1")
      val meth2   = getMethod(clsNode, "meth2")

      val instructions1 = instructionsFromMethod(meth1)
      val instructions2 = instructionsFromMethod(meth2)

      assert(instructions1 == instructions2,
        "the `s` string interpolator incorrectly converts to string concatenation\n" +
          diffInstructions(instructions1, instructions2))
    }
  }

  // Interpolations with more than 32 operands are concatenated in chunks by
  // StringInterpolatorOpt; the backend should flatten them into one concatenation.
  @Test def testLargeSInterpolator = {
    val source =
      s"""
         |class Foo {
         |  val one = 1
         |  val two = "two"
         |
         |  def meth1: String = s"${"$one,$two" * 40}"
         |  def meth2: String = "" ${"+ one + \",\" + two " * 40}
         |}
      """.stripMargin

    checkBCode(source) { dir =>
      val clsIn   = lookupClass(dir, "Foo.class")
      val clsNode = loadClassNode(clsIn)
      val meth1   = getMethod(clsNode, "meth1")
      val meth2   = getMethod(clsNode, "meth2")

      val instructions1 = instructionsFromMethod(meth1)
      val instructions2 = instructionsFromMethod(meth2)

      assert(instructions1 == instructions2,
        "the `s` string interpolator incorrectly converts a large interpolation to string concatenation\n" +
          diffInstructions(instructions1, instructions2))
    }
  }
}
