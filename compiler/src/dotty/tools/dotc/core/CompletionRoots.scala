package dotty.tools
package dotc
package core

import _root_.dotty.tools.sharing.{CycleHandler, PerKeyClaims}

/** Owners of completion roots, with parallel readers (`-Yinteractive-parallel`,
 *  step 4 of `docs/_docs/internals/interactive-multi-reader.md`, §3.1): the
 *  library's `PerKeyClaims`, with the readers as its claimants.
 *
 *  A completion claims its root before it runs: the symbol loader of a class
 *  and its module, the unpickler of a TASTy file, or the symbol. The claim
 *  is the reader's: reentrant on that reader, and every other reader that
 *  needs the root waits until the owner releases it, then looks at the
 *  symbol again (completed, or left completable by an owner that unwound).
 *  This replaces step 3's one completion lock, so that completions of
 *  different roots run concurrently.
 *
 *  A wait that would close a cycle in the wait-for graph is a dependency
 *  cycle, and dotc's policy for it is the reader's (`Reader` as a `Claimant`):
 *  a reader typing a body is aborted as the victim and its request retried; a
 *  reader inside a Namer completion waits for one that is not, or, on a cycle
 *  through a coarse root (every claim but a Namer signature's: a TASTy file, a
 *  bring-forward, an inherited cache), waits `coarseCycleWaitNanos` and then
 *  aborts after all (I74); a cycle between signatures is reported by one
 *  reader, as a `CyclicReference` (`SymDenotation.cyclicWait`).
 *
 *  With parallel readers, the base's `ClaimRegistry`: a `Sharing.claim` claims its key here, and a wait-for cycle
 *  that it reports aborts the reader (`CompletionRoots.AbortVictim`).
 */
object CompletionRoots:
  /** A reader's claim on a root. */
  type Claim = PerKeyClaims.Claim

  /** How long a reader inside a Namer completion waits on a wait-for cycle
   *  through a coarse root before it aborts after all (I74). */
  val coarseCycleWaitNanos: Long = 10_000_000_000L

  def apply(currentReader: () => Reader): PerKeyClaims =
    PerKeyClaims(currentReader, AbortVictim(currentReader), coarseCycleWaitNanos)
  def apply(base: Contexts.ContextState): PerKeyClaims = apply(() => base.reader)

  /** Abort this reader on a wait that would close a wait-for cycle: unwind as a cancelled reader does. */
  def abortVictim(subject: AnyRef)(using Contexts.Context): Nothing = Contexts.ctx.reader.abort()

  /** `abortVictim` as the `CycleHandler` of a `Sharing.claim`. */
  final class AbortVictim(currentReader: () => Reader) extends CycleHandler:
    def waitCycle(subject: AnyRef): Nothing = currentReader().abort()
