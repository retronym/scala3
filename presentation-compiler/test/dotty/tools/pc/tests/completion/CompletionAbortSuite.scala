package dotty.tools.pc.tests.completion

import java.lang
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionStage}
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.*
import scala.language.unsafeNulls
import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.metals.{CompilerOffsetParams, EmptyCancelToken, PcQueryContext}
import scala.meta.internal.pc.{InterruptException, PresentationCompilerConfigImpl}
import scala.meta.pc.{CancelToken, SourcePathMode}
import scala.meta.pc.reports.EmptyReportContext

import dotty.tools.dotc.CompilationUnit.CancelledCompilation
import dotty.tools.pc.{CachingDriver, ScalaPresentationCompiler}
import dotty.tools.pc.base.BaseCompletionSuite

import org.junit.Test

/** Cancellation that aborts the compilation in progress (as opposed to
 *  `CompletionCancelSuite`, which only checks that the request's future is
 *  cancelled): the driver's run is unwound from the typer, its partial results
 *  are discarded, the driver instance survives, and the next request on the
 *  same file is answered in full.
 */
class CompletionAbortSuite extends BaseCompletionSuite:

  private val timeout = 30.seconds

  private val sourcepathDir: Path = Files.createDirectories(tmp.resolve("sourcepath"))

  locally:
    val pkg1Dir = Files.createDirectories(sourcepathDir.resolve("pkg1"))
    // `greetAlpha` has no result type: completing its info types the whole
    // right-hand side (well over 1024 trees), so a run that is cancelled while
    // typing a reference to it unwinds through the completion of a symbol that
    // outlives the run.
    val body = (0 until 400).map(i => s"    val a$i = x + $i").mkString("\n")
    Files.write(
      pkg1Dir.resolve("Alpha.scala"),
      s"""|package pkg1
          |
          |class Alpha:
          |  def greetAlpha =
          |    val x = 1
          |$body
          |    a0 + a399
          |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )

  override protected def config: PresentationCompilerConfigImpl =
    // The cold first compilation of `bigQuery` must not hit the job timeout
    super.config.copy(sourcePathMode = SourcePathMode.PRUNED, timeoutDelay = 120)
  override protected val sourcePath: Seq[Path] = Seq(sourcepathDir)
  override protected def scalacOptions(classpath: Seq[Path]): Seq[String] =
    Seq("-Ylogical-package-loading")

  private val uri = URI.create("file:///A.scala")

  /** A source whose typing takes long enough for a cancellation to land in it.
   *  `tag` makes each request's content distinct, so that `CachingDriver`
   *  compiles it rather than serving the previous request's result. The
   *  methods are local to the statement with the completion, so that a
   *  targeted run (which types only that statement) still types them.
   */
  private def bigQuery(tag: String) =
    val methods = (0 until 400).map(i => s"    def m$i(xs: List[Int]): Int = xs.map(_ + $i).filter(_ > $i).sum").mkString("\n")
    s"""|object A { // $tag
        |  val x = {
        |$methods
        |    asser@@
        |  }
        |}
        |""".stripMargin

  private val expected =
    """|assert(inline assertion: Boolean): Unit
       |assert(inline assertion: Boolean, inline message: => Any): Unit
       |""".stripMargin

  private def pc = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]
  private given PcQueryContext = PcQueryContext(None, () => "")(using EmptyReportContext())

  private def withDriver[T](f: CachingDriver => T): T =
    pc.compilerAccess.withNonInterruptableCompiler(null.asInstanceOf[T], EmptyCancelToken) { access =>
      f(access.compiler())
    }.get(timeout.length, timeout.unit)

  private def runId(): Int = withDriver(_.currentCtx.runId)
  private def driver(): CachingDriver = withDriver(identity)

  private def complete(query: String, token: CancelToken = EmptyCancelToken): String =
    val (code, offset) = params(query)
    presentationCompiler
      .complete(CompilerOffsetParams(uri, code, offset, token))
      .get(timeout.length, timeout.unit)
      .getItems.asScala.map(_.getLabel).sorted.mkString("\n")

  /** Cancels `delayMs` after the job queue starts the request (that is, in
   *  `checkCanceled`, which mtags calls right before running the thunk).
   */
  class DelayedCancelToken(delayMs: Long) extends CancelToken:
    val cancel = new CompletableFuture[lang.Boolean]()
    val started = new AtomicBoolean(false)
    override def onCancel(): CompletionStage[lang.Boolean] = cancel
    override def checkCanceled(): Unit =
      if started.compareAndSet(false, true) then
        val t = new Thread((() => { Thread.sleep(delayMs); cancel.complete(true) }): Runnable)
        t.setDaemon(true)
        t.start()

  private def inTyperCheckpoint(): Boolean =
    Thread.currentThread.getStackTrace.exists(_.getMethodName == "checkCancelledTyping")

  private def inCompletion(): Boolean =
    Thread.currentThread.getStackTrace.exists(_.getMethodName == "completeFrom")

  @Test def `abort-via-token` =
    assertNoDiff(expected, complete(bigQuery("first")))
    val driverBefore = driver()
    val runIdBefore = runId()
    println(s"[abort-via-token] runId before cancelled request: $runIdBefore")

    val token = DelayedCancelToken(20)
    try
      complete(bigQuery("cancelled"), token)
      fail("Expected completion request to be cancelled")
    catch case InterruptException() => ()

    // Queued behind the cancelled job: it sees the driver after the abort
    val runIdAfterAbort = runId()
    println(s"[abort-via-token] runId after cancelled request: $runIdAfterAbort")
    // The aborted run's context stays current (its denotations are the newest); its trees are discarded
    assertEquals(runIdBefore + 1, runIdAfterAbort, "the aborted run's context stays current")
    assert(driver() eq driverBefore, "the driver must not be recreated")

    assertNoDiff(expected, complete(bigQuery("following")))
    val runIdAfter = runId()
    println(s"[abort-via-token] runId after following request: $runIdAfter")
    // The aborted run consumed one run id, the successful one another
    assertEquals(runIdBefore + 2, runIdAfter, "the following request must compile on the same driver")
    assert(driver() eq driverBefore, "the driver must not be recreated")

  @Test def `abort-from-typer` =
    assertNoDiff(expected, complete(bigQuery("first")))
    val (code, _) = params(bigQuery("first"))
    val (thrown, typerPolls, runIdBefore, runIdAfter, openedAfter, unitsAfter) = withDriver { driver =>
      val runIdBefore = driver.currentCtx.runId
      val unitBefore = driver.compilationUnits(uri)
      var typerPolls = 0
      val thrown =
        try
          driver.withCancelChecker(() => { if inTyperCheckpoint() then typerPolls += 1; typerPolls >= 2 }) {
            driver.run(uri, code + "\n// changed")
          }
          false
        catch case _: CancelledCompilation => true
      (thrown, typerPolls, runIdBefore, driver.currentCtx.runId,
        driver.openedFiles(uri).textContent(), driver.compilationUnits(uri) eq unitBefore)
    }
    println(s"[abort-from-typer] thrown=$thrown typerPolls=$typerPolls runId $runIdBefore -> $runIdAfter")
    assert(thrown, "the run must be aborted with CancelledCompilation")
    assertEquals(2, typerPolls, "cancelled at the second typer checkpoint")
    assertEquals(runIdBefore + 1, runIdAfter, "the aborted run's context stays current")
    assertEquals(code, openedAfter, "the previous source must stay open")
    assert(unitsAfter, "the previous compilation unit must stay current")
    // Same content as before the abort, but the aborted run invalidated the
    // driver's cache, so this compiles again
    assertNoDiff(expected, complete(bigQuery("first")))
    val runIdFinal = runId()
    println(s"[abort-from-typer] runId after following request: $runIdFinal")
    assertEquals(runIdBefore + 2, runIdFinal, "the following request must compile on the same driver")

  @Test def `abort-inside-completion-of-sourcepath-symbol` =
    val query =
      """|import pkg1.Alpha
         |object Main:
         |  val a = new Alpha
         |  val g = a.greetAlpha
         |  a.greetA@@
         |""".stripMargin
    val (code, _) = params(query)
    // Cancel at the first typer checkpoint that is reached from inside a
    // symbol completion: the first 1024 trees of this run are typed while
    // completing `greetAlpha` (via `g`), whose symbol belongs to the sourcepath
    // and outlives the run. This must be the first run that enters `Alpha`,
    // otherwise `greetAlpha` is already completed.
    val (thrown, polls) = withDriver { driver =>
      var polls = 0
      val thrown =
        try
          driver.withCancelChecker(() => { if inTyperCheckpoint() && inCompletion() then polls += 1; polls >= 1 }) {
            driver.run(uri, code)
          }
          false
        catch case _: CancelledCompilation => true
      (thrown, polls)
    }
    println(s"[abort-inside-completion] thrown=$thrown polls=$polls")
    assert(thrown, "the run must be aborted with CancelledCompilation")
    // `greetAlpha` is completable again (no CyclicReference), but its Namer
    // completer was created in the aborted run and refuses to complete in a
    // later one (`Namer.Completer.complete` gives it `UnspecifiedErrorType`):
    // the item is listed with an error type. Re-entering the sourcepath file
    // in the next run would be needed for `greetAlpha: Int`.
    val obtained = complete(query + "\n// changed")
    println(s"[abort-inside-completion] completion after abort: $obtained")
    assertNoDiff("greetAlpha: Any", obtained)
