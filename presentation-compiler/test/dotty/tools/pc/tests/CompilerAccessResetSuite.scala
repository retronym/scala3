package dotty.tools.pc.tests

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.language.unsafeNulls
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.pc.PresentationCompilerConfigImpl

import dotty.tools.pc.ScalaPresentationCompiler
import dotty.tools.pc.base.BasePCSuite

import org.junit.{Assert, Test}

/** A request that times out in the queue resets the job queue and drops the
 *  compiler (mtags' `CompilerAccess.onCompilerJobQueue`). The reset shuts the
 *  old queue's executor down without discarding its backlog, so the jobs still
 *  queued there run on the old queue's thread, and they load the compiler that
 *  the requests on the new queue load too: two threads on one driver. Seen in
 *  a replayed editor trace as `denotation ... invalid in run N`, a context of
 *  one run meeting denotations brought forward by the other.
 */
class CompilerAccessResetSuite extends BasePCSuite:

  override protected def config: PresentationCompilerConfigImpl =
    super.config.copy(timeoutDelay = if isDebug then 3600 else 2)

  @Test def `jobs of the old and the new queue do not share the driver at once`(): Unit =
    val pc = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]
    val access = pc.compilerAccess
    given scala.meta.internal.metals.PcQueryContext = pc.emptyQueryContext
    val inFlight = new AtomicInteger
    val maxInFlight = new AtomicInteger
    def onDriver[T](body: => T): T =
      maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), math.max)
      try body finally inFlight.decrementAndGet()

    val releaseOld = new CountDownLatch(1)
    val newStarted = new CountDownLatch(1)
    val backlogStarted = new CountDownLatch(1)

    // holds the thread of the first queue past the timeout
    val stuck = access.withNonInterruptableCompiler((), EmptyCancelToken)(_ => releaseOld.await())
    // queued behind it, on the first queue's backlog, late enough not to time
    // out itself before it is dequeued (the queue drops a job whose result is done)
    Thread.sleep(1000)
    val backlog = access.withNonInterruptableCompiler((), EmptyCancelToken) { _ =>
      onDriver(backlogStarted.countDown())
    }
    // the timeout resets the queue and drops the compiler
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while !stuck.isCancelled && System.nanoTime() < deadline do Thread.sleep(20)
    Assert.assertTrue("the first job timed out", stuck.isCancelled)

    // a request on the new queue, holding the new compiler while the backlog resumes
    val fresh = access.withNonInterruptableCompiler((), EmptyCancelToken) { _ =>
      onDriver:
        newStarted.countDown()
        backlogStarted.await(300, TimeUnit.MILLISECONDS) // long enough to see the backlog join it
    }
    Assert.assertTrue(newStarted.await(5, TimeUnit.SECONDS))
    releaseOld.countDown()
    fresh.get(5, TimeUnit.SECONDS)
    Assert.assertTrue("the backlog ran", backlogStarted.await(5, TimeUnit.SECONDS))
    Assert.assertEquals("jobs on the driver at once", 1, maxInFlight.get)
