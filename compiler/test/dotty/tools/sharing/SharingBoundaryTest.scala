package dotty.tools.sharing

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import org.junit.Assert.*
import org.junit.Test

import dotty.tools.dotc.Main
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.vulpix.TestConfiguration

/** `dotty.tools.sharing` depends on nothing in the compiler: its sources compile on their own, against the Scala
 *  library alone (docs/_docs/internals/interactive-multi-reader.md §11). Until the package is a module of its own,
 *  built before the compiler, this is the check that keeps it so.
 */
class SharingBoundaryTest:

  private val dir = Paths.get("compiler/src/dotty/tools/sharing")

  private def sources: List[Path] =
    Files.list(dir).iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sortBy(_.toString)

  @Test def compilesAgainstTheLibraryAlone(): Unit =
    val out = Files.createTempDirectory("sharing-boundary")
    val args = List("-classpath", TestConfiguration.basicClasspath, "-d", out.toString, "-Yexplicit-nulls")
      ++ sources.map(_.toString)
    val reporter = Reporter.SilentReporter()
    Main.process(args.toArray, reporter)
    assertFalse(
      s"dotty.tools.sharing does not compile on its own:\n${reporter.allErrors.mkString("\n")}",
      reporter.hasErrors)

