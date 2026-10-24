package dotty.tools.pc.tests

import dotty.tools.dotc.core.Periods.*
import dotty.tools.dotc.Compiler

import org.junit.Assert.*
import org.junit.Test

class RunIdOverflowSuite:

  /** The run id occupies the top 16 bits of the period code, so id 65536 wraps to `NoRunId` and 65537 aliases the
   *  first run: a period of a later run is `contains`-equal to one of the first run.
   */
  @Test def overflowingRunIdAliasesEarlierRuns(): Unit =
    assertEquals(NoRunId, Period(MaxPossibleRunId + 1, 3).runId)
    assertEquals(InitialRunId, Period(MaxPossibleRunId + 2, 3).runId)
    assertTrue(Period(InitialRunId, 1, 5).contains(Period(MaxPossibleRunId + 2, 3)))

  @Test def compilerRefusesRunIdBeyondMax(): Unit =
    val compiler = new Compiler
    compiler.setLastRunIdForTesting(MaxPossibleRunId - 1)
    assertEquals(MaxPossibleRunId, compiler.nextRunId)
    assertFalse(compiler.hasMoreRunIds)
    try
      compiler.nextRunId
      fail("expected run id overflow to be detected")
    catch case _: AssertionError => ()
