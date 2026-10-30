package dotty.tools.dotc.core

import java.util.concurrent.ConcurrentHashMap

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.Test

import dotty.tools.dotc.Reader
import dotty.tools.sharing.{ClaimRegistry, Mode, Sharing}
import dotty.tools.sharing.SharingLincheckTest.*
import dotty.tools.sharing.ToyCompilerLincheckTest.{BatchToy, Toy}

/** `SharingLincheckTest`'s sections and `ToyCompilerLincheckTest`'s toy on dotc's registry for parallel readers,
 *  `CompletionRoots` (readers stubbed, one per thread, each typing a body, as no reader here is inside a Namer
 *  completion), and one section of its own: a wait-for cycle, which only this registry checks for.
 *
 *  Stress only: a wait for a claim is timed, which model checking does not explore. Bounds as there: per section,
 *  10 scenarios of 3 threads of 3 calls, each run 1000 times; the cycle 5 scenarios of 50 runs, since a reader finds
 *  a cycle only at its next look, a wait slice (5 ms) after the wait began.
 */
class SharingOnCompletionRootsLincheckTest:
  import SharingOnCompletionRootsLincheckTest.*

  @Test def claim(): Unit = stress(classOf[ClaimSpec], classOf[RootsClaim])
  @Test def once(): Unit = stress(classOf[OnceSpec], classOf[RootsOnce])
  @Test def blackHole(): Unit = stress(classOf[BlackHoleSpec], classOf[RootsBlackHole])
  @Test def claimBacksOffAWaitForCycle(): Unit = stress(classOf[CycleSpec], classOf[RootsCycle], scenarios = 5, runs = 50)
  @Test def toyCompiler(): Unit = stress(classOf[BatchToy], classOf[RootsToy])

  // the same sections on the registry with one key for every root (`-Yinteractive-completion-lock:global`): the
  // contract holds, and two keys claimed in opposite orders never close a cycle, so no reader is aborted
  @Test def claimGlobal(): Unit = stress(classOf[ClaimSpec], classOf[GlobalClaim])
  @Test def onceGlobal(): Unit = stress(classOf[OnceSpec], classOf[GlobalOnce])
  @Test def blackHoleGlobal(): Unit = stress(classOf[BlackHoleSpec], classOf[GlobalBlackHole])
  @Test def oppositeOrdersNeverCycleGlobal(): Unit = stress(classOf[CycleSpec], classOf[GlobalNoCycle], scenarios = 5, runs = 50)
  @Test def toyCompilerGlobal(): Unit = stress(classOf[BatchToy], classOf[GlobalToy])

object SharingOnCompletionRootsLincheckTest:

  given Mode = Mode.Concurrent

  /** Completion roots whose readers are one per thread. */
  def roots(): ClaimRegistry =
    val readers = ConcurrentHashMap[Thread, Reader]()
    CompletionRoots(() => readers.computeIfAbsent(Thread.currentThread, _ => Reader.initial()).nn)

  /** Completion roots with the one key (`CompletionRoots(_, global = true)`). */
  def globalRoots(): ClaimRegistry =
    val readers = ConcurrentHashMap[Thread, Reader]()
    CompletionRoots(() => readers.computeIfAbsent(Thread.currentThread, _ => Reader.initial()).nn, global = true)

  class GlobalClaim extends Claim(using globalRoots())
  class GlobalOnce extends BlackHoleOnce(using globalRoots())
  class GlobalBlackHole extends BlackHole(using globalRoots())
  class GlobalToy extends Toy(using Mode.Concurrent, globalRoots())

  /** `RootsCycle`'s claims in opposite orders under the one key: never a cycle, so never an abort (a `Cancelled`
   *  would escape as a result Lincheck does not expect from the spec). */
  class GlobalNoCycle:
    private given ClaimRegistry = globalRoots()
    private val a, b = Key()
    private val counter = Counter()

    @Operation def ab(): Int = Sharing.claim(a)(Sharing.claim(b)(counter.count()))
    @Operation def ba(): Int = Sharing.claim(b)(Sharing.claim(a)(counter.count()))

  class RootsClaim extends Claim(using roots())
  class RootsOnce extends BlackHoleOnce(using roots())
  class RootsBlackHole extends BlackHole(using roots())
  class RootsToy extends Toy(using Mode.Concurrent, roots())

  /** Two readers that claim two keys in opposite orders can close a wait-for cycle. One of them is aborted (a reader
   *  typing a body is cancelled) and its request restarts, rather than both waiting for ever, a hang Lincheck
   *  reports. A call restarts until it is not aborted, so what it promises is what a claim that is never aborted
   *  does: one more on the counter.
   */
  class CycleSpec extends CounterSpec:
    def ab(): Int = next()
    def ba(): Int = next()

  class RootsCycle:
    private given ClaimRegistry = roots()
    private val a, b = Key()
    private val counter = Counter()

    @Operation def ab(): Int = restarting(Sharing.claim(a)(Sharing.claim(b)(counter.count())))
    @Operation def ba(): Int = restarting(Sharing.claim(b)(Sharing.claim(a)(counter.count())))
