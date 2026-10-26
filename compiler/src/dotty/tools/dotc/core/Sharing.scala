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
 *
 *  Which operation, by what the state is:
 *
 *  - a lazy fact, computed once by one writer while other readers wait for it: `once` (a value read lock free once
 *    it is there), `claim` (anything else), with a `ClaimKind` given beside the key's class and, for re-entry, a
 *    `BlackHole`. The writer's state is reached through methods that take the claim's evidence, `Holds[key.type]`.
 *  - a short critical section over a collection or a few fields: a `Guard` whose fields are `Guarded`, or
 *    `serialised` and `locking` on another object's monitor. The guarded state is reached with `Monitor[lock.type]`.
 *  - a value paired with the period or run it was computed in: `Caching`.
 *  - typing that must not write what readers share: `privately`.
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
   *
   *  The writer's state is private, its writes behind methods that take the evidence (`LazyRef`):
   *  {{{
   *  private def set(ref: Type | Null, done: Boolean)(using Sharing.Holds[this.type]): Unit =
   *    myRef = ref
   *    computed = done
   *  }}}
   *  and `set(...)` outside `Sharing.claim(this)` or `Sharing.once(this)` is an error: "this code must run as the one
   *  writer of (LazyRef.this : LazyRef)". At run time the parameter is the one shared `held` object.
   */
  @implicitNotFound("this code must run as the one writer of ${K}: inside Sharing.claim or Sharing.once on it, or a completion of it")
  sealed abstract class Holds[K]
  private object held extends Holds[Any]

  /** Evidence of `claim(key)` without it, for an access that races on purpose; `why` says why it is safe
   *  (Clang's `NO_THREAD_SAFETY_ANALYSIS`, scoped to one access). `K` is inferred from where the evidence is used:
   *  {{{
   *  // BaseDataImpl: the dependent cache's computation holds the claim further up this thread's stack
   *  setProvisional(true)(using Sharing.unguarded("signalled from inside the claim's own computation"))
   *  }}}
   *  Expands to the shared evidence object; `why` is not evaluated, it is there for the reader (and the state map).
   */
  inline def unguarded[K](inline why: String): Holds[K] = held.asInstanceOf[Holds[K]]

  /** The evidence a completer runs with, `Holds[denot.type]`: made only by `SymDenotation.lockAndComplete0`, which
   *  runs a completer under the denotation's `Touched` and, with several readers, its root's claim or the
   *  completion lock. A completer passes it on to one it delegates to for the same denotation.
   */
  private[core] inline def completing[K]: Holds[K] = held.asInstanceOf[Holds[K]]

  /** Evidence that the code it is given to holds the monitor of `lock: K` (with concurrent readers; on a batch base
   *  it runs alone), as `Holds` is of a claim: state a monitor guards is reached only through methods that take one.
   *  Made by `serialised`, `locking`, a `Guard`'s `locked` and `alwaysLocked`, and `unlocked`; a claim's evidence is
   *  not a monitor's, nor the reverse. A `Guarded` field takes it to be read: `bySource()` outside `locked` is an
   *  error, "this code must hold the monitor of (Run.this.suppressions : ...)".
   */
  @implicitNotFound("this code must hold the monitor of ${K}: inside its `locked` or `alwaysLocked`, if it is a Sharing.Guard, or Sharing.serialised or Sharing.locking on it")
  sealed abstract class Monitor[K]
  private object monitorHeld extends Monitor[Any]

  /** Evidence of `lock`'s monitor without it, for an access that races on purpose; `why` says why it is safe:
   *  {{{
   *  // ContextDocstrings: the pickler reads the map after the typer, when no reader writes it
   *  def docstrings: ReadOnlyMap[Symbol, Comment] =
   *    _docstrings()(using Sharing.unlocked("read after the typer, or by the driver's edits with no reader"))
   *  }}}
   *  Bind it to a value, not a `given` alias, if it is used more than once: `given M = Sharing.unlocked(...)` is a
   *  lazy given, a `LazyRef` per call; `val m = Sharing.unlocked(...); given M = m` is not.
   */
  inline def unlocked[K](inline why: String): Monitor[K] = monitorHeld.asInstanceOf[Monitor[K]]

  /** How the claim on a key of type `K` is taken (a given for each kind of key, beside its class): its name in
   *  lock-wait reports, and whether serialised readers take the completion lock for it, as for a completion (a
   *  bring-forward, an inherited cache, a change to a published scope, a lazy TASTy read), or need no claim, since
   *  their reads are serialised and the computation is not a completion (a lazy reference, a lazy annotation).
   *  {{{
   *  object LazyRef:
   *    given Sharing.ClaimKind[LazyRef] = Sharing.ClaimKind(CompletionLock.lazyRef, lockWhenSerialised = false)
   *  }}}
   *  Contravariant, so one given serves a class, its subclasses and the singleton types `claim` infers
   *  (`ClaimKind[LazyRef] <: ClaimKind[r.type]`), and one for a union serves both (`ClaimKind[LazyAnnotation |
   *  LazyBodyAnnotation]`). The given is found through the key's class (its companion or enclosing object), so a
   *  key with no kind is an error at the claim: "no Sharing.ClaimKind for ...".
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
   *  concurrent base. `cycleSubject` (what a wait-for cycle is reported against) defaults to the key.
   *  {{{
   *  // MutableScope.writing: the scope's one writer
   *  Sharing.claim(this)(inside(op))
   *  // SingleDenotation.current: one key for every bring-forward, the cycle reported against the symbol
   *  Sharing.claim(BringForward, symbol)(initial.bringForward().current)
   *  }}}
   *  The key is a stable path (`this`, a val, an object), whose singleton type is inferred as `K`. A call expands to
   *  {{{
   *  val h = held.asInstanceOf[Holds[K]]
   *  if ctx.base.concurrent then SharingImpl.claim(key, kind, cycleSubject, () => op(using h))
   *  else op(using h)
   *  }}}
   *  where `SharingImpl.claim` takes the root's claim (parallel), the completion lock (serialised, `kind` says so)
   *  or nothing (serialised, it says not). Re-entry by the owner of a claim runs `op` at once: the owner's test for
   *  re-entering its own computation is `op`'s (a `BlackHole`, or a sentinel in the state).
   */
  inline def claim[K <: AnyRef & Singleton, T](inline key: K, inline cycleSubject: AnyRef | Null = null)(
      inline op: Holds[K] ?=> T)(using inline kind: ClaimKind[K])(using Context): T =
    // the evidence a local value, not a given alias, which would be a lazy one: a `scala.runtime.LazyRef` per call
    val h = held.asInstanceOf[Holds[K]]
    if ctx.base.concurrent then
      interactive.SharingImpl.claim(key, kind, if cycleSubject == null then key else cycleSubject.nn, () => op(using h))
    else op(using h)

  /** `claim` on a key computed per call (a file's root, found from its symbols), which no state is reached by: it
   *  hands out no evidence, and `key` is evaluated only on a concurrent base, as in `claim`. The kind is given
   *  explicitly, since the key has no class to find it through:
   *  {{{
   *  // TreeUnpickler.unpickle: the file's address maps, under the claim its completers take
   *  Sharing.claimAt(claimKey, Sharing.ClaimKind.lazyTastyRead, this)(unpickle0(mode))
   *  }}}
   *  Expands to `if ctx.base.concurrent then SharingImpl.claim(key, kind, cycleSubject, () => op) else op`.
   */
  inline def claimAt[T](inline key: AnyRef, inline kind: ClaimKind[Nothing], inline cycleSubject: AnyRef)(inline op: T)(
      using Context): T =
    if ctx.base.concurrent then interactive.SharingImpl.claim(key, kind, cycleSubject, () => op) else op

  /** A lazy fact keyed by `key`, computed once: the value `peek` finds, or, when it finds none, `compute`d as the
   *  one writer of `key` (`claim`), with `peek` asked again inside first, since another reader may have computed it
   *  meanwhile. `peek` must find the value only once it is complete; `compute` stores it. Another reader waits for an
   *  owner's computation at the claim, so `compute` is entered again only by its owner, re-entering its own
   *  computation: that is the cycle test's place. On a batch base `peek ?: compute`.
   *  {{{
   *  // LazyRef.ref: `myRef` is null until the value is stored; `computed` is set when the computation starts
   *  def ref(using Context): Type =
   *    Sharing.once(this)(myRef):
   *      if computed then throw CyclicReference(NoDenotation) // only the owner gets here again
   *      compute()
   *      myRef.nn
   *  }}}
   *  Expands to the double-checked computation
   *  {{{
   *  val v = peek
   *  if v != null then v
   *  else claim(key, cycleSubject):
   *    val v1 = peek
   *    if v1 != null then v1 else compute
   *  }}}
   *  so a hit is `peek`'s reads and a null test, on every base. `peek` reads without the claim: what it reads must
   *  be published whole (a final-field structure, a volatile, a value stored after what it refers to).
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
   *  {{{
   *  private abstract class InheritedCacheImpl(...) extends InheritedCache, Sharing.BlackHole
   *  // BaseDataImpl.apply, inside `Sharing.claim(this)`: a re-entry by the owner is a cycle
   *  if isInside then throw CyclicReference(clsd)
   *  val computed = inside(clsd.computeBaseData(using this, ctx))
   *  // BaseDataImpl.invalidate, from any thread: an invalidation from inside the computation is ignored
   *  if valid && !isInsideHere then ...
   *  }}}
   *  `inside(op)` expands to `val was = insideThread; insideThread = currentThread; try op finally insideThread = was`.
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
   *  one of them (I62, I66, I70, I76).
   *  {{{
   *  // Namer: a signature typed ahead, from whichever reader forces it
   *  val mdef = Sharing.privately(ctx)(typedAheadExpr(original)).asInstanceOf[tpd.DefDef]
   *  }}}
   *  Expands to `if c0.base.parallel then SharingImpl.privately(c0, c => op(using c)) else op(using c0)`.
   */
  inline def privately[T](c0: Context)(inline op: Context ?=> T): T =
    if c0.base.parallel then interactive.SharingImpl.privately(c0, c => op(using c)) else op(using c0)

  /** `op` holding `lock`'s monitor when readers are concurrent, as is otherwise, with the evidence of it. For state
   *  that lives in `lock` but is not a `Guard`'s (a trait's fields, a map another object owns):
   *  {{{
   *  // ConstraintRunInfo: a statistic every reader reports to
   *  Sharing.serialised(this) {
   *    if size > maxSize then
   *      maxSize = size
   *      maxConstraint = c
   *  }
   *  }}}
   *  Expands to `if ctx.base.concurrent then lock.synchronized(op(using m)) else op(using m)`, `m` the shared
   *  evidence object. Prefer a `Guard` where the state can move into one: then nothing reaches it without the monitor.
   */
  inline def serialised[K <: AnyRef & Singleton, T](lock: K)(inline op: Monitor[K] ?=> T)(using Context): T =
    val m = monitorHeld.asInstanceOf[Monitor[K]] // not a given alias, as in `claim`
    if ctx.base.concurrent then lock.synchronized(op(using m)) else op(using m)

  /** `op` holding `lock`'s monitor on every base, with the evidence of it: `serialised` for code with no context, or
   *  state that more than the readers of one base reach. Expands to `lock.synchronized(op(using m))`.
   */
  inline def locking[K <: AnyRef & Singleton, T](lock: K)(inline op: Monitor[K] ?=> T): T =
    lock.synchronized(op(using monitorHeld.asInstanceOf[Monitor[K]]))

  /** A variable of a `Guard` reached only with the evidence of the monitor of `K`, the guard: `v()` and `v() = x`.
   *  {{{
   *  object suspension extends Sharing.Guard:
   *    private val atTyper = guardedVar(false)
   *    def add(...) = locked { ...; atTyper() = true }
   *    def atTyperPhase(using Context): Boolean = locked(atTyper())
   *  }}}
   *  A box, one object per variable, read and written through it; a `Guarded` value costs nothing, so prefer a
   *  guarded collection to a guarded variable where either serves.
   */
  final class GuardedVar[K, T](private var value: T):
    inline def apply()(using Monitor[K]): T = value
    inline def update(v: T)(using Monitor[K]): Unit = value = v

  /** An object whose monitor guards the state it makes with `guarded` and `guardedVar`: the state lives in the
   *  guard, and is reached only inside `locked` (with concurrent readers; a batch base runs alone) or `alwaysLocked`
   *  (on every base, for code without a context).
   *  {{{
   *  object suppressions extends Sharing.Guard:
   *    private val bySource = guarded(mutable.LinkedHashMap.empty[SourceFile, ListBuffer[Suppression]])
   *    def addSuppression(sup: Suppression)(using Context): Unit =
   *      locked:
   *        bySource().getOrElseUpdate(sup.annotPos.source, ListBuffer.empty) += sup
   *  }}}
   *  `locked(op)` expands to `serialised(this)(op)`, `alwaysLocked(op)` to `locking(this)(op)`, and `bySource()` to
   *  the field read. What `op` returns is outside the monitor: return a copy (`units().toList`), not the collection.
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
 *  value itself, so a field of this type costs what the field did, and `g()` expands to the field read. Made by the
 *  guard's `guarded(init)`, its type `Guarded[guard.type, T]` (see `Sharing.Guard` for a use). Outside
 *  `object Sharing`: an opaque type is seen through in the result types of the members of classes defined in its
 *  scope, `Guard.guarded`'s among them.
 */
opaque type Guarded[K, T] = T
object Guarded:
  private[core] inline def wrap[K, T](t: T): Guarded[K, T] = t
  extension [K, T](g: Guarded[K, T]) inline def apply()(using Sharing.Monitor[K]): T = g
