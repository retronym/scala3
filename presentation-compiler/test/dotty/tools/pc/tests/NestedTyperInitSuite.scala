package dotty.tools.pc.tests

import java.io.File
import java.util.concurrent.{CountDownLatch, ConcurrentLinkedQueue}

import scala.language.unsafeNulls

import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.typer.Typer
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** Finding I90 (docs/_docs/internals/interactive-multi-reader.md §8): the
 *  first use of a typer's `nestedTyper` map, on two readers at once, made a
 *  map in each and kept one; the symbols entered in the other were lost
 *  ("None.get" from `Typer.localTyper`).
 */
class NestedTyperInitSuite:
  @Test def `first-use-from-several-threads-makes-one-map`(): Unit =
    val settings = List("-color:never", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    val ctx = driver.currentCtx
    val threads = 8
    var distinct = 0
    for _ <- 0 until 3000 do
      val typer = new Typer
      val start = new CountDownLatch(1)
      val seen = new ConcurrentLinkedQueue[Int]()
      val ts = (0 until threads).map { _ =>
        val t = new Thread((() => { start.await(); seen.add(System.identityHashCode(typer.nestedTyper(using ctx))) }): Runnable)
        t.start(); t
      }
      start.countDown()
      ts.foreach(_.join())
      if seen.stream.distinct.count != 1 then distinct += 1
    assertEquals("typers whose threads saw different maps", 0, distinct)
