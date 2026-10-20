package dotty.tools.backend.jvm

import org.junit.Assert.*
import org.junit.Test

import scala.jdk.CollectionConverters.*
import dotty.DottyBytecodeTest

/** Compiled with -Yno-fuse-phases. Fusion is an optimization only, so the code should be the
 *  same as in a normal compile; these are cases where a mini-phase depended on it.
 */
class UnfusedPhasesBytecodeTests extends DottyBytecodeTest {
  override def initCtx = {
    val ctx0 = super.initCtx
    ctx0.setSetting(ctx0.settings.YnoFusePhases, true)
  }

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
