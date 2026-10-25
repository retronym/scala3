package dotty.tools.pc.tests

import java.io.File
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}

import scala.language.unsafeNulls

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Scopes.newScope
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.pc.base.TestResources

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

/** Finding I93 (docs/_docs/internals/interactive-multi-reader.md §8):
 *  `Type.testProvisional` broke cycles by clearing the type's own
 *  `mightBeProvisional` while it tested the prefix and the info of a `TypeRef`
 *  (and the underlying type of a `ParamRef`), a sentinel in a field every
 *  reader shares. Another reader asking `isProvisional` meanwhile got `false`
 *  for a provisional type, and the caches that skip provisional values kept it.
 *
 *  A fresh class per round with `type T >: Nothing <: LazyRef(...)`, the lazy
 *  reference never forced, so `C.this.T` is provisional; eight threads ask at once.
 */
class ProvisionalSentinelSuite:
  @Test def `is-provisional-from-several-threads`(): Unit =
    val settings = List("-color:never", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    given ctx: Context = driver.currentCtx
    def provisionalRef(i: Int): TypeRef =
      val cls = newCompleteClassSymbol(defn.EmptyPackageClass, typeName(s"P$i"), EmptyFlags, List(defn.ObjectType), newScope)
      val bounds = TypeBounds(defn.NothingType, LazyRef(_ => defn.AnyType))
      TypeRef(cls.thisType, newSymbol(cls, typeName("T"), Deferred, bounds).entered)
    assertTrue("one thread finds the type provisional", provisionalRef(-1).isProvisional)
    val threads = 8
    var wrong = 0
    for i <- 0 until 2000 do
      val tref = provisionalRef(i)
      val start = new CountDownLatch(1)
      val seen = new ConcurrentLinkedQueue[java.lang.Boolean]()
      val ts = (0 until threads).map { _ =>
        val t = new Thread((() =>
          start.await()
          seen.add(tref.isProvisional(using ctx.fresh))): Runnable)
        t.start(); t
      }
      start.countDown()
      ts.foreach(_.join())
      if seen.contains(java.lang.Boolean.FALSE) then wrong += 1
    assertEquals("rounds in which a thread found the provisional type not provisional", 0, wrong)
