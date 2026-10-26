package dotty.tools
package dotc
package core

import java.lang.invoke.VarHandle

import Contexts.*

/** Caches on shared objects that pair a value with a stamp, the period or run id the value was computed in
 *  (docs/_docs/internals/interactive-multi-reader.md §4.4.1).
 *
 *  Each helper is an `inline def` that compiles to the plain read, compare, compute and store, with fences under a
 *  test of a final field of the base (`ctx.base.concurrent`); a batch call site allocates nothing. On a concurrent base
 *  the stamp is read first, with acquire semantics, then the value; a writer stores the value, then the stamp, with
 *  release semantics. Readers in one run share a period, so a reader that finds the stamp current sees a value
 *  computed for it, never a previous one or the default (I36, I60, I78).
 *
 *  The ordering is by fences (`VarHandle.acquireFence`, `releaseFence`), not handles on the fields, so that one
 *  helper serves any pair of fields: a `VarHandle` the JIT folds must be a `static final` constant, one per field.
 */
object Caching:

  /** The value of the cache in fields `stamp` and `value`, valid while the stamp is `now`; on a miss the value
   *  `compute`d, stored with stamp `now`, and returned. The value returned is the local one, never the field read
   *  again.
   *  {{{
   *  private var myBaseClassesPeriod: Period = Nowhere
   *  private var myBaseClasses: List[ClassSymbol] = uninitialized
   *  override final def baseClasses(using Context): List[ClassSymbol] =
   *    Caching.stamped(myBaseClassesPeriod, myBaseClasses)(myBaseClassesPeriod = _, myBaseClasses = _)(ctx.period):
   *      ... // the base classes, computed
   *  }}}
   *  Expands to (`cache`, with `isConcurrent` the base's `concurrent`, read once)
   *  {{{
   *  val s = myBaseClassesPeriod
   *  if isConcurrent then VarHandle.acquireFence()
   *  val v = myBaseClasses
   *  if s == ctx.period then v
   *  else
   *    val v1 = compute
   *    if isConcurrent then VarHandle.releaseFence()
   *    myBaseClasses = v1
   *    if isConcurrent then VarHandle.releaseFence()
   *    myBaseClassesPeriod = ctx.period
   *    v1
   *  }}}
   *  On a batch base, the plain read, compare, compute and store it replaced. The fields must be touched nowhere
   *  else but by a reset of the stamp (`StampedCachesTest` checks it).
   */
  inline def stamped[S, T](inline stamp: S, inline value: T)(inline setStamp: S => Unit, inline setValue: T => Unit)(
      inline now: S)(inline compute: T)(using c: Context): T =
    cache(c.base.concurrent, stamp, value, setStamp, setValue, (s, _) => s == now, now, _ => true, compute)

  /** `stamped`, storing the value computed only when `keep` holds of it (it is not provisional, say), and leaving
   *  the old pair otherwise: it is still a correct one, for its own stamp.
   *  {{{
   *  // AppliedType.isStable: a provisional type's stability may change, so it is not kept
   *  Caching.stampedIf(myIsStableRunId, myIsStable)(myIsStableRunId = _, myIsStable = _)(ctx.runId, _ => !isProvisional):
   *    computeIsStable
   *  }}}
   *  Expands as `stamped` does, the two stores under `if keep(v1)`.
   */
  inline def stampedIf[S, T](inline stamp: S, inline value: T)(inline setStamp: S => Unit, inline setValue: T => Unit)(
      inline now: S, inline keep: T => Boolean)(inline compute: T)(using c: Context): T =
    cache(c.base.concurrent, stamp, value, setStamp, setValue, (s, _) => s == now, now, keep, compute)

  /** `stamped`, with a hit when `isCurrent(stamp, value)`, the stamp read first: for a validity wider than one
   *  period, or a value an invalidation may null.
   *  {{{
   *  // ClassDenotation.baseTypeCache: valid while the base types are the same, nulled by an invalidation
   *  Caching.stampedWhen(myBaseTypeCachePeriod, myBaseTypeCache)(myBaseTypeCachePeriod = _, myBaseTypeCache = _)(
   *      (period, cache) => cache != null && currentHasSameBaseTypesAs(period), ctx.period):
   *    ctx.base.sharing.newEqHashMap()
   *  }}}
   *  Expands as `stamped` does, with `isCurrent(s, v)` for `s == now`.
   */
  inline def stampedWhen[S, T](inline stamp: S, inline value: T)(inline setStamp: S => Unit, inline setValue: T => Unit)(
      inline isCurrent: (S, T) => Boolean, inline now: S)(inline compute: T)(using c: Context): T =
    cache(c.base.concurrent, stamp, value, setStamp, setValue, isCurrent, now, _ => true, compute)

  /** The helpers' code, `concurrent` the base's (a parameter for `CachingLincheckTest`). */
  private[core] inline def cache[S, T](
      inline concurrent: Boolean, inline stamp: S, inline value: T, inline setStamp: S => Unit, inline setValue: T => Unit,
      inline isCurrent: (S, T) => Boolean, inline now: S, inline keep: T => Boolean, inline compute: T): T =
    // one copy of the code, the fences under the test: the batch and concurrent branches differ only by them, and
    // two copies would double the bytecode of every site (the JIT inlines by bytecode size)
    val isConcurrent = concurrent
    val s = stamp
    if isConcurrent then VarHandle.acquireFence()
    val v = value
    if isCurrent(s, v) then v
    else
      val v1 = compute
      if keep(v1) then
        // the release before the value orders the value's construction before it: a second writer in the same
        // period stores its value after the stamp is already current
        if isConcurrent then VarHandle.releaseFence()
        setValue(v1)
        if isConcurrent then VarHandle.releaseFence()
        setStamp(now)
      v1

  /** The value of the cache in fields `stamp` and `value` if the stamp is `now`, `orElse` otherwise: a read that
   *  neither computes nor stores.
   *  {{{
   *  // NamedType.currentSignature: the signature if known, without forcing a denotation
   *  Caching.peek(mySignatureRunId, mySignature)(ctx.runId):
   *    ... // the signature from what is known now
   *  }}}
   *  Expands to `val s = stamp; if concurrent then VarHandle.acquireFence(); if s == now then value else orElse`.
   */
  inline def peek[S, T](inline stamp: S, inline value: T)(inline now: S)(inline orElse: T)(using Context): T =
    val s = stamp
    if ctx.base.concurrent then VarHandle.acquireFence()
    if s == now then value else orElse
