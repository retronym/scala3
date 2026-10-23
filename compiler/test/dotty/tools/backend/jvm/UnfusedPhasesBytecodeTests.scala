package dotty.tools.backend.jvm

import org.junit.Assert.*
import org.junit.Test

import scala.jdk.CollectionConverters.*
import dotty.DottyBytecodeTest
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.core.Contexts.{Context, ContextBase, ctx}

/** Compiled with every miniphase in a traversal of its own (the research plugin
 *  `plugins.UnfusedPhases`). Fusion is an optimization, so the code should be the same as in a
 *  normal compile, up to fresh names and the order of synthetic members; these are cases where a
 *  mini-phase depended on it.
 */
class UnfusedPhasesBytecodeTests extends DottyBytecodeTest {
  override protected def newContextBase: ContextBase = dotty.tools.dotc.plugins.UnfusedPhases.contextBase

  @Test
  def thePluginUnfusesThePhases(): Unit =
    // without it the tests below would pass on a fused compile too, saying nothing
    given Context = initCtx
    new Compiler().newRun.compileFromStrings(List("class C"))
    // a group of one is not wrapped in a MegaPhase: a fused compile has several MegaPhases, an unfused one none
    val fused = ctx.base.allPhases.toList.collect { case m: dotty.tools.dotc.transform.MegaPhase if m.miniPhases.length > 1 => m.phaseName }
    assertTrue(s"some phases are still fused: $fused", fused.isEmpty)

  @Test
  def localLazyValHolderIsNotBoxed(): Unit = {
    val code =
      """class C {
        |  def f(n: Int): Int = {
        |    lazy val x = n + 1
        |    x
        |  }
        |}
      """.stripMargin

    checkBCode(code) { dir =>
      val c = loadClassNode(lookupClass(dir, "C.class"))
      val descs = c.methods.asScala.filter(_.name.startsWith("x$")).map(m => m.name -> m.desc).toList.sorted
      assertEquals(List("x$1" -> "(Lscala/runtime/LazyInt;I)I", "x$lzyINIT1$1" -> "(Lscala/runtime/LazyInt;I)I"), descs)
    }
  }
}
