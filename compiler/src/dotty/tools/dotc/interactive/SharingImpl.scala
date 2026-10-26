package dotty.tools
package dotc
package interactive

import core.*
import Contexts.*

/** The concurrent branches of `core.Sharing`'s operations: reached only from a base the interactive driver made
 *  with several readers, never from batch compilation. See `Sharing` for what each operation means.
 */
object SharingImpl:

  def claim[T](key: AnyRef, kind: Sharing.ClaimKind[Nothing], cycleSubject: AnyRef, op: () => T)(using Context): T =
    val base = ctx.base
    if base.parallel then base.completionRoots.locked(key, kind.what, () => CompletionRoots.backOff(cycleSubject))(op())
    else if kind.lockWhenSerialised then base.completionLock.locked(kind.what)(op())
    else op()

  def privately[T](c0: Context, op: Context => T): T =
    val c1 = c0.fresh.setNewTyperState()
    val result = op(c1)
    c1.typerState.settle(c0)(using c1)
    result

  /** The end of a typer state `c1.typerState` that was a child of `into`'s: with parallel readers settled and
   *  dropped (I76); with serialised readers committed under the completion lock. */
  def endState(c1: Context, into: Context): Unit =
    if c1.base.parallel then c1.typerState.settle(into)(using c1)
    else c1.base.completionLock.locked(CompletionLock.commit)(c1.typerState.commit()(using into))
