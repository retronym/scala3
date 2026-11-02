package dotty.tools.pc.tests

import scala.language.unsafeNulls

import dotty.tools.sharing.AnyConcurrent
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.pc.base.TestResources

import org.junit.Assert.{assertFalse, assertTrue}
import org.junit.Test

/** `AnyConcurrent.get()` is false until a concurrent base exists in the JVM, and true from then on. Run on
 *  its own (a fresh JVM): another suite's concurrent base would already have flipped it. */
class AlmostFinalConcurrencySuite:
  @Test def flipsWithTheFirstConcurrentBase(): Unit =
    assertFalse("no concurrent base yet", AnyConcurrent.get())
    val settings = List("-color:never", "-Yinteractive-parallel", "-classpath",
      TestResources.classpath.map(_.toString).mkString(java.io.File.pathSeparator))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    assertTrue("a concurrent base exists", AnyConcurrent.get())
    assertTrue(driver.currentCtx.base.concurrent)
