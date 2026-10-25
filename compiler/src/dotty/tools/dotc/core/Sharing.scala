package dotty.tools
package dotc
package core

import scala.collection.mutable

import Contexts.*

/** How the symbol table of a `ContextBase` is shared between threads, chosen when the base is made and never changed.
 *
 *  - `Sharing.Batch`: one thread, the batch compiler (and the default).
 *  - `Sharing.Serialised`: several readers of the interactive driver, completions one at a time under the base's
 *    `completionLock` (step 3 of docs/_docs/internals/interactive-multi-reader.md).
 *  - `Sharing.Independent`: several readers, each completion with an owner (`completionRoots`), its intermediate
 *    state seen only by that reader, others waiting for it (`-Yinteractive-readers`, step 4).
 *
 *  A concurrent sharing is only ever made by the interactive driver's base.
 *
 *  Every operation that takes code to run is an `inline def` whose batch branch is that code as it was before
 *  concurrency: a batch call site compiles to a test of a final field and the original expression, and allocates
 *  nothing. The concurrent branches are ordinary methods of `interactive.SharingImpl`.
 */
final class Sharing private (val concurrent: Boolean, val independent: Boolean):

  /** `op` as one writer of the lazy facts keyed by `key` (`key` and `what` are evaluated only when readers are
   *  concurrent) (`what` names it for lock-wait reports): under the
   *  completion lock when readers are serialised, holding the claim on `key` when they are independent (a wait that
   *  would close a wait-for cycle backs off, `CompletionRoots.backOff(cycleSubject)`), as is otherwise. For bring-forwards, lazy TASTy reads, inherited
   *  caches. */
  inline def exclusive[T](inline key: AnyRef, inline what: CompletionLock.What, inline cycleSubject: AnyRef)(inline op: T)(using Context): T =
    if concurrent then interactive.SharingImpl.exclusive(key, what, cycleSubject, () => op) else op

  /** `op` holding the claim on `key` when readers are independent, as is otherwise (serialised readers need no
   *  claim: the computation is not a completion). For lazy references and lazy annotations, whose computations
   *  another reader must wait for rather than take for a cycle. */
  inline def claimed[T](inline key: AnyRef, inline what: CompletionLock.What, inline cycleSubject: AnyRef)(inline op: T)(using Context): T =
    if independent then interactive.SharingImpl.claimed(key, what, cycleSubject, () => op) else op

  /** `op` in `c0`, or, when readers are independent, in a private child of `c0`'s typer state, settled when it ends
   *  (its type variables instantiated, its messages issued) and dropped: what readers share is never written by
   *  one of them (I62, I66, I70, I76). */
  inline def privately[T](c0: Context)(inline op: Context ?=> T): T =
    if independent then interactive.SharingImpl.privately(c0, c => op(using c)) else op(using c0)

  /** `op` holding `lock`'s monitor when readers are concurrent, as is otherwise. */
  inline def serialised[T](lock: AnyRef)(inline op: T): T =
    if concurrent then lock.synchronized(op) else op

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
    if independent then "Sharing.Independent" else if concurrent then "Sharing.Serialised" else "Sharing.Batch"

object Sharing:
  val Batch: Sharing = new Sharing(concurrent = false, independent = false)
  val Serialised: Sharing = new Sharing(concurrent = true, independent = false)
  val Independent: Sharing = new Sharing(concurrent = true, independent = true)
