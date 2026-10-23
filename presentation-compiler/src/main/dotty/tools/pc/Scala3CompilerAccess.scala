package dotty.tools.pc

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContextExecutor
import scala.language.unsafeNulls
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.pc.CompilerAccess
import scala.meta.internal.pc.CompilerWrapper
import scala.meta.pc.CancelToken
import scala.meta.pc.PresentationCompilerConfig

import dotty.tools.dotc.reporting.StoreReporter

class Scala3CompilerAccess(
    config: PresentationCompilerConfig,
    sh: Option[ScheduledExecutorService],
    newCompiler: () => Scala3CompilerWrapper
)(using ec: ExecutionContextExecutor)
    extends CompilerAccess[StoreReporter, CachingDriver](
      config,
      sh,
      newCompiler,
      /* If running inside the executor, we need to reset the job queue
       * Otherwise it will block indefinetely in case of infinite loops.
       */
      shouldResetJobQueue = true
    ):

  def newReporter = new StoreReporter(null)

  // Under `-Yinteractive-log`, one line per request on stderr when it leaves
  // the job queue: how long it waited, and whether it waited behind another
  // request (one that was still running when this one was submitted) or the
  // queue was idle. `CompilerJobQueue` runs the most recently submitted
  // request first, so the wait is the time an editor's request spends behind
  // the requests submitted after it and the one running when it arrived.
  private val requestSeq = new AtomicInteger
  @volatile private var lastJob: String = "nothing"
  @volatile private var lastJobEnded: Long = 0L

  // The `ScalaPresentationCompiler` method that submitted the request.
  private def submitter: String =
    Thread.currentThread.getStackTrace.iterator
      .find(_.getClassName == "dotty.tools.pc.ScalaPresentationCompiler")
      .map(_.getMethodName).getOrElse("?")

  private def queued[T](
      thunk: CompilerWrapper[StoreReporter, CachingDriver] => T
  ): CompilerWrapper[StoreReporter, CachingDriver] => T =
    val label = s"$submitter#${requestSeq.incrementAndGet()}"
    val submitted = System.nanoTime()
    access =>
      val driver = access.compiler()
      if !driver.logRequests then thunk(access)
      else
        val started = System.nanoTime()
        val behind = if lastJobEnded > submitted then s"behind $lastJob" else "queue idle"
        System.err.println(f"[dotc-queue] $label started after ${(started - submitted) / 1e6}%.1f ms in the queue, $behind")
        try thunk(access)
        finally
          val ended = System.nanoTime()
          lastJob = f"$label (${(ended - started) / 1e6}%.1f ms)"
          lastJobEnded = ended
          System.err.println(f"[dotc-queue] $label done after ${(ended - started) / 1e6}%.1f ms")

  override def withNonInterruptableCompiler[T](
      default: T,
      token: CancelToken
  )(
      thunk: CompilerWrapper[StoreReporter, CachingDriver] => T
  )(implicit queryInfo: PcQueryContext): CompletableFuture[T] =
    super.withNonInterruptableCompiler(default, token)(queued(thunk))

  /** Interruptable requests abort their compilation when the token is
   *  cancelled: the driver polls the token from the typer (see
   *  `InteractiveDriver.withCancelChecker`) and unwinds with a
   *  `CancelledCompilation`, which `withSharedCompiler` maps to `default`
   *  while keeping the compiler instance.
   */
  override def withInterruptableCompiler[T](
      default: T,
      token: CancelToken
  )(
      thunk: CompilerWrapper[StoreReporter, CachingDriver] => T
  )(implicit queryInfo: PcQueryContext): CompletableFuture[T] =
    val cancelled = token.onCancel().toCompletableFuture
    val isCancelled: () => Boolean =
      () => cancelled.isDone && cancelled.getNow(false)
    super.withInterruptableCompiler(default, token)(queued { access =>
      access.compiler().withCancelChecker(isCancelled)(thunk(access))
    })

  /** One job at a time on a driver. The queue alone does not ensure it: a
   *  request that times out resets the job queue and drops the compiler, but
   *  the old queue's executor is only shut down, so its backlog still runs on
   *  the old queue's thread, and those jobs load the same new compiler as the
   *  jobs on the new queue. A job stuck on a dropped compiler holds only that
   *  compiler's lock, so the reset still gets the requests going again.
   */
  override def withSharedCompiler[T](
      default: T
  )(
      thunk: CompilerWrapper[StoreReporter, CachingDriver] => T
  )(implicit queryInfo: PcQueryContext): T =
    super.withSharedCompiler(default) { access =>
      access.compiler().synchronized(thunk(access))
    }

  /** Handle the exception in order to make sure that we retry immediately.
   *  Otherwise, we will wait until the end of the timeout, which is 20s by
   *  default.
   */
  protected def handleSharedCompilerException(
      t: Throwable
  ): Option[String] = None

  protected def ignoreException(t: Throwable): Boolean = false
end Scala3CompilerAccess
