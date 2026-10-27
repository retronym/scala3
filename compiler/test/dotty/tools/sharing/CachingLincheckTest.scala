package dotty.tools.sharing

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Assert.*
import org.junit.Test

/** `Caching`'s stamped cache under concurrent readers and writers, checked for linearizability by Lincheck.
 *
 *  The cell starts with the pair of an earlier period (stamp 1, value 10); every reader is in period 2, whose value
 *  is 20, so `get` returns 20 in any sequential order, and `getProvisional`, whose value -1 is not kept, returns 20
 *  once some `get` has stored it and -1 before. Model checking explores the interleavings (under sequential
 *  consistency, so it checks the order of the reads and stores, not the fences); stress runs real threads, on Apple
 *  silicon as well. `Broken` is the idiom the helper replaced, a plain value store whether kept or not and the field
 *  read again, and must fail.
 */
class CachingLincheckTest:
  import CachingLincheckTest.*

  @Test def modelChecking(): Unit =
    LinChecker.check(classOf[Cell], ModelCheckingOptions().iterations(100).threads(3).actorsPerThread(3))

  @Test def stress(): Unit =
    LinChecker.check(classOf[Cell], StressOptions().iterations(100).invocationsPerIteration(5000).threads(3).actorsPerThread(3))

  @Test def brokenIsCaught(): Unit =
    val failed =
      try { LinChecker.check(classOf[Broken], ModelCheckingOptions().iterations(100).threads(3).actorsPerThread(3)); false }
      catch case _: AssertionError => true
    assertTrue("the plain idiom should not be linearizable", failed)

object CachingLincheckTest:
  inline val Now = 2

  class Cell:
    private var stamp: Int = 1
    private var value: Int = 10

    @Operation def get(): Int =
      Caching.cache(true, stamp, value, stamp = _, value = _, (s, _) => s == Now, Now, _ => true, 20)

    @Operation def getProvisional(): Int =
      Caching.cache(true, stamp, value, stamp = _, value = _, (s, _) => s == Now, Now, _ != -1, -1)

  class Broken:
    private var stamp: Int = 1
    private var value: Int = 10

    private def cached(v: Int, keep: Boolean): Int =
      if stamp != Now then
        value = v
        if keep then stamp = Now
      value

    @Operation def get(): Int = cached(20, keep = true)

    @Operation def getProvisional(): Int = cached(-1, keep = false)
