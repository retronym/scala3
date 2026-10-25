package dotty.tools.pc.tests

import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.{Callable, ExecutorService, Executors}

import scala.language.unsafeNulls

import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.pc.base.BasePCSuite
import dotty.tools.pc.base.TestResources

import org.junit.Assert.*
import org.junit.Test

/** A pool thread that lives for the session (Metals runs the presentation
 *  compiler's jobs on such threads) must not keep a replaced driver alive:
 *  the context base's per-thread state (the reader, the implicit search
 *  roots') is a `ThreadLocal` value, which the thread's own map holds
 *  strongly, and the value reaches the base that owns the key.
 */
class DriverThreadLocalLeakSuite extends BasePCSuite:

  private val source =
    """object A:
      |  def f(xs: List[Int]): Int = xs.map(_ + 1).sum
      |  val s: String = f(List(1, 2)).toString
      |""".stripMargin

  private def settings(extra: String*) =
    List("-color:never", "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator)) ++ extra

  /** Type `source` on `worker` under a driver, and return the driver's context base, weakly. */
  private def typeOn(worker: ExecutorService, extra: Seq[String], scoped: Boolean): WeakReference[AnyRef] =
    val driver = new InteractiveDriver(settings(extra*), CachedLogicalPackage.none)
    val uri = new URI("file:///A.scala")
    val diags = worker.submit(new Callable[Int]:
      def call(): Int =
        def run = driver.run(uri, SourceFile.virtual(uri, source)).size
        if scoped then driver.withCancelChecker(() => false)(run) else run
    ).get()
    assertEquals(0, diags, "the file types cleanly")
    WeakReference(driver.currentCtx.base)

  private def collected(ref: WeakReference[AnyRef]): Boolean =
    var i = 0
    while ref.get != null && i < 20 do
      System.gc()
      Thread.sleep(50)
      i += 1
    ref.get == null

  private def check(extra: String*): Unit =
    val worker = Executors.newSingleThreadExecutor()
    try
      val ref = typeOn(worker, extra, scoped = true)
      assertTrue("the replaced driver's context base is still reachable from the live worker thread", collected(ref))
    finally worker.shutdown()

  @Test def `driver-not-retained-by-live-thread`(): Unit = check()

  @Test def `driver-not-retained-by-live-thread-independent-readers`(): Unit = check("-Yinteractive-readers")
