package dotty.tools.dotc.util

object Util {

  /** The index `i` in `candidates.indices` such that `candidates(i) <= x` and
   *  `candidates(i)` is closest to `x`, determined by binary search, or -1
   *  if `x < candidates(0)`.
   *  @param  hint   If between 0 and `length`, first probe `hint` and
   *                 `hint + 1`, so that a result at or just after `hint`
   *                 is found in constant time.
   *  @pre   candidates is sorted
   */
  def bestFit(candidates: Array[Int], length: Int, x: Int, hint: Int = -1): Int =
    if length == 0 || x < candidates(0) then -1
    else
      // invariant: candidates(lo) <= x && (hi == length || x < candidates(hi))
      var lo = 0
      var hi = length
      if 0 <= hint && hint < length then
        if x < candidates(hint) then hi = hint
        else
          lo = hint
          if hint + 1 < length then
            if x < candidates(hint + 1) then hi = hint + 1
            else lo = hint + 1
      while hi - lo > 1 do
        val mid = (lo + hi) >>> 1
        if x < candidates(mid) then hi = mid
        else lo = mid
      lo
}
