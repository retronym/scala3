package dotty.tools
package dotc
package core

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}

import scala.jdk.CollectionConverters.*

/** Owners of completion roots, with independent readers (`-Yinteractive-readers`,
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
 */
final class CompletionRoots(base: Contexts.ContextState):
  import CompletionRoots.Claim

  private val claims = new ConcurrentHashMap[AnyRef, Claim]()

  /** Claim `key` for this thread's reader, to do `what`; wait while another
   *  reader holds it. `cycle` reports a wait-for cycle (it throws).
   */
  def acquire(key: AnyRef, what: CompletionLock.What, cycle: () => Nothing): Unit =
    val me = base.reader
    var waitedSince = 0L
    var seen: Claim | Null = null
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
        if closesCycle(me, c) then cycle()
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
    c != null && (c.owner eq base.reader)

  /** Would waiting on `c` close a cycle: do the owners, followed through
   *  what each waits on, lead back to `me`?
   */
  private def closesCycle(me: Reader, c: Claim): Boolean =
    var o: Reader | Null = c.owner
    var steps = 0
    while o != null && steps < 1000 do
      if o eq me then return true
      val w = o.waitingOn
      o = if w == null then null else w.owner
      steps += 1
    false

  /** The claims held now, for tools (the x-ray). */
  def holdersNow: String =
    claims.values.asScala.map(c => s"${c.ownerName}: ${c.what.render}").mkString("; ")

object CompletionRoots:
  /** Back off from a wait that would close a wait-for cycle (see
   *  `SymDenotation.cyclicWait`): unwind as a pre-empted reader does.
   */
  def backOff(subject: AnyRef)(using Contexts.Context): Nothing =
    Contexts.ctx.reader.waitCyclesBackedOff += 1
    throw CompilationUnit.CancelledCompilation()

  /** A reader's claim on a root, released when its holds come back to 0. */
  final class Claim(val owner: Reader, val what: CompletionLock.What, val ownerName: String):
    @volatile var holds: Int = 1
    val done: CountDownLatch = new CountDownLatch(1)
