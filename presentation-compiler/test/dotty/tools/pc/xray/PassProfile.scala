package dotty.tools.pc.xray

import java.net.URI
import java.nio.file.{Files, Paths}

import scala.language.unsafeNulls

import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

/** The x-ray's background pass, alone, timed and optionally profiled: on a
 *  fresh driver per round (warmed up on other content), a targeted request
 *  at the start of a viewport, then `readAll` with that viewport first, on
 *  one thread in a request scope, as `XrayServer.startBackground` does but
 *  with no hovers beside it. For the 2x of the pass with
 *  `-Yinteractive-parallel` (step 4) against the serialised mode.
 *
 *    -Dpass.readers=parallel      with `-Yinteractive-parallel`; otherwise serialised
 *    -Dpass.rounds=12 -Dpass.warmup=4
 *    -Dpass.wholeFile=true        a whole-file run (`run`) instead of the pass, for its diagnostics
 *    -Dpass.profile=<file>        async-profiler (cpu, 1 ms), collapsed stacks, around the measured passes only;
 *                                 needs async-profiler.jar on the classpath and -Dpass.asyncLib=<libasyncProfiler>
 *
 *  Arguments: files relative to `compiler/src/dotty/tools/dotc` of the
 *  working directory (e.g. core/Types.scala typer/Typer.scala).
 */
object PassProfile:

  def main(args: Array[String]): Unit =
    val parallel = sys.props.get("pass.readers").contains("parallel")
    val rounds = sys.props.getOrElse("pass.rounds", "12").toInt
    val warmup = sys.props.getOrElse("pass.warmup", "4").toInt
    val settings = XrayServer.baseSettings ++ (if parallel then List("-Yinteractive-parallel") else Nil)
    val profiler: Option[String => String] = sys.props.get("pass.profile").map { _ =>
      val cls = Class.forName("one.profiler.AsyncProfiler")
      val inst = cls.getMethod("getInstance", classOf[String]).invoke(null, sys.props("pass.asyncLib"))
      val exec = cls.getMethod("execute", classOf[String])
      (cmd: String) => String.valueOf(exec.invoke(inst, cmd))
    }
    var started = false
    val root = Paths.get("compiler/src/dotty/tools/dotc")
    for file <- args do
      val code = Files.readString(root.resolve(file))
      val uri = URI.create(s"file:///pass/$file")
      // the viewport: 60 lines a third of the way into the file
      val lines = code.split('\n')
      val first = lines.length / 3
      val from = lines.take(first).map(_.length + 1).sum
      val to = from + lines.slice(first, first + 60).map(_.length + 1).sum
      val visible = Span(from, to)
      val times = List.newBuilder[(Double, Double, Int)]
      for i <- 1 to warmup + rounds do
        val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
        driver.run(XrayServer.generatedUri, SourceFile.virtual(XrayServer.generatedUri, XrayServer.generatedSource("warmup", 5)))
        val source = SourceFile.virtual(uri, code)
        val measured = i > warmup
        if measured then profiler.foreach { p =>
          p(if !started then "start,event=cpu,interval=1ms" else "resume")
          started = true
        }
        val t0 = System.nanoTime()
        var tVisible = 0.0
        val wholeFile = sys.props.get("pass.wholeFile").contains("true")
        val diags = driver.withCancelChecker(() => false) {
          if !wholeFile then driver.runUpTo(uri, source, Span(visible.start))
          tVisible = (System.nanoTime() - t0) / 1e6
          driver.readAll(uri, source, if wholeFile then dotty.tools.dotc.util.Spans.NoSpan else visible) { (_, _, ds) =>
            if sys.props.get("pass.printDiagnostics").contains("true") then
              ds.foreach(d => println(s"[diag] ${d.pos.line + 1}: ${d.msg.message.linesIterator.next()}"))
            ds.size
          }
        }
        val ms = (System.nanoTime() - t0) / 1e6
        if measured then
          profiler.foreach(_("stop"))
          times += ((tVisible, ms, diags))
        println(f"[pass] ${if parallel then "parallel" else "serialised"} $file round $i${if measured then "" else " (warm-up)"}: visible ${tVisible}%.0f ms, whole ${ms}%.0f ms, $diags diagnostics")
      val ts = times.result()
      def med(xs: List[Double]) = xs.sorted.apply(xs.size / 2)
      profiler.foreach(p => println(s"[pass] profile: ${p(s"dump,collapsed,file=${sys.props("pass.profile")}")}"))
      println(f"[pass] SUMMARY ${if parallel then "parallel" else "serialised"} $file: visible median ${med(ts.map(_._1))}%.0f ms, whole median ${med(ts.map(_._2))}%.0f ms (min ${ts.map(_._2).min}%.0f, max ${ts.map(_._2).max}%.0f), ${ts.size} rounds")
