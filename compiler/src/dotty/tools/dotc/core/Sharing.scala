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
   *  `claim(key)`. A capability in the manner of Clang's `REQUIRES`: state that only the writer may touch is
   *  reachable only through a method that takes one, so a touch outside the claim does not compile. Only `claim`,
   *  `once`, `unguarded` and a completion (`completing`) make one; it is invariant in `K`, so the evidence for one
   *  key is not evidence for another. One instance serves every key: a holder allocates nothing.
   */
  @implicitNotFound("this code must run as the one writer of ${K}: inside Sharing.claim or Sharing.once on it, or a completion of it")
  sealed abstract class Holds[K]
  private object held extends Holds[Any]

  /** Evidence of `claim(key)` without it, for an access that races on purpose; `why` says why it is safe
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
  @implicitNotFound("this code must hold the monitor of ${K}: inside its `locked` or `alwaysLocked`, if it is a Sharing.Guard, or Sharing.serialised or Sharing.locking on it")
  sealed abstract class Monitor[K]
  private object monitorHeld extends Monitor[Any]

  /** Evidence of `lock`'s monitor without it, for an access that races on purpose; `why` says why it is safe. */
  inline def unlocked[K](inline why: String): Monitor[K] = monitorHeld.asInstanceOf[Monitor[K]]

  /** How the claim on a key of type `K` is taken (a given for each kind of key, beside its class): its name in
   *  lock-wait reports, and whether serialised readers take the completion lock for it, as for a completion (a
   *  bring-forward, an inherited cache, a change to a published scope, a lazy TASTy read), or need no claim, since
   *  their reads are serialised and the computation is not a completion (a lazy reference, a lazy annotation).
   */
  @implicitNotFound("no Sharing.ClaimKind for ${K}: a key's class says how it is claimed with a given ClaimKind beside it")
  final class ClaimKind[-K](val what: CompletionLock.What, val lockWhenSerialised: Boolean)
  object ClaimKind:
    /** A TASTy file's root (`claimAt`): its address maps are filled by its completers and its lazy reads. */
    val lazyTastyRead: ClaimKind[Any] = ClaimKind(CompletionLock.lazyTastyRead, lockWhenSerialised = true)

  /** `op` as the one writer of the lazy facts keyed by `key`, with the evidence of it: holding the claim on `key` when
   *  readers are parallel, where a wait that would close a wait-for cycle backs off
   *  (`CompletionRoots.backOff(cycleSubject)`); under the completion lock, or as is, when they are serialised, as the
   *  key's `ClaimKind` says; as is on a batch base. `key`, its kind and `cycleSubject` are evaluated only on a
   *  concurrent base.
   */
  inline def claim[K <: AnyRef & Singleton, T](inline key: K, inline cycleSubject: AnyRef | Null = null)(
      inline op: Holds[K] ?=> T)(using inline kind: ClaimKind[K])(using Context): T =
    given Holds[K] = held.asInstanceOf[Holds[K]]
    if ctx.base.concurrent then
      interactive.SharingImpl.claim(key, kind, if cycleSubject == null then key else cycleSubject.nn, () => op)
    else op

  /** `claim` on a key computed per call (a file's root, found from its symbols), which no state is reached by: it
   *  hands out no evidence, and `key` is evaluated only on a concurrent base, as in `claim`.
   */
  inline def claimAt[T](inline key: AnyRef, inline kind: ClaimKind[Nothing], inline cycleSubject: AnyRef)(inline op: T)(
      using Context): T =
    if ctx.base.concurrent then interactive.SharingImpl.claim(key, kind, cycleSubject, () => op) else op

  /** A lazy fact keyed by `key`, computed once: the value `peek` finds, or, when it finds none, `compute`d as the
   *  one writer of `key` (`claim`), with `peek` asked again inside first, since another reader may have computed it
   *  meanwhile. `peek` must find the value only once it is complete; `compute` stores it. Another reader waits for an
   *  owner's computation at the claim, so `compute` is entered again only by its owner, re-entering its own
   *  computation: that is the cycle test's place. On a batch base `peek ?: compute`.
   */
  inline def once[K <: AnyRef & Singleton, T <: AnyRef](inline key: K, inline cycleSubject: AnyRef | Null = null)(
      inline peek: T | Null)(inline compute: Holds[K] ?=> T)(using inline kind: ClaimKind[K])(using Context): T =
    val v = peek
    if v != null then v.asInstanceOf[T]
    else claim(key, cycleSubject):
      val v1 = peek
      if v1 != null then v1.asInstanceOf[T] else compute

  /** The black hole of a computation that the holder of the claim on this object runs: which thread is inside it.
   *  Entered and left only with the claim's evidence; whether this thread is inside needs none, since only its own
   *  store can make that true. A field of the object it is mixed into, so it allocates nothing.
   */
  trait BlackHole:
    @scala.annotation.internal.benignRace("written under the claim; read without it only to compare with this thread")
    private var insideThread: Thread | Null = null
    /** `op` with this thread inside, restoring who was inside before (a nested entry by the same thread). */
    protected final inline def inside[T](inline op: T)(using Holds[this.type]): T =
      val was = insideThread
      insideThread = Thread.currentThread
      try op finally insideThread = was
    /** Is some thread inside? */
    protected final def isInside(using Holds[this.type]): Boolean = insideThread != null
    /** Is this thread inside? */
    protected final def isInsideHere: Boolean = insideThread eq Thread.currentThread

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

  /** A variable of a `Guard` reached only with the evidence of the monitor of `K`, the guard: `v()` and `v() = x`. */
  final class GuardedVar[K, T](private var value: T):
    inline def apply()(using Monitor[K]): T = value
    inline def update(v: T)(using Monitor[K]): Unit = value = v

  /** An object whose monitor guards the state it makes with `guarded` and `guardedVar`: the state lives in the
   *  guard, and is reached only inside `locked` (with concurrent readers; a batch base runs alone) or `alwaysLocked`
   *  (on every base, for code without a context).
   */
  abstract class Guard:
    protected final def guarded[T](init: T): Guarded[this.type, T] = Guarded.wrap(init)
    protected final def guardedVar[T](init: T): GuardedVar[this.type, T] = GuardedVar(init)
    final inline def locked[T](inline op: Monitor[this.type] ?=> T)(using Context): T =
      serialised(this)(op)
    final inline def alwaysLocked[T](inline op: Monitor[this.type] ?=> T): T =
      locking(this)(op)

  /** The end of a typer state `c1.typerState` that was a child of `into`'s, on a concurrent base: with parallel
   *  readers settled and dropped (I76), with serialised readers committed under the completion lock. */
  def endState(c1: Context, into: Context): Unit = interactive.SharingImpl.endState(c1, into)

  val Batch: Sharing = new Sharing(concurrent = false, parallel = false)
  val Serialised: Sharing = new Sharing(concurrent = true, parallel = false)
  val Parallel: Sharing = new Sharing(concurrent = true, parallel = true)

/** A value of a `Sharing.Guard` reached only with the evidence of the monitor of `K`, the guard: `g()` takes it. The
 *  value itself, so a field of this type costs what the field did. Outside `object Sharing`: an opaque type is seen
 *  through in the result types of the members of classes defined in its scope, `Guard.guarded`'s among them.
 */
opaque type Guarded[K, T] = T
object Guarded:
  private[core] inline def wrap[K, T](t: T): Guarded[K, T] = t
  extension [K, T](g: Guarded[K, T]) inline def apply()(using Sharing.Monitor[K]): T = g
