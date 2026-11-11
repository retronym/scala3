package dotty.tools.dotc.util

object Util {

  /** The last index `i < length` such that `candidates(i) <= x`, or -1 if `x < candidates(0)`.
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
        if x < candidates(hint) then
          hi = hint
          while step < hi && x < candidates(hi - step) do
            hi -= step
            step <<= 1
          lo = if step < hi then hi - step else 0
        else
          lo = hint
          while step < length - lo && candidates(lo + step) <= x do
            lo += step
            step <<= 1
          hi = if step < length - lo then lo + step else length
      while hi - lo > 1 do
        val mid = (lo + hi) >>> 1
        if x < candidates(mid) then hi = mid
        else lo = mid
      lo
}
