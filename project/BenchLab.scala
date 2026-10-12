import sbt.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

import scala.sys.process.{Process => SysProcess, ProcessLogger}

/** Commands for bench-lab (see bench-lab/README.md).
 *
 *  Each builds a `scala3-bench-lab/Jmh/run` command line with a profiler and pushes it as one `Exec`, because sbt
 *  splits a typed command line at the `;` that separates a profiler's options. Output goes to `out/bench-lab/<name>`,
 *  replacing the previous run's.
 *
 *  - `benchLabXctrace <template> <jmh args>`: `XctraceProfiler` (Apple Silicon PMU counters). `<template>` is a file
 *    in `bench-lab/templates` without `.template.xml` (`ipc`, `frontend`, `mem-l1d`) or `cpu-counters` for
 *    Instruments' stock CPU Counters template (top-down breakdown).
 *  - `benchLabAsync <events> <jmh args>`: async-profiler, recording JFR, then flame graphs with `jfrconv`.
 *    `<events>` is a comma-separated list of `cpu`, `wall`, `alloc`, `lock`, or another async-profiler event
 *    (e.g. `cycles`, `cache-misses` on Linux). The library is `$ASYNC_PROFILER_LIB`, else found in the usual places.
 */
object BenchLab {
  private def parser(name: String, arg: String): State => Parser[(String, String)] = _ =>
    (token(name) ~> Space ~> token(NotSpace, arg)) ~ (Space ~> any.+.string).?.map(_.getOrElse(""))

  private def outDir(s: State, name: String): File = {
    val dir = Project.extract(s).get(ThisBuild / Keys.baseDirectory) / "out" / "bench-lab" / name
    IO.delete(dir)
    IO.createDirectory(dir)
    dir
  }

  private def jmhRun(s: State, out: File, prof: String, jmhArgs: String, andThen: String*): State = {
    val run = s"""scala3-bench-lab/Jmh/run -rf json -rff ${out / "result.json"} -prof "$prof" $jmhArgs"""
    s.copy(remainingCommands = (run +: andThen).map(Exec(_, None)).toList ++ s.remainingCommands)
  }

  val xctraceCommand: Command = Command.arb(parser("benchLabXctrace", "<template>")) { case (s, (template, jmhArgs)) =>
    val root = Project.extract(s).get(ThisBuild / Keys.baseDirectory)
    val tpl =
      if (template == "cpu-counters") "CPU Counters"
      else (root / "bench-lab" / "templates" / s"$template.template.xml").getAbsolutePath
    val out = outDir(s, template)
    jmhRun(s, out, s"dotty.tools.benchlab.xctrace.XctraceProfiler:template=$tpl;dir=$out", jmhArgs)
  }

  private val asyncLibCandidates = List(
    "/opt/homebrew/lib/libasyncProfiler.dylib", "/usr/local/lib/libasyncProfiler.dylib",
    "/usr/local/lib/libasyncProfiler.so", "/usr/lib/libasyncProfiler.so")

  val asyncCommand: Command = Command.arb(parser("benchLabAsync", "<events>")) { case (s, (eventsArg, jmhArgs)) =>
    val events = eventsArg.split(",").toList.filter(_.nonEmpty)
    val opts = events.map {
      case "alloc" => "alloc=512k"
      case "lock" => "lock=1ms"
      case "cpu" => "event=cpu;interval=1000000" // ns
      case e => s"event=$e"
    }
    val lib = sys.env.get("ASYNC_PROFILER_LIB").orElse(asyncLibCandidates.find(new File(_).exists))
    val out = outDir(s, "async-" + events.mkString("-"))
    val prof = (s"async:output=jfr;dir=$out" :: lib.map("libPath=" + _).toList ::: opts).mkString(";")
    val kinds = events.map { case k @ ("wall" | "alloc" | "lock") => k; case _ => "cpu" }.distinct
    jmhRun(s, out, prof, jmhArgs, s"benchLabJfrconv $out ${kinds.mkString(",")}")
  }

  /** `benchLabJfrconv <dir> <kinds>`: flame graphs of each JFR file under `<dir>`, for each kind of sample
   *  (`cpu`, `wall`, `alloc`, `lock`, comma-separated).
   */
  val jfrconvCommand: Command = Command.arb(parser("benchLabJfrconv", "<dir>")) { case (s, (dirArg, kindsArg)) =>
    val dir = file(dirArg)
    val jfrs = (dir ** "*.jfr").get
    if (jfrs.isEmpty) s.log.warn(s"no JFR files under $dir")
    for (jfr <- jfrs; kind <- kindsArg.split(",").toList.filter(_.nonEmpty)) {
      val base = jfr.getPath.stripSuffix(".jfr")
      def conv(suffix: String, extra: String*): Unit = {
        val cmd = Seq("jfrconv", s"--$kind", "--minwidth", "0.2") ++ extra ++ Seq(jfr.getPath, s"$base-$kind$suffix.html")
        if (SysProcess(cmd).!(ProcessLogger(_ => ())) != 0) s.log.warn(s"failed: ${cmd.mkString(" ")}")
      }
      // Stacks through the JMH stub are the benchmark threads; the rest are JIT, GC and JMH itself.
      conv("", "-I", ".*jmh_generated.*")
      conv("-reverse", "-I", ".*jmh_generated.*", "--reverse")
      conv("-threads", "--threads")
    }
    s.log.info(s"flame graphs in $dir")
    s
  }

  val commands: Seq[Command] = Seq(xctraceCommand, asyncCommand, jfrconvCommand)
}
