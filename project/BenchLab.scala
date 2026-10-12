import sbt.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** Commands for bench-lab (see bench-lab/README.md).
 *
 *  `benchLabXctrace <template> <jmh args>` runs the JMH benchmarks under `XctraceProfiler`, keeping the traces and the
 *  JSON results in `out/bench-lab/<template>`. The command line is built here and pushed as one `Exec`, because sbt
 *  splits a typed command line at the `;` that separates the profiler's options. `<template>` is a file in
 *  `bench-lab/templates` without `.template.xml` (`ipc`, `frontend`, `mem-l1d`) or `cpu-counters` for Instruments'
 *  stock CPU Counters template (top-down breakdown).
 */
object BenchLab {
  private val parser: State => Parser[(String, String)] = _ =>
    (token("benchLabXctrace") ~> Space ~> token(NotSpace, "<template>")) ~ (Space ~> any.+.string).?.map(_.getOrElse(""))

  val xctraceCommand: Command = Command.arb(parser) { case (s, (template, jmhArgs)) =>
    val root = Project.extract(s).get(ThisBuild / Keys.baseDirectory)
    val tpl =
      if (template == "cpu-counters") "CPU Counters"
      else (root / "bench-lab" / "templates" / s"$template.template.xml").getAbsolutePath
    val out = root / "out" / "bench-lab" / template
    IO.createDirectory(out)
    val run = s"""scala3-bench-lab/Jmh/run -rf json -rff ${out / "result.json"} """ +
      s"""-prof "dotty.tools.benchlab.xctrace.XctraceProfiler:template=$tpl;dir=$out" $jmhArgs"""
    s.copy(remainingCommands = Exec(run, None) +: s.remainingCommands)
  }
}
