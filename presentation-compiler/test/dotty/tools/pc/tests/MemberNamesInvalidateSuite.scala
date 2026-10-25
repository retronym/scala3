package dotty.tools.pc.tests

import java.io.File
import java.net.URI
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicBoolean

import scala.language.unsafeNulls

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.SymDenotations.{BaseData, MemberNames}
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.takeAllFilter
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** Finding I92 (docs/_docs/internals/interactive-multi-reader.md §8): a class's
 *  member names cache was read twice (`if !memberNamesCache.isValid then ...;
 *  memberNamesCache(keepOnly, this)`), and its `apply` asserted that it was
 *  still valid, so a reader whose cache another reader invalidated meanwhile
 *  (a superclass's members changed: `invalidateDependents`) applied
 *  `MemberNames.None` (`???`) or failed the assertion. The base data cache's
 *  `apply` asserted the same.
 */
class MemberNamesInvalidateSuite:
  @Test def `member-names-while-another-reader-invalidates`(): Unit =
    val settings = List("-color:never", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    val uri = new URI("file:///MemberNames.scala")
    driver.run(uri, "class A { def a = 1 }\nclass B extends A { def b = 2 }\nclass C extends B { def c = 3 }")
    given ctx: Context = driver.currentCtx
    val a = requiredClass("A")
    val c = requiredClass("C")
    val failed = new ConcurrentLinkedQueue[Throwable]()
    val stop = new AtomicBoolean(false)
    val start = new CountDownLatch(1)
    val readers = (0 until 6).map { _ =>
      new Thread((() =>
        start.await()
        val rctx = ctx.fresh
        while !stop.get do
          try
            c.classDenot.memberNames(takeAllFilter)(using MemberNames.None, rctx)
            c.classDenot.baseClasses(using BaseData.None, rctx)
          catch
            case e: Exception => failed.add(e)
            case e: Error => failed.add(e)): Runnable)
    }
    val invalidator = new Thread((() =>
      start.await()
      val ictx = ctx.fresh
      while !stop.get do
        a.classDenot.invalidateMemberCaches()(using ictx)
        c.classDenot.invalidateMemberCaches()(using ictx)
        a.classDenot.invalidateBaseDataCache()
        c.classDenot.invalidateBaseDataCache()): Runnable)
    (invalidator +: readers).foreach(_.start())
    start.countDown()
    Thread.sleep(3000)
    stop.set(true)
    (invalidator +: readers).foreach(_.join())
    val kinds = failed.toArray.map(_.getClass.getSimpleName).distinct.mkString(", ")
    assertEquals(s"memberNames or baseClasses failures while invalidated ($kinds)", 0, failed.size)
