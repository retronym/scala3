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
 *  A concurrent sharing is only ever made by the interactive driver's base.
 *
 *  Every operation that takes code to run is an `inline def` of the companion whose batch branch is that code as it was before
 *  concurrency: a batch call site compiles to a test of a final field and the original expression, and allocates
 *  nothing. The concurrent branches are ordinary methods of `interactive.SharingImpl`.
 */
final class Sharing private (val concurrent: Boolean, val independent: Boolean):

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
  // The operations test the base's copies of the flags (`ContextState.concurrent`, `independent`): one field read
  // from the base at each call site, not two.

  /** `op` as the one writer of the lazy facts keyed by `key`: under the completion lock when readers are serialised;
   *  holding the claim on `key` when they are independent, where a wait that would close a wait-for cycle backs off
   *  (`CompletionRoots.backOff(cycleSubject)`); as is otherwise. `key`, `what` (its name in lock-wait reports) and
   *  `cycleSubject` are evaluated only on a concurrent base. For bring-forwards, lazy TASTy reads, inherited caches. */
  inline def exclusive[T](inline key: AnyRef, inline what: CompletionLock.What, inline cycleSubject: AnyRef)(inline op: T)(using Context): T =
    if ctx.base.concurrent then interactive.SharingImpl.exclusive(key, what, cycleSubject, () => op) else op

  /** `op` holding `lock`'s monitor when readers are concurrent, as is otherwise. */
  inline def serialised[T](lock: AnyRef)(inline op: T)(using Context): T =
    if ctx.base.concurrent then lock.synchronized(op) else op

  /** The end of a typer state `c1.typerState` that was a child of `into`'s, on a concurrent base: committed under
   *  the completion lock. */
  def endState(c1: Context, into: Context): Unit = interactive.SharingImpl.endState(c1, into)

  val Batch: Sharing = new Sharing(concurrent = false, independent = false)
  val Serialised: Sharing = new Sharing(concurrent = true, independent = false)
