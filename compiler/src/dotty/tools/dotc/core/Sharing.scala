package dotty.tools
package dotc
package core

import scala.collection.mutable

import Contexts.*

/** How the symbol table of a `ContextBase` is shared between threads, chosen when the base is made and never changed.
 *
 *  - `Sharing.Batch`: one thread, no locking: the batch compiler (and the default).
 *  - `Sharing.Serialised`: coarse-grained. Several request threads and one completion lock (`completionLock`)
 *    that read actions also take, so one reader types at a time (K2's global resolve lock; step 3 of
 *    docs/_docs/internals/interactive-multi-reader.md).
 *  - `Sharing.Parallel`: fine-grained. A claim per completion root (`completionRoots`) and read actions in
 *    parallel: a completion's intermediate state is seen only by its reader, and others wait for it (IntelliJ's
 *    parallel read actions, K2's per-declaration locks; `-Yinteractive-parallel`, step 4).
 *  - `Sharing.ParallelGlobalLock`: `Parallel` with one key for every root (`-Yinteractive-completion-lock:global`):
 *    one completion at a time, bodies typed in parallel, no wait-for cycles between readers. A measurement mode.
 *
 *  A concurrent sharing is only ever made by the interactive driver's base.
 *
 *  The operations are those of `dotty.tools.sharing.Sharing`, which depends on nothing in dotc, re-exported by the
 *  companion; the base supplies their `Mode` and `ClaimRegistry` givens (`dotc.sharingMode`, `dotc.claimRegistry`).
 *  Every operation that takes code to run is an `inline def` whose batch branch is that code as it was before
 *  concurrency: a batch call site compiles to a test of a final field of the base and the original expression, and
 *  allocates nothing. `privately`, which settles typer states, stays here.
 *
 *  Which operation, by what the state is:
 *
 *  - a lazy fact, computed once by one writer while other readers wait for it: `once` (a value read lock free once
 *    it is there), `claim` (anything else), with a `ClaimKind` given beside the key's class and, for re-entry, a
 *    `BlackHole`. The writer's state is reached through methods that take the claim's evidence, `Holds[key.type]`.
 *  - a short critical section over a collection or a few fields: a `Guard` whose fields are `Guarded`, or
 *    `serialised` and `locking` on another object's monitor. The guarded state is reached with `Monitor[lock.type]`.
 *  - a value paired with the period or run it was computed in: `Stamped`, one field holding both.
 *  - typing that must not write what readers share: `privately`.
 */
final class Sharing private (val concurrent: Boolean, val parallel: Boolean, val globalCompletionLock: Boolean = false):
  assert(concurrent || !parallel)
  assert(parallel || !globalCompletionLock)

  /** The library's mode, held by the base (`ContextState.mode`) for the operations' given. */
  val mode: _root_.dotty.tools.sharing.Mode = _root_.dotty.tools.sharing.Mode(concurrent)

  // ---- construction-time choices: called once per base, run, cache or class, never per access

  /** An identity hash map: the synchronised one when readers are concurrent. */
  def newEqHashMap[K, V](initialCapacity: Int = 8): util.EqHashMap[K, V] =
    if concurrent then util.SyncEqHashMap[K, V](initialCapacity) else util.EqHashMap[K, V](initialCapacity)

  /** A mutable map shared by readers: a `TrieMap` when they are concurrent. */
  def newMap[K, V](): mutable.Map[K, V] =
    if concurrent then scala.collection.concurrent.TrieMap() else mutable.HashMap()

  // concurrent: striped, a set per stripe with a monitor of its own (`Uniques.stripes`, 1 for one set)
  def newUniques(): Uniques =
    if !concurrent then Uniques() else if Uniques.stripes == 1 then ConcurrentUniques() else Uniques.StripedUniques()
  def newAppliedUniques(): Uniques.AppliedUniques =
    if !concurrent then Uniques.AppliedUniques()
    else if Uniques.stripes == 1 then Uniques.ConcurrentAppliedUniques() else Uniques.StripedAppliedUniques()
  def newNamedTypeUniques(): Uniques.NamedTypeUniques =
    if !concurrent then Uniques.NamedTypeUniques()
    else if Uniques.stripes == 1 then Uniques.ConcurrentNamedTypeUniques() else Uniques.StripedNamedTypeUniques()

  override def toString: String =
    if globalCompletionLock then "Sharing.ParallelGlobalLock"
    else if parallel then "Sharing.Parallel" else if concurrent then "Sharing.Serialised" else "Sharing.Batch"

object Sharing:
  /** Debug mode (`-Ddotc.sharing.check`): assert, where shared state is read or written, that the current reader
   *  holds the claim that guards it, so that a reader that bypasses a claim fails where it does, with its stack,
   *  rather than corrupting the state for whoever reads it next. Off by default: one static field test per site. */
  val check: Boolean = System.getProperty("dotc.sharing.check") != null

  /** With parallel readers and `check`: fail unless this reader holds the claim on one of `keys`. */
  inline def assertClaimed(inline what: => String, keys: AnyRef*)(using c: Contexts.Context): Unit =
    if check && c.base.parallel then
      val roots = c.base.completionRoots
      if !keys.exists(roots.ownedHere) then
        throw AssertionError(s"[sharing.check] $what on ${Thread.currentThread.nn.getName} without the claim on any of ${keys.mkString(", ")}; claims held: ${roots.holdersNow}")

  export _root_.dotty.tools.sharing.Sharing.{Holds, Monitor, unguarded, unlocked, ClaimKind, claim, claimAt, once,
    BlackHole, serialised, locking, GuardedVar, Guard}

  /** The evidence a completer runs with, `Holds[denot.type]`: made only by `SymDenotation.lockAndComplete0`, which
   *  runs a completer under the denotation's `Touched` and, with several readers, its root's claim or the
   *  completion lock. A completer passes it on to one it delegates to for the same denotation.
   */
  private[core] inline def completing[K]: Holds[K] =
    unguarded("a completer runs under its denotation's Touched and its root's claim or the completion lock")

  /** A TASTy file's root (`claimAt`): its address maps are filled by its completers and its lazy reads. */
  val lazyTastyRead: ClaimKind[Any] = ClaimKind(CompletionLock.lazyTastyRead, lockWhenSerialised = true)
  val denotRing: ClaimKind[Any] = ClaimKind(CompletionLock.denotRing, lockWhenSerialised = true)

  /** `op` in `c0`, or, when readers are parallel, in a private child of `c0`'s typer state, settled when it ends
   *  (its type variables instantiated, its messages issued) and dropped: what readers share is never written by
   *  one of them (I62, I66, I70, I76).
   *  {{{
   *  // Namer: a signature typed ahead, from whichever reader forces it
   *  val mdef = Sharing.privately(ctx)(typedAheadExpr(original)).asInstanceOf[tpd.DefDef]
   *  }}}
   *  Expands to `if c0.base.parallel then SharingImpl.privately(c0, c => op(using c)) else op(using c0)`.
   */
  inline def privately[T](c0: Context)(inline op: Context ?=> T): T =
    if c0.base.parallel then interactive.SharingImpl.privately(c0, c => op(using c)) else op(using c0)

  /** The end of a typer state `c1.typerState` that was a child of `into`'s, on a concurrent base: with parallel
   *  readers settled and dropped (I76), with serialised readers committed under the completion lock. */
  def endState(c1: Context, into: Context): Unit = interactive.SharingImpl.endState(c1, into)

  val Batch: Sharing = new Sharing(concurrent = false, parallel = false)
  val Serialised: Sharing = new Sharing(concurrent = true, parallel = false)
  val Parallel: Sharing = new Sharing(concurrent = true, parallel = true)

  /** `Parallel` with every completion root the same one key (`-Yinteractive-completion-lock:global`): read actions
   *  in parallel and a completion's intermediate state hidden from other readers as with `Parallel`, but one
   *  completion at a time, so no wait-for cycle between readers can form. For measuring what the key per root buys. */
  val ParallelGlobalLock: Sharing = new Sharing(concurrent = true, parallel = true, globalCompletionLock = true)
