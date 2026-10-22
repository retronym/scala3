package dotty.tools.dotc.util

object Util {

  /** The index `i` in `candidates.indices` such that `candidates(i) <= x` and
   *  `candidates(i)` is closest to `x`, determined by binary search, or -1
   *  if `x < candidates(0)`.
   *  @param  hint   If between 0 and `length`, gallop outwards from `hint`
   *                 before searching, so that a result `d` positions away
   *                 from `hint` is found with O(log d) probes.
   *  @pre   candidates is sorted
   */
  def bestFit(candidates: Array[Int], length: Int, x: Int, hint: Int = -1): Int =
    if length == 0 || x < candidates(0) then -1
    else
      // invariant: candidates(lo) <= x && (hi == length || x < candidates(hi))
      var lo = 0
      var hi = length
      if 0 <= hint && hint < length then
        var step = 1
        var galloping = true
        if x < candidates(hint) then
          hi = hint
          while galloping do
            if hi <= step then galloping = false
            else if candidates(hi - step) <= x then
              lo = hi - step
              galloping = false
            else
              hi -= step
              step <<= 1
        else
          lo = hint
          while galloping do
            if length - lo <= step then galloping = false
            else if x < candidates(lo + step) then
              hi = lo + step
              galloping = false
            else
              lo += step
              step <<= 1
      while hi - lo > 1 do
        val mid = (lo + hi) >>> 1
        if x < candidates(mid) then hi = mid
        else lo = mid
      lo
}
