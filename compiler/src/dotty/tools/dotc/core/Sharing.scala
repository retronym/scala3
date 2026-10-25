package dotty.tools
package dotc
package core

import Contexts.*

/** How the symbol table of a `ContextBase` is shared between threads, chosen when the base is made and never changed.
 *
 *  - `Sharing.Batch`: one thread, no locking: the batch compiler (and the default).
 *
 *  Only `Batch` so far: the concurrent sharings, and the operations that differ for them, come with the structures and
 *  readers that need them. Each operation that takes code to run will be an `inline def` whose batch branch is that
 *  code as it was: a batch call site compiles to a test of a final field of the base and the original expression.
 */
final class Sharing private (val concurrent: Boolean, val parallel: Boolean):
  assert(concurrent || !parallel)

  override def toString: String =
    if parallel then "Sharing.Parallel" else if concurrent then "Sharing.Serialised" else "Sharing.Batch"

object Sharing:
  // The operations test the base's copies of the flags (`ContextState.concurrent`, `parallel`): one field read
  // from the base at each call site, not two.

  val Batch: Sharing = new Sharing(concurrent = false, parallel = false)
