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
    ctx.base.completionLock.locked(what)(op())

  /** The end of a typer state `c1.typerState` that was a child of `into`'s: committed under the completion lock. */
  def endState(c1: Context, into: Context): Unit =
    c1.base.completionLock.locked(CompletionLock.commit)(c1.typerState.commit()(using into))
