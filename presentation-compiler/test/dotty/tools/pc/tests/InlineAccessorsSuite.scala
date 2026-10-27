package dotty.tools.pc.tests

import java.io.File
import java.net.URI

import scala.language.unsafeNulls

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.NameKinds.InlineAccessorName
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** In the IDE an inline method's references to non-public members stay as written: no inline accessor is entered in
 *  the class, which other readers may already see, and calls of the method still type without errors.
 */
class InlineAccessorsSuite:

  private val source =
    """package p
      |class A:
      |  private var x = 1
      |  protected def y = 2
      |  inline def f = x + y
      |  inline def g(a: A) = a.x
      |  inline def set(i: Int) = x = i
      |object B:
      |  def h(a: A): Int = a.f + a.g(a)
      |  def k(a: A): Unit = a.set(3)
      |""".stripMargin

  @Test def `no inline accessors in the IDE`(): Unit =
    val driver = new InteractiveDriver(List("-color:never",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator)), CachedLogicalPackage.none)
    val diags = driver.run(new URI("file:///InlineAccessors.scala"), source)
    assertEquals("diagnostics", Nil, diags.map(_.message))
    given Context = driver.currentCtx
    val accessors = requiredClass("p.A").info.decls.toList.filter(_.name.unexpandedName.toTermName.is(InlineAccessorName))
    assertEquals("inline accessors entered in A", Nil, accessors)
