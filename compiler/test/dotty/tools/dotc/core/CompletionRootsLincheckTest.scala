package dotty.tools.dotc.core

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, CyclicBarrier, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Assert.*
import org.junit.Test

import dotty.tools.dotc.Reader

/** `CompletionRoots`' wait-for check: it must report a cycle only for a real one (finding I87).
 *
 *  The operations take root keys in ways that never deadlock: `nested` holds k1 and takes k2, `single` takes k2 and
 *  releases it, then takes k1 alone. A reader that is woken from a wait on k2 still has that claim in `waitingOn`
 *  until it runs, and the reader that released k2 and now asks for k1 (held by the woken one) used to read it
 *  as a cycle. The operations return whether `cycle()` was called; sequentially it never is, so any `true` fails
 *  the linearizability check. Every reader is inside a Namer completion, so the cycle path is live.
 *
 *  Stress runs real threads (the waits are timed, which model checking does not explore). The real cycle, in
 *  `realCycleIsRaisedByExactlyOne`, must still be raised, once.
 */
class CompletionRootsLincheckTest:
  import CompletionRootsLincheckTest.*

  @Test def stress(): Unit =
    LinChecker.check(classOf[Roots], StressOptions().iterations(50).invocationsPerIteration(500).threads(3).actorsPerThread(4))

  @Test def realCycleIsRaisedByExactlyOne(): Unit =
    val readers = new ConcurrentHashMap[Thread, Reader]()
    val roots = CompletionRoots(() => readers.computeIfAbsent(Thread.currentThread, _ => Reader.initial()).nn)
    val k1, k2 = new Object
    val what = CompletionLock.What(CompletionLock.namerKind, null)
    val barrier = new CyclicBarrier(2)
    val raised = new AtomicInteger
    val done = new CountDownLatch(2)
    class Cycle extends RuntimeException
    def party(first: Object, second: Object): Thread =
      val t = new Thread((() =>
        try
          val me = readers.computeIfAbsent(Thread.currentThread, _ => Reader.initial()).nn
          me.namerCompletionDepth = 1
          roots.acquire(first, what, () => throw new Cycle)
          try
            barrier.await(): Unit
            try
              roots.acquire(second, what, () => throw new Cycle)
              roots.release(second)
            catch case _: Cycle => raised.incrementAndGet()
          finally roots.release(first)
        finally done.countDown()): Runnable)
      t.setDaemon(true)
      t.start()
      t
    party(k1, k2); party(k2, k1)
    assertTrue("both readers should finish", done.await(30, TimeUnit.SECONDS))
    assertEquals("one reader of the cycle raises it", 1, raised.get)

object CompletionRootsLincheckTest:
  class Roots:
    private val readers = new ConcurrentHashMap[Thread, Reader]()
    private val roots = CompletionRoots(() => reader())
    private val k1, k2 = new Object
    private val what = CompletionLock.What(CompletionLock.namerKind, null)

    private def reader(): Reader =
      val r = readers.computeIfAbsent(Thread.currentThread, _ => Reader.initial()).nn
      r.namerCompletionDepth = 1
      r

    private final class Cycle extends RuntimeException

    // true if `cycle` was called (the flag is the operation's own, readers run concurrently)
    private def attempt(op: (() => Nothing) => Unit): Boolean =
      var cycled = false
      try op(() => { cycled = true; throw new Cycle }) catch case _: Cycle => ()
      cycled

    @Operation def nested(): Boolean = attempt { cycle =>
      roots.acquire(k1, what, cycle)
      try
        roots.acquire(k2, what, cycle)
        roots.release(k2)
      finally roots.release(k1)
    }

    @Operation def single(): Boolean = attempt { cycle =>
      roots.acquire(k2, what, cycle)
      roots.release(k2)
      roots.acquire(k1, what, cycle)
      roots.release(k1)
    }
