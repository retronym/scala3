package dotty.tools.pc.tests

import java.io.File
import java.net.URI
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.language.unsafeNulls

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.toTypeName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** Package scopes written by readers (docs/_docs/internals/interactive-multi-reader.md §4.4, shape 4; the state map's
 *  `MutableScope` items). A first lookup of a synthesized member of `scala` used to make and enter a symbol on each
 *  reader that missed, so readers held different symbols for one name; a lookup of a flat name (`C$D`) entered a
 *  package's flat classes into its scope in place, rehashing it under readers looking up other names.
 */
class ScopeRacesSuite:

  private def newDriver(): InteractiveDriver =
    val settings = List("-color:never", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    driver.run(new URI("file:///ScopeRaces.scala"), "object ScopeRaces")
    driver

  /** `op` on `n` threads started together, each in its own context; the results in thread order, and the failures. */
  private def together[T](n: Int)(op: Context => T)(using ctx: Context): (IndexedSeq[T | Null], List[Throwable]) =
    val start = CountDownLatch(1)
    val results = new Array[Any](n)
    val failures = ConcurrentLinkedQueue[Throwable]()
    val threads = (0 until n).map { i =>
      new Thread((() =>
        val c = ctx.fresh
        start.await()
        try results(i) = op(c)
        catch { case e: Exception => failures.add(e); case e: Error => failures.add(e) }): Runnable)
    }
    threads.foreach(_.start())
    start.countDown()
    threads.foreach(_.join())
    (results.toIndexedSeq.asInstanceOf[IndexedSeq[T | Null]], failures.toArray.toList.asInstanceOf[List[Throwable]])

  @Test def `a synthesized member of scala has one symbol`(): Unit =
    val driver = newDriver()
    given ctx: Context = driver.currentCtx
    val scope = defn.ScalaPackageClass.info.decls
    // each name is synthesized here for the first time, by whichever reader gets there first
    val names = (30 until 130).map(n => s"ContextFunction$n".toTypeName) ++ (30 until 130).map(n => s"Function$n".toTypeName)
    var split = 0
    for name <- names do
      val (syms, failures) = together(8)(c => scope.lookup(name)(using c))
      assertEquals(s"failures looking up $name: $failures", Nil, failures)
      if syms.distinct.size > 1 then split += 1
    assertEquals(s"names with more than one symbol, of ${names.size}", 0, split)

  @Test def `lookups during a flat-class fill find what the package holds`(): Unit =
    val known = List("ArrayList", "HashMap", "List", "Map", "Set", "Collections", "Arrays", "Optional", "Objects",
      "Iterator", "LinkedList", "TreeMap", "HashSet", "Deque", "Comparator", "Locale", "Random", "Scanner")
    val misses = AtomicInteger()
    val failures = ConcurrentLinkedQueue[Throwable]()
    for round <- 1 to 8 do
      val driver = newDriver()
      given ctx: Context = driver.currentCtx
      val scope = requiredPackage("java.util").moduleClass.info.decls // the package's classes, not its flat ones
      val names = known.map(_.toTypeName)
      assertEquals("known names before the fill", Nil, names.filterNot(n => scope.lookup(n).exists))
      val filled = AtomicBoolean(false)
      val start = CountDownLatch(1)
      val filler = new Thread((() =>
        val c = ctx.fresh
        start.await()
        try scope.lookup("AbstractMap$SimpleEntry".toTypeName)(using c) // enters the flat classes
        catch { case e: Exception => failures.add(e); case e: Error => failures.add(e) }
        finally filled.set(true)): Runnable)
      val readers = (0 until 7).map { _ =>
        new Thread((() =>
          val c = ctx.fresh
          start.await()
          try
            while !filled.get do
              for n <- names do if !scope.lookup(n)(using c).exists then misses.incrementAndGet()
          catch { case e: Exception => failures.add(e); case e: Error => failures.add(e) }): Runnable)
      }
      (filler +: readers).foreach(_.start())
      start.countDown()
      (filler +: readers).foreach(_.join())
    assertEquals(s"failures: ${failures.toArray.toList}", 0, failures.size)
    assertEquals("lookups of a class the package holds that missed during the fill", 0, misses.get)
