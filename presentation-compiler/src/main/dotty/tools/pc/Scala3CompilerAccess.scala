package dotty.tools.pc

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContextExecutor
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.pc.CompilerAccess
import scala.meta.internal.pc.CompilerWrapper
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
