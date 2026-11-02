package dotty.tools.pc.tests

import java.io.File
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}

import scala.language.unsafeNulls

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.pc.base.TestResources

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

/** The lazy trees of a TASTy file (`ValDef.rhs`, `DefDef.rhs`, `Template.body`) and the trees `-Yretain-trees` keeps
 *  were read outside the file's claim, and each reader that forced one read it again: the local symbols of the
 *  second reading are registered over the first's in the unpickler, and the readers hold different trees.
 *
 *  Eight threads ask for the root tree of a class from the library, then for its template's body and the right-hand
 *  sides of its members, at once; they must all get the same trees.
 */
class LazyTreesSuite:
  @Test def `lazy-trees-are-read-once`(): Unit =
    val settings = List("-color:never", "-Yinteractive-parallel", "-Yretain-trees",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    given ctx: Context = driver.currentCtx
    val names = List(
      "scala.collection.immutable.List", "scala.collection.immutable.Vector", "scala.Option", "scala.util.Either",
      "scala.collection.immutable.Map", "scala.collection.immutable.Set", "scala.collection.mutable.ArrayBuffer",
      "scala.collection.immutable.Range", "scala.collection.immutable.LazyList", "scala.collection.immutable.Queue",
      "scala.collection.mutable.HashMap", "scala.collection.immutable.TreeMap", "scala.util.Try", "scala.math.BigInt")
    var withTrees = 0
    var split = 0
    val threads = 8
    for name <- names do
      val cls = getClassIfDefined(name)
      if cls.exists then
        val start = new CountDownLatch(1)
        val seen = new ConcurrentLinkedQueue[List[AnyRef]]()
        val ts = (0 until threads).map { _ =>
          val t = new Thread((() =>
            start.await()
            given Context = ctx.fresh
            val root = cls.asClass.rootTree
            val parts = List.newBuilder[AnyRef]
            parts += root
            root.foreachSubTree:
              case tmpl: Template =>
                parts += tmpl.body
                tmpl.body.foreach:
                  case d: ValOrDefDef => parts += d.rhs
                  case _ =>
              case _ =>
            seen.add(parts.result())): Runnable)
          t.start(); t
        }
        start.countDown()
        ts.foreach(_.join())
        val all = seen.toArray.toList.asInstanceOf[List[List[AnyRef]]]
        if all.nonEmpty && all.head.head.isInstanceOf[Tree] && !all.head.head.asInstanceOf[Tree].isEmpty then withTrees += 1
        val first = all.head
        if all.exists(l => l.size != first.size || l.zip(first).exists((a, b) => a ne b)) then split += 1
    assertTrue("some class has a TASTy tree on the classpath", withTrees > 0)
    assertEquals("classes whose lazy trees two readers got different copies of", 0, split)
