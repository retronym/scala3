package dotty.tools
package backend.jvm

import dotty.DottyBytecodeTest
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.core.Contexts.{Context, ctx}
import org.junit.Test
import org.junit.Assert.*
import org.objectweb.asm.Opcodes

import java.io.File

/** Resolution of default methods by the inliner's `BCodeRepository.methodNode`, see scala/bug#12787.
 *  The interfaces are compiled by javac: Scala classes get mixin forwarders, so lookup would never
 *  reach `findInInterfaces`.
 */
class InterfaceMethodLookupTest extends DottyBytecodeTest {
  import dotty.AsmConverters.*

  private val javaSources = List(
    VirtualJavaSource("B.java", "public interface B { default int m() { return 42; } }"),
    VirtualJavaSource("A.java", "public interface A extends B {}"),
    VirtualJavaSource("C1.java", "public final class C1 implements A, B {}"),
    VirtualJavaSource("C2.java", "public final class C2 implements B, A {}"),
    VirtualJavaSource("D.java", "public interface D extends A { default int m() { return 7; } }"),
    VirtualJavaSource("F.java", "public final class F implements D {}"),
  )

  private val scalaSource =
    """class Test {
      |  def c1(c: C1): Int = c.m()
      |  def c2(c: C2): Int = c.m()
      |  def f(c: F): Int = c.m()
      |}
      |""".stripMargin

  private def assertInlined(dir: io.AbstractFile, meth: String, result: Int): Unit = {
    val instrs = instructionsFromMethod(getMethod(loadClassNode(lookupClass(dir, "Test.class")), meth))
    assertFalse(s"m() not inlined in $meth:\n${instrs.mkString("\n")}", instrs.exists {
      case Invoke(_, _, "m", _, _) => true
      case _ => false
    })
    assertTrue(s"expected $result in $meth:\n${instrs.mkString("\n")}", instrs.contains(IntOp(Opcodes.BIPUSH, result)))
  }

  private def checkInlined(meth: String, result: Int): Unit = withJavaCompiled(javaSources*) { javaOut =>
    given Context = {
      val c = initCtx
      c.setSetting(c.settings.classpath, c.settings.classpath.value(using c) + File.pathSeparator + javaOut)
      c.setSetting(c.settings.opt, true)
      c.setSetting(c.settings.optInline, List("**"))
      c.setSetting(c.settings.YoptInlineHeuristics, "everything")
    }
    new Compiler().newRun.compileFromStrings(List(scalaSource))
    assertInlined(ctx.settings.outputDir.value, meth, result)
  }

  // `B` is reached twice when `A` (which extends `B`) is listed first
  @Test def subInterfaceFirst(): Unit = checkInlined("c1", 42)
  @Test def superInterfaceFirst(): Unit = checkInlined("c2", 42)

  // `D.m` is more specific than `B.m` via `A`, not as a direct superinterface
  @Test def transitivelyMoreSpecific(): Unit = checkInlined("f", 7)
}
