package dotty.tools.dotc.util

import org.junit.Test
import org.junit.Assert.assertEquals

class BestFitTest:

  // Reference implementation: the last index whose candidate is <= x, or -1
  def oracle(candidates: Array[Int], length: Int, x: Int): Int =
    var i = length - 1
    while i >= 0 && x < candidates(i) do i -= 1
    i

  @Test def matchesLinearScan(): Unit =
    val rnd = new scala.util.Random(42)
    for _ <- 0 until 100000 do
      val arr = Array.fill(rnd.nextInt(40))(rnd.nextInt(100)).sorted.distinct
      // also exercise `length` < `candidates.length`
      val length = if arr.isEmpty then 0 else rnd.nextInt(arr.length + 1)
      val x = rnd.nextInt(110) - 5
      // includes out-of-range hints, which must be ignored
      val hint = rnd.nextInt(length + 4) - 2
      assertEquals(
        s"candidates = ${arr.take(length).toList}, x = $x, hint = $hint",
        oracle(arr, length, x), Util.bestFit(arr, length, x, hint))
