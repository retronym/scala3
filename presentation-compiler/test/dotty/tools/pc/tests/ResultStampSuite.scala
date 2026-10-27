package dotty.tools.pc.tests

import java.io.File
import java.net.URI

import scala.language.unsafeNulls

import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.util.SourceFile
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** A request reads the typed tree after `runUpTo` has left the read lock; a write action between the two (another
 *  request's run) replaced what it read. With parallel readers the request is served again, and its result is read
 *  from the state the write left.
 */
class ResultStampSuite:

  private def newDriver(): InteractiveDriver =
    new InteractiveDriver(List("-color:never", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator)), CachedLogicalPackage.none)

  @Test def `a write after the read serves the request again`(): Unit =
    val driver = newDriver()
    val a = URI("file:///A.scala")
    val b = URI("file:///B.scala")
    val src = SourceFile.virtual(a, "object A { def x = 1 }")
    driver.run(a, src)
    var tries = 0
    val runId = driver.withCancelChecker(() => false) {
      tries += 1
      driver.runUpTo(a, src, Span(15))
      if tries == 1 then // another request's run, between this request's read and its result
        val t = new Thread(() => { driver.run(b, "object B"); () })
        t.start(); t.join()
      driver.currentCtx.runId
    }
    assertEquals("tries", 2, tries)
    assertEquals("retries", 1, driver.staleRetries.get)
    assertEquals("the result is read from the state the write left", driver.currentCtx.runId, runId)

  @Test def `no write, no retry`(): Unit =
    val driver = newDriver()
    val a = URI("file:///A.scala")
    val src = SourceFile.virtual(a, "object A { def x = 1 }")
    driver.run(a, src)
    var tries = 0
    driver.withCancelChecker(() => false) { tries += 1; driver.runUpTo(a, src, Span(15)) }
    assertEquals("tries", 1, tries)
