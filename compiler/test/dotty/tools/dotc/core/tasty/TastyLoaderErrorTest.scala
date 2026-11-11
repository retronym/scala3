package dotty.tools.dotc.core.tasty

import java.nio.file.Files

import org.junit.Test
import org.junit.Assert.*

import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.Diagnostic.ERROR
import dotty.tools.dotc.reporting.TestReporter
import dotty.tools.tasty.TastyFormat.*
import dotty.tools.vulpix.TestConfiguration

class TastyLoaderErrorTest {

  /** A `.tasty` file that cannot be unpickled should be reported as a loading error naming the file,
   *  rather than crashing the compiler with the underlying `UnpickleException`.
   */
  @Test def tooNewTastyIsReportedWithPath(): Unit =
    val lib = Files.createTempDirectory("tasty-loader-error")
    val tastyFile = lib.resolve("A.tasty")
    val header = TastyHeaderUnpicklerTest.fillHeader(MajorVersion + 1, 0, 0, "Scala 99.0.0")
    Files.write(tastyFile, header.bytes.take(header.length))
    val src = Files.createTempFile("Test", ".scala")
    Files.writeString(src, "class Test { def a: A = ??? }")

    val options = TestConfiguration.defaultOptions
      .withClasspath(lib.toString)
      .and("-d", Files.createTempDirectory("tasty-loader-error-out").toString)
      .and(src.toString)
    val reporter = TestReporter.reporter(System.out, logLevel = ERROR)
    Main.process(options.all, reporter)

    val messages = reporter.errors.map(_.message).toList
    assertTrue(s"expected a loading error naming $tastyFile, got: $messages",
      messages.exists(m => m.contains(s"TASTy file $tastyFile could not be read") && m.contains("Forward incompatible TASTy file")))
}
