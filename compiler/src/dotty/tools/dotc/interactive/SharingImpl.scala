package dotty.tools
package dotc
package interactive

import core.*
import Contexts.*

/** The concurrent branches of `core.Sharing`'s operations: reached only from a base the interactive driver made
 *  with several readers, never from batch compilation. See `Sharing` for what each operation means.
 */
object SharingImpl:

  def exclusive[T](key: AnyRef, what: CompletionLock.What, cycleSubject: AnyRef, op: () => T)(using Context): T =
    val base = ctx.base
    if base.independent then base.completionRoots.locked(key, what, () => CompletionRoots.backOff(cycleSubject))(op())
    else base.completionLock.locked(what)(op())

  def claimed[T](key: AnyRef, what: CompletionLock.What, cycleSubject: AnyRef, op: () => T)(using Context): T =
    ctx.base.completionRoots.locked(key, what, () => CompletionRoots.backOff(cycleSubject))(op())

  def privately[T](c0: Context, op: Context => T): T =
    val c1 = c0.fresh.setNewTyperState()
    val result = op(c1)
    c1.typerState.settle(c0)(using c1)
    result

  /** The end of a typer state `c1.typerState` that was a child of `into`'s: with independent readers settled and
   *  dropped (I76); with serialised readers committed under the completion lock. */
  def endState(c1: Context, into: Context): Unit =
    if c1.base.independent then c1.typerState.settle(into)(using c1)
    else c1.base.completionLock.locked(CompletionLock.commit)(c1.typerState.commit()(using into))
