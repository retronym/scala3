package dotty.tools
package dotc
package core

import scala.collection.mutable
import scala.annotation.implicitNotFound

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
 *
 *  A concurrent sharing is only ever made by the interactive driver's base.
 *
 *  Every operation that takes code to run is an `inline def` of the companion whose batch branch is that code as it was before
 *  concurrency: a batch call site compiles to a test of a final field and the original expression, and allocates
 *  nothing. The concurrent branches are ordinary methods of `interactive.SharingImpl`.
 */
final class Sharing private (val concurrent: Boolean, val parallel: Boolean):
  assert(concurrent || !parallel)

  // ---- construction-time choices: called once per base, run, cache or class, never per access

  /** An identity hash map: the synchronised one when readers are concurrent. */
  def newEqHashMap[K, V](initialCapacity: Int = 8): util.EqHashMap[K, V] =
    if concurrent then util.SyncEqHashMap[K, V](initialCapacity) else util.EqHashMap[K, V](initialCapacity)

  /** A mutable map shared by readers: a `TrieMap` when they are concurrent. */
  def newMap[K, V](): mutable.Map[K, V] =
    if concurrent then scala.collection.concurrent.TrieMap() else mutable.HashMap()

  def newUniques(): Uniques = if concurrent then ConcurrentUniques() else Uniques()
  def newAppliedUniques(): Uniques.AppliedUniques = if concurrent then Uniques.ConcurrentAppliedUniques() else Uniques.AppliedUniques()
  def newNamedTypeUniques(): Uniques.NamedTypeUniques = if concurrent then Uniques.ConcurrentNamedTypeUniques() else Uniques.NamedTypeUniques()

  override def toString: String =
    if parallel then "Sharing.Parallel" else if concurrent then "Sharing.Serialised" else "Sharing.Batch"

object Sharing:
  // The operations test the base's copies of the flags (`ContextState.concurrent`, `parallel`): one field read
  // from the base at each call site, not two.

  /** Evidence that the code it is given to is the one writer of the lazy facts keyed by `key: K`: it runs in
   *  `exclusive(key)`. A capability in the manner of Clang's `REQUIRES`: state that only the writer may touch is
   *  reachable only through a method that takes one, so a touch outside the claim does not compile. Only `exclusive`,
   *  `claimed`, `unguarded` and a completion (`completing`) make one; it is invariant in `K`, so the evidence for one key is not evidence for another. One
   *  instance serves every key: a holder allocates nothing.
   */
  @implicitNotFound("this code must run as the one writer of ${K}: inside Sharing.exclusive or Sharing.claimed on it, or a completion of it")
  sealed abstract class Holds[K]
  private object held extends Holds[Any]

  /** Evidence of `exclusive(key)` without its claim, for an access that races on purpose; `why` says why it is safe
   *  (Clang's `NO_THREAD_SAFETY_ANALYSIS`, scoped to one access).
   */
  inline def unguarded[K](inline why: String): Holds[K] = held.asInstanceOf[Holds[K]]

  /** The evidence a completer runs with, `Holds[denot.type]`: made only by `SymDenotation.lockAndComplete0`, which
   *  runs a completer under the denotation's `Touched` and, with several readers, its root's claim or the
   *  completion lock. A completer passes it on to one it delegates to for the same denotation.
   */
  private[core] inline def completing[K]: Holds[K] = held.asInstanceOf[Holds[K]]

  /** Evidence that the code it is given to holds the monitor of `lock: K` (with concurrent readers; on a batch base
   *  it runs alone), as `Holds` is of a claim: state a monitor guards is reached only through methods that take one.
   *  Made by `serialised`, `locking` and `unlocked`; a claim's evidence is not a monitor's, nor the reverse.
   */
  @implicitNotFound("this code must hold the monitor of ${K}: inside Sharing.serialised or Sharing.locking on it")
  sealed abstract class Monitor[K]
  private object monitorHeld extends Monitor[Any]

  /** Evidence of `lock`'s monitor without it, for an access that races on purpose; `why` says why it is safe. */
  inline def unlocked[K](inline why: String): Monitor[K] = monitorHeld.asInstanceOf[Monitor[K]]

  /** `op` as the one writer of the lazy facts keyed by `key`: under the completion lock when readers are serialised;
   *  holding the claim on `key` when they are parallel, where a wait that would close a wait-for cycle backs off
   *  (`CompletionRoots.backOff(cycleSubject)`); as is otherwise. `key`, `what` (its name in lock-wait reports) and
   *  `cycleSubject` are evaluated only on a concurrent base. For bring-forwards, lazy TASTy reads, inherited caches. */
  inline def exclusive[K <: AnyRef & Singleton, T](inline key: K, inline what: CompletionLock.What, inline cycleSubject: AnyRef)(
      inline op: Holds[K] ?=> T)(using Context): T =
    given Holds[K] = held.asInstanceOf[Holds[K]]
    if ctx.base.concurrent then interactive.SharingImpl.exclusive(key, what, cycleSubject, () => op) else op

  /** `exclusive` on a key computed per call (a file's root, found from its symbols), which no state is reached by:
   *  it hands out no evidence, and `key` is evaluated only on a concurrent base, as in `exclusive`. */
  inline def exclusiveAt[T](inline key: AnyRef, inline what: CompletionLock.What, inline cycleSubject: AnyRef)(inline op: T)(
      using Context): T =
    if ctx.base.concurrent then interactive.SharingImpl.exclusive(key, what, cycleSubject, () => op) else op

  /** `op` holding the claim on `key` when readers are parallel, as is otherwise (serialised readers need no
   *  claim: the computation is not a completion). For lazy references and lazy annotations, whose computations
   *  another reader must wait for rather than take for a cycle. */
  inline def claimed[K <: AnyRef & Singleton, T](inline key: K, inline what: CompletionLock.What, inline cycleSubject: AnyRef)(
      inline op: Holds[K] ?=> T)(using Context): T =
    given Holds[K] = held.asInstanceOf[Holds[K]]
    if ctx.base.parallel then interactive.SharingImpl.claimed(key, what, cycleSubject, () => op) else op

  /** `op` in `c0`, or, when readers are parallel, in a private child of `c0`'s typer state, settled when it ends
   *  (its type variables instantiated, its messages issued) and dropped: what readers share is never written by
   *  one of them (I62, I66, I70, I76). */
  inline def privately[T](c0: Context)(inline op: Context ?=> T): T =
    if c0.base.parallel then interactive.SharingImpl.privately(c0, c => op(using c)) else op(using c0)

  /** `op` holding `lock`'s monitor when readers are concurrent, as is otherwise. */
  inline def serialised[K <: AnyRef & Singleton, T](lock: K)(inline op: Monitor[K] ?=> T)(using Context): T =
    given Monitor[K] = monitorHeld.asInstanceOf[Monitor[K]]
    if ctx.base.concurrent then lock.synchronized(op) else op

  /** `op` holding `lock`'s monitor on every base, with the evidence of it. */
  inline def locking[K <: AnyRef & Singleton, T](lock: K)(inline op: Monitor[K] ?=> T): T =
    given Monitor[K] = monitorHeld.asInstanceOf[Monitor[K]]
    lock.synchronized(op)

  /** The end of a typer state `c1.typerState` that was a child of `into`'s, on a concurrent base: with parallel
   *  readers settled and dropped (I76), with serialised readers committed under the completion lock. */
  def endState(c1: Context, into: Context): Unit = interactive.SharingImpl.endState(c1, into)

  val Batch: Sharing = new Sharing(concurrent = false, parallel = false)
  val Serialised: Sharing = new Sharing(concurrent = true, parallel = false)
  val Parallel: Sharing = new Sharing(concurrent = true, parallel = true)
