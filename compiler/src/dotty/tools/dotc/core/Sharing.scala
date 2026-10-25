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

  /** `op` holding `lock`'s monitor when readers are concurrent, as is otherwise. */
  inline def serialised[T](lock: AnyRef)(inline op: T)(using Context): T =
    if ctx.base.concurrent then lock.synchronized(op) else op

  val Batch: Sharing = new Sharing(concurrent = false, parallel = false)
  val Serialised: Sharing = new Sharing(concurrent = true, parallel = false)
