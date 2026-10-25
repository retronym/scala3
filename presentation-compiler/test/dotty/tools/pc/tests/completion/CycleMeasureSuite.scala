package dotty.tools.pc.tests.completion

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}

import scala.language.unsafeNulls

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Completion, InteractiveDriver}
import dotty.tools.dotc.util.{SourceFile, SourcePosition}
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.base.BasePCSuite

import org.junit.Test

/** A measurement, not a check: targeted completions at many points of real
 *  compiler sources (`Typer.scala`, `Namer.scala` of this very checkout,
 *  against the compiler's own classes on the test JVM's classpath), counting
 *  the cyclic references prevented and the completions unwound per request,
 *  the failures, and the time. Skipped when the sources are not found.
 */
class CycleMeasureSuite extends BasePCSuite:

  private def sourceRoot: Option[Path] =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .map(_.resolve("compiler/src/dotty/tools/dotc/typer")).find(Files.isDirectory(_))

  @Test def `measure` =
    sourceRoot match
      case None => println("[cycle-measure] compiler sources not found, skipped")
      case Some(root) =>
        val settings = List("-color:never", "-Ximport-suggestion-timeout", "0",
          "-classpath", sys.props("java.class.path"))
        val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
        for name <- List("Typer.scala", "Namer.scala") do
          val path = root.resolve(name)
          val code = Files.readString(path)
          val uri = path.toUri
          val source = SourceFile.virtual(uri, code)
          // every 40th identifier end: a completion of that identifier's prefix
          val idents = raw"[A-Za-z_][A-Za-z0-9_]*".r.findAllMatchIn(code).map(_.end).toList
          val points = idents.grouped(40).map(_.head).toList
          var cycles = 0; var unwound = 0; var failed = 0; var empty = 0; var requests = 0
          var ms = 0L; var maxMs = 0L
          var runId = -1; var fresh = 0
          val cyclic = scala.collection.mutable.Map.empty[String, Int]
          for p <- points do
            requests += 1
            val t0 = System.nanoTime()
            try
              driver.runUpTo(uri, source, Span(p))
              val id = driver.runIdOf(uri)
              if id != runId then { fresh += 1; runId = id }
              val run = driver.currentCtx.run
              val before = (run.cyclesPrevented.size, run.completionsUnwound.size)
              val items = driver.compilationUnits.get(uri) match
                case Some(unit) =>
                  given Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using driver.currentCtx))
                  Completion.completions(SourcePosition(source, Span(p)))._2
                case None => Nil
              if items.isEmpty then empty += 1
              val after = (run.cyclesPrevented.size, run.completionsUnwound.size)
              cycles += after._1 - before._1; unwound += after._2 - before._2
              given Context = driver.currentCtx
              run.cyclesPrevented.drop(before._1).foreach(s => cyclic(s.name.toString) = cyclic.getOrElse(s.name.toString, 0) + 1)
            catch case scala.util.control.NonFatal(ex) =>
              failed += 1
              if failed <= 5 then println(s"[cycle-measure] $name@$p failed: ${ex.getClass.getName}: ${ex.getMessage.take(120)}")
            val d = (System.nanoTime() - t0) / 1000000
            ms += d; maxMs = math.max(maxMs, d)
          println(s"[cycle-measure] $name: ${code.linesIterator.size} lines, $requests completions, $fresh runs, ${ms}ms total (max ${maxMs}ms), $empty empty, $failed failed; cyclic references prevented: $cycles (${cyclic.toList.sortBy(-_._2).take(8).mkString(", ")}); completions unwound: $unwound")
