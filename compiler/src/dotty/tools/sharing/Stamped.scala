package dotty.tools.sharing

/** A value with the stamp it was computed at, as one immutable object: the cell of a cache held in one field, where
 *  `Caching` keeps two (docs/_docs/internals/interactive-multi-reader.md §4.4.1).
 *
 *  Both fields are final, so a reader that loads the reference sees the pair whole, however it was published: no
 *  fences, no test of a `Mode`, and the code is the same with one thread or several. A writer replaces the cell; a
 *  reader can never see a current stamp with an older value, nor a stamp and value from two writers. The price is an
 *  allocation per miss and a dependent load per hit (the owner's field, then the cell's).
 *
 *  A stamp is an `Int` (in dotc, a period's `rawCode` or a run id), never `0` for a current one: `Stamped.Empty`, the
 *  cell a field starts with, has stamp `0`.
 */
final class Stamped[+T](val stamp: Int, val value: T):
  override def toString: String = s"Stamped($stamp, $value)"

object Stamped:

  /** The cell of a cache not yet computed: stamp `0`, which is never current. */
  // a cast of the cell, not of `null` to `Nothing`, which throws
  val Empty: Stamped[Nothing] = Stamped[Null](0, null).asInstanceOf[Stamped[Nothing]]

  /** The value of the cache in field `cell`, valid while its stamp is `now`; on a miss the value `compute`d, stored
   *  with stamp `now` in a new cell, and returned.
   *  {{{
   *  private var myBaseClasses: Stamped[List[ClassSymbol]] = Stamped.Empty
   *  override final def baseClasses(using Context): List[ClassSymbol] =
   *    Stamped.cached(myBaseClasses)(myBaseClasses = _)(ctx.period.rawCode):
   *      ... // the base classes, computed
   *  }}}
   *  Expands to
   *  {{{
   *  val n = ctx.period.rawCode
   *  val c = myBaseClasses
   *  if c.stamp == n then c.value
   *  else
   *    val v = ... // the base classes, computed
   *    myBaseClasses = Stamped(n, v)
   *    v
   *  }}}
   */
  inline def cached[T](inline cell: Stamped[T])(inline setCell: Stamped[T] => Unit)(inline now: Int)(
      inline compute: T): T =
    cachedIf(cell)(setCell)(now, _ => true)(compute)

  /** `cached`, storing the value computed only when `keep` holds of it (it is not provisional, say), and leaving the
   *  old cell otherwise: it is still a correct one, for its own stamp.
   *  {{{
   *  // OrType.atoms: a provisional type's atoms may change, so they are not kept
   *  Stamped.cachedIf(myAtoms)(myAtoms = _)(ctx.runId, _ => !isProvisional):
   *    computeAtoms()
   *  }}}
   *  Expands as `cached` does, the store under `if keep(v)`.
   */
  inline def cachedIf[T](inline cell: Stamped[T])(inline setCell: Stamped[T] => Unit)(
      inline now: Int, inline keep: T => Boolean)(inline compute: T): T =
    val n = now
    val c = cell
    if c.stamp == n then c.value
    else
      val v = compute
      if keep(v) then setCell(Stamped(n, v))
      v

  /** The value of the cache in field `cell` if its stamp is `now`, `orElse` otherwise: a read that neither computes
   *  nor stores.
   */
  inline def peek[T](inline cell: Stamped[T])(inline now: Int)(inline orElse: T): T =
    val c = cell
    if c.stamp == now then c.value else orElse
