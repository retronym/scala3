package dotty.tools
package dotc
package core

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}

import scala.jdk.CollectionConverters.*
import scala.annotation.internal.{confined, interned, scratch}

import _root_.dotty.tools.sharing.{ClaimRegistry, CycleHandler, Sharing as Shared}

/** Owners of completion roots, with parallel readers (`-Yinteractive-parallel`,
 *  step 4 of `docs/_docs/internals/interactive-multi-reader.md`, §3.1).
 *
 *  A completion claims its root before it runs: the symbol loader of a class
 *  and its module, the unpickler of a TASTy file, or the symbol. The claim
 *  is the reader's: reentrant on that reader, and every other reader that
 *  needs the root waits until the owner releases it, then looks at the
 *  symbol again (completed, or left completable by an owner that unwound).
 *  This replaces step 3's one completion lock, so that completions of
 *  different roots run concurrently.
 *
 *  A wait that would close a cycle in the wait-for graph (each waiting
 *  reader records the claim it waits on, `Reader.waitingOn`; following the
 *  owners from the claim leads back to the waiter) is a dependency cycle:
 *  the reader that would close it gets a `CyclicReference` (§4.2), and its
 *  completions unwind and release their roots, so that the other reader's
 *  wait ends. The check runs at every slice of a wait, since another
 *  reader's wait may start after this one's.
 *
 *  With parallel readers, the base's `ClaimRegistry`: a `Sharing.claim` claims its key here, and a wait-for cycle
 *  it would close goes to `cycles` (by default the reader backs off, `CompletionRoots.backOff`).
 */
final class CompletionRoots(currentReader: () => Reader, cycles: CycleHandler) extends ClaimRegistry:
  import CompletionRoots.Claim

  def this(currentReader: () => Reader) = this(currentReader, CompletionRoots.BackOff(currentReader))
  def this(base: Contexts.ContextState) = this(() => base.reader)

  def claim[T](key: AnyRef, kind: Shared.ClaimKind[Nothing], cycleSubject: AnyRef, op: () => T): T =
    locked(key, kind.what, () => cycles.waitCycle(cycleSubject))(op())

  @interned
  private val claims = new ConcurrentHashMap[AnyRef, Claim]()

  /** Claim `key` for this thread's reader, to do `what`; wait while another
   *  reader holds it. `cycle` reports a wait-for cycle (it throws).
   */
  def acquire(key: AnyRef, what: CompletionLock.What, cycle: () => Nothing): Unit =
    val me = currentReader()
    var waitedSince = 0L
    var cycleSince = 0L
    var seen: Claim | Null = null
    var lastChain: List[Claim] | Null = null
    while true do
      val c = claims.get(key)
      if c == null then
        val mine = Claim(me, what, Thread.currentThread.nn.getName.nn)
        if claims.putIfAbsent(key, mine) == null then
          if waitedSince != 0L then
            val s = seen.nn
            me.recordLockWait(System.nanoTime() - waitedSince, s.what.render, "", s.ownerName)
          return
      else if c.owner eq me then
        c.holds += 1
        return
      else
        if waitedSince == 0L then
          waitedSince = System.nanoTime()
          seen = c
        cycleChain(me, c) match
          case null =>
            cycleSince = 0L
            lastChain = null
          case chain =>
            val parties = chain.map(_.owner)
            if me.namerCompletionDepth == 0 then
              // body typing: this reader backs off, its request restarts
              me.waitCyclesBackedOff += 1
              throw CompilationUnit.CancelledCompilation()
            else if parties.exists(_.namerCompletionDepth == 0) then
              () // a reader in the cycle that is not inside a Namer completion backs off at its next look: wait
            else if chain.exists(_.what.kind != CompletionLock.namerKind) then
              // a coarse root (a TASTy file, a bring-forward, an inherited
              // cache): its owner finishes; wait, bounded
              if cycleSince == 0L then cycleSince = System.nanoTime()
              else if System.nanoTime() - cycleSince > CompletionRoots.coarseCycleWaitNanos then
                me.waitCyclesBackedOff += 1
                throw CompilationUnit.CancelledCompilation()
            else if parties.forall(p => System.identityHashCode(p.thread) <= System.identityHashCode(me.thread)) then
              // every party inside a Namer completion, every root a symbol:
              // a cycle between signatures, reported where a single reader
              // reports it, by one reader of the cycle (a fixed order of
              // the threads picks it). The chain is read from fields the
              // readers change as they go: only one seen at two looks, a slice
              // apart, with the same claims, is a cycle and not a snapshot of
              // two waits that ended (I87)
              val last = lastChain
              if last != null && last.corresponds(chain)(_ eq _) then cycle()
              else lastChain = chain
        me.waitingOn = c
        try c.done.await(5, TimeUnit.MILLISECONDS)
        finally me.waitingOn = null
    end while

  /** Release a claim made by `acquire` on this thread. */
  def release(key: AnyRef): Unit =
    val c = claims.get(key).nn
    c.holds -= 1
    if c.holds == 0 then
      claims.remove(key)
      c.done.countDown()

  /** Run `op` holding the claim on `key`. */
  inline def locked[T](key: AnyRef, what: CompletionLock.What, cycle: () => Nothing)(inline op: T): T =
    acquire(key, what, cycle)
    try op finally release(key)

  /** Does this reader hold the claim on `key`? */
  def ownedHere(key: AnyRef): Boolean =
    val c = claims.get(key)
    c != null && (c.owner eq currentReader())

  /** The claims of a wait-for cycle that waiting on `c` would close: `c`,
   *  then what its owner waits on, and so on, back to a claim of `me`; null
   *  if the owners do not lead back to `me`.
   */
  private def cycleChain(me: Reader, c: Claim): List[Claim] | Null =
    var claim: Claim | Null = c
    var chain: List[Claim] = Nil
    var steps = 0
    while claim != null && steps < 1000 do
      // a released claim is a stale edge: `waitingOn` of a reader that has been woken
      // and has not yet cleared it (finding I87)
      if claim.done.getCount == 0 then return null
      chain = claim :: chain
      if claim.owner eq me then
        // the edges were read one after another: a claim released after it was read, whose owner then waited on a
        // claim of `me`, joins two waits that never coexisted. Claims are not reacquired, so every claim still held
        // now was held throughout the walk, and the waits on them still stand (I98)
        return if chain.forall(_.done.getCount != 0) then chain else null
      claim = claim.owner.waitingOn
      steps += 1
    null

  /** The claims held now, for tools (the x-ray). */
  def holdersNow: String =
    claims.values.asScala.map(c => s"${c.ownerName}: ${c.what.render}").mkString("; ")

object CompletionRoots:
  /** How long a reader inside a Namer completion waits on a wait-for cycle
   *  through a coarse root before it backs off after all (I74). */
  val coarseCycleWaitNanos: Long = 10_000_000_000L

  /** Back off from a wait that would close a wait-for cycle (see
   *  `SymDenotation.cyclicWait`): unwind as a pre-empted reader does.
   */
  def backOff(subject: AnyRef)(using Contexts.Context): Nothing = backOff(Contexts.ctx.reader)

  private def backOff(reader: Reader): Nothing =
    reader.waitCyclesBackedOff += 1
    throw CompilationUnit.CancelledCompilation()

  /** `backOff` as the `CycleHandler` of a `Sharing.claim`. */
  final class BackOff(currentReader: () => Reader) extends CycleHandler:
    def waitCycle(subject: AnyRef): Nothing = backOff(currentReader())

  /** A reader's claim on a root, released when its holds come back to 0. */
  final class Claim(val owner: Reader, val what: CompletionLock.What, val ownerName: String):
    // changed only by the owner (`acquire` re-entering, `release`); `@volatile` is not needed for that
    @scratch @confined
    @volatile var holds: Int = 1
    @scratch
    val done: CountDownLatch = new CountDownLatch(1)
