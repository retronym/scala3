package dotty.tools.dotc.core

import dotty.tools.DottyTest
import dotty.tools.dotc.core.Symbols.defn
import dotty.tools.dotc.core.Types.{LazyRef, Type}

import org.junit.Test
import org.junit.Assert.{assertEquals, assertTrue, fail}

class LazyRefUnwindTest extends DottyTest:

  // A computation that unwinds (a cancellation, a back-off, a crash) must leave the reference as it found it, so that
  // the next read computes again instead of taking the half-started computation for a cycle.
  @Test def unwoundComputationIsRetried(): Unit =
    var calls = 0
    val ref = LazyRef.of {
      calls += 1
      if calls == 1 then throw RuntimeException("unwound")
      defn.IntType
    }
    try
      ref.ref
      fail("the first computation should have unwound")
    catch case ex: RuntimeException => assertEquals("unwound", ex.getMessage)
    assertTrue("nothing is published by the unwound computation", !ref.completed && !ref.evaluating)
    assertEquals(defn.IntType, ref.ref: Type)
    assertEquals(2, calls)
