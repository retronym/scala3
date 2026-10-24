package dotty.tools.pc.tests

import java.net.URI

import dotty.tools.dotc.core.Periods.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}

import org.junit.Assert.*
import org.junit.Test

class RunIdOverflowSuite:

  private def newDriver() =
    new InteractiveDriver(List("-classpath", System.getProperty("java.class.path")), CachedLogicalPackage.none)

  /** The run id occupies the top 16 bits of the period code, so id 65536 wraps to `NoRunId` and 65537 aliases the
   *  first run: a period of a later run is `contains`-equal to one of the first run.
   */
  @Test def overflowingRunIdAliasesEarlierRuns(): Unit =
    assertEquals(NoRunId, Period(MaxPossibleRunId + 1, 3).runId)
    assertEquals(InitialRunId, Period(MaxPossibleRunId + 2, 3).runId)
    assertTrue(Period(InitialRunId, 1, 5).contains(Period(MaxPossibleRunId + 2, 3)))

  @Test def compilerRefusesRunIdBeyondMax(): Unit =
    val driver = newDriver()
    val compiler = driver.compilerForTesting
    compiler.setLastRunIdForTesting(MaxPossibleRunId - 1)
    assertEquals(MaxPossibleRunId, compiler.nextRunId)
    assertFalse(compiler.hasMoreRunIds)
    try
      compiler.nextRunId
      fail("expected run id overflow to be detected")
    catch case _: AssertionError => ()

  /** Without the driver restart, this sequence fails with
   *  `assertion failed: denotation class Object invalid in run 4. ValidFor: Period(5.1-2)`:
   *  the run after the wrap has id 4 (aliasing the run that compiled B) while denotations
   *  already carry validity from run 5.
   */
  @Test def driverSwitchesBaseBeforeExhaustingRunIds(): Unit =
    val driver = newDriver()
    val uriA = new URI("file:///A.scala")
    val uriB = new URI("file:///B.scala")
    val uriC = new URI("file:///C.scala")
    driver.run(uriA, "class A { def x: Int = 1 }")
    driver.run(uriB, "object B { val i: Int = new A().x }")
    val runOfB = driver.currentCtx.runId
    driver.run(uriA, "class A { def x: String = \"s\" }")
    val oldBase = driver.baseForTesting
    driver.compilerForTesting.setLastRunIdForTesting(runOfB + MaxPossibleRunId)
    val diags = driver.run(uriC, "object C { val s: String = new A().x }")
    assertTrue(diags.toString, diags.isEmpty)
    assertNotSame(oldBase, driver.baseForTesting)
    assertTrue(driver.compilerForTesting.hasMoreRunIds)
    assertEquals(Set(uriA, uriB, uriC), driver.openedTrees.keySet)
    assertEquals(driver.baseForTesting, driver.currentCtx.base)
