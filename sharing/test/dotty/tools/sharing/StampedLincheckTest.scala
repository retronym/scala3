package dotty.tools.sharing

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Assert.*
import org.junit.Test

/** `Stamped`'s cache cell under concurrent readers and writers, checked for linearizability by Lincheck, as
 *  `CachingLincheckTest` checks `Caching`'s pair of fields.
 *
 *  The cell starts with the value of an earlier stamp (1, value 10); every reader is at stamp 2, whose value is 20,
 *  so `get` returns 20 in any sequential order, and `getProvisional`, whose value -1 is not kept, returns 20 once
 *  some `get` has stored it and -1 before; `peek` returns 20 once it is stored and 0 before. Model checking explores
 *  the interleavings under sequential consistency; that the pair is seen whole without fences is the JMM's guarantee
 *  for final fields, which stress runs on real threads exercise (on Apple silicon as well). `Broken`, a cell whose
 *  stamp and value are updated in place, must fail.
 */
class StampedLincheckTest:
  import StampedLincheckTest.*

  @Test def modelChecking(): Unit =
    LinChecker.check(classOf[Cell], ModelCheckingOptions().iterations(100).threads(3).actorsPerThread(3))

  @Test def stress(): Unit =
    LinChecker.check(classOf[Cell], StressOptions().iterations(100).invocationsPerIteration(5000).threads(3).actorsPerThread(3))

  @Test def emptyIsNeverCurrent(): Unit =
    val empty: Stamped[String] = Stamped.Empty
    assertEquals(0, empty.stamp)
    assertEquals("computed", Stamped.cached(empty)(_ => ())(Now)("computed"))

  @Test def brokenIsCaught(): Unit =
    val failed =
      try { LinChecker.check(classOf[Broken], ModelCheckingOptions().iterations(100).threads(3).actorsPerThread(3)); false }
      catch case _: AssertionError => true
    assertTrue("a cell updated in place should not be linearizable", failed)

object StampedLincheckTest:
  inline val Now = 2

  class Cell:
    private var cell: Stamped[Int] = Stamped(1, 10)

    @Operation def get(): Int = Stamped.cached(cell)(cell = _)(Now)(20)

    @Operation def getProvisional(): Int = Stamped.cachedIf(cell)(cell = _)(Now, _ != -1)(-1)

    @Operation def peek(): Int = Stamped.peek(cell)(Now)(0)

  class Broken:
    private var stamp: Int = 1
    private var value: Int = 10

    private def cached(v: Int, keep: Boolean): Int =
      if stamp != Now then
        stamp = Now
        value = v
        if !keep then stamp = 1
      value

    @Operation def get(): Int = cached(20, keep = true)

    @Operation def getProvisional(): Int = cached(-1, keep = false)

    @Operation def peek(): Int = if stamp == Now then value else 0
