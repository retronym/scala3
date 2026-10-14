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
 *    (e.g. `cycles`, `cache-misses` on Linux), optionally `<event>@<interval>`; with kperf, several PMU events in one
 *    session joined by `+` (`cycles+cache-misses@5000`), each graphed separately. The library is `$ASYNC_PROFILER_LIB`, else found in the usual places.
 *    Native stacks via the VM (`cstack=vm`), vtable/itable receiver types, JIT compile tasks; flame graphs of the
 *    benchmark threads, reversed, split by thread and classified by category. Optional settings before the JMH
 *    arguments: `lib=<path>` (another libasyncProfiler, e.g. a development build), `tag=<label>` (appended to the output directory and graph titles, to tell variants apart) and `kperf=<socket>` (macOS PMU
 *    events such as `cycles` or `cache-misses` through a running `asprof-kperf` helper; adds the frame-pointer JVM
 *    flags the kernel's stack walk needs).
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

  private def jmhRun(s: State, out: File, prof: String, jmhArgs: String, andThen: String*): State =
    jmhRunAfter(s, Nil, out, prof, jmhArgs, andThen*)

  // `before`: commands to run first (e.g. building the audit agent); `prof` may be empty
  private def jmhRunAfter(s: State, before: Seq[String], out: File, prof: String, jmhArgs: String, andThen: String*): State = {
    // -o: JMH's output also goes to a file as it runs, for watching a run from outside sbt
    val profArg = if (prof.isEmpty) "" else s"""-prof "$prof" """
    val run = s"""scala3-bench-lab/Jmh/run -o ${out / "jmh.log"} -rf json -rff ${out / "result.json"} $profArg$jmhArgs"""
    s.copy(remainingCommands = (before ++ (run +: andThen)).map(Exec(_, None)).toList ++ s.remainingCommands)
  }

  /** The caller-audit agent (bench-lab/doc/caller-audit.md): bench-lab's audit classes in a jar with a Premain-Class.
   *  They stay on the benchmark's classpath too; the jar only makes them an agent.
   */
  val benchLabAuditAgent = taskKey[File]("bench-lab's caller-audit agent jar")

  def auditAgentJar: Def.Initialize[Task[File]] = Def.task {
    val classes = (Compile / Keys.classDirectory).value
    (Compile / Keys.compile).value
    val jar = (ThisBuild / Keys.baseDirectory).value / "out" / "bench-lab" / "audit-agent.jar"
    val files = ((classes / "dotty" / "tools" / "benchlab" / "audit") ** "*.class").get.map(f => f -> IO.relativize(classes, f).get)
    val manifest = new java.util.jar.Manifest()
    manifest.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest.getMainAttributes.put(new java.util.jar.Attributes.Name("Premain-Class"), "dotty.tools.benchlab.audit.AuditAgent")
    IO.jar(files, jar, manifest, None)
    jar
  }

  /** `benchLabAudit <spec> <jmh args>`: runs JMH with the audit agent and the probes of `<spec>` (a file, or a name in
   *  bench-lab/audit without `.audit`) in each fork, then merges the forks' reports (scripts/auditfold.py) into
   *  `out/bench-lab/audit-<spec>/report.md`. The counts do not depend on the iteration count: a short run will do.
   */
  val auditCommand: Command = Command.arb(parser("benchLabAudit", "<spec>")) { case (s, (specArg, jmhArgs)) =>
    val root = Project.extract(s).get(ThisBuild / Keys.baseDirectory)
    val spec = { val f = file(specArg); if (f.exists) f.getAbsoluteFile else root / "bench-lab" / "audit" / s"$specArg.audit" }
    if (!spec.exists) sys.error(s"no audit spec $specArg (nor $spec)")
    val name = spec.getName.stripSuffix(".audit")
    val out = outDir(s, s"audit-$name")
    val jar = root / "out" / "bench-lab" / "audit-agent.jar"
    val agent = s"-javaagent:$jar=spec=$spec,out=${out / "audit-{pid}.json"}"
    jmhRunAfter(s, Seq("scala3-bench-lab/benchLabAuditAgent"), out, "", s"""-jvmArgsPrepend "$agent" $jmhArgs""",
      s"benchLabAuditFold $out")
  }

  val auditFoldCommand: Command = Command.arb(parser("benchLabAuditFold", "<dir>")) { case (s, (dirArg, _)) =>
    val root = Project.extract(s).get(ThisBuild / Keys.baseDirectory)
    val dir = file(dirArg)
    val reports = (dir * "audit-*.json").get.map(_.getPath).sorted
    if (reports.isEmpty) s.log.warn(s"no audit reports in $dir (did the agent load? see jmh.log)")
    else {
      val md = new StringBuilder
      val cmd = Seq("python3", "-I", (root / "bench-lab" / "scripts" / "auditfold.py").getPath) ++ reports
      if (SysProcess(cmd).!(ProcessLogger(l => md.append(l).append('\n'), l => s.log.warn(l))) != 0) s.log.warn(s"failed: ${cmd.mkString(" ")}")
      IO.write(dir / "report.md", md.toString)
      s.log.info(s"audit report: ${dir / "report.md"} (${reports.size} forks; refold with scripts/auditfold.py --by method|outside=<package>)")
    }
    s
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

  val asyncCommand: Command = Command.arb(parser("benchLabAsync", "<events>")) { case (s, (eventsArg, rest)) =>
    // optional settings before the JMH arguments: lib=<libasyncProfiler>, kperf=<asprof-kperf socket>, tag=<label>
    val (settings, jmhArgs) = {
      val words = rest.split(" ", -1).toList
      val (kv, others) = words.span(w => w.matches("(lib|kperf|tag)=\\S+"))
      (kv.map { w => val i = w.indexOf('='); w.take(i) -> w.drop(i + 1) }.toMap, others.mkString(" "))
    }
    val events = eventsArg.split(",").toList.filter(_.nonEmpty)
    val opts = events.map {
      case "alloc" => "alloc=512k"
      case "lock" => "lock=1ms"
      case "cpu" => "event=cpu;interval=1000000" // ns
      case e if e.contains("+") => s"event=$e" // several PMU events in one session (kperf), each optionally <event>@<period>
      case e if e.contains("@") => s"event=${e.takeWhile(_ != '@')};interval=${e.dropWhile(_ != '@').tail}"
      case e => s"event=$e"
    }
    val lib = settings.get("lib").orElse(sys.env.get("ASYNC_PROFILER_LIB")).orElse(asyncLibCandidates.find(new File(_).exists))
    // tag: names a variant of the setup (e.g. a profiler build or helper mode) in the directory and graph titles
    val tag = settings.get("tag")
    val out = outDir(s, "async-" + events.mkString("-") + tag.fold("")(t => s"($t)"))
    val (raw, jvmArgs) = settings.get("kperf") match {
      case Some(sock) =>
        // macOS PMU events through the asprof-kperf helper (already running, as root, on that socket): the kernel
        // walks the stack by frame pointers, so compiled code must keep them
        (s"rawCommand=kperf=$sock",
         "-jvmArgsPrepend \"-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+PreserveFramePointer\" ")
      case None =>
        // cstack=vm: native frames walked through the VM's own structures; features: receiver classes in vtable/itable
        // stubs, the method being compiled in JIT threads' stacks, mixed Java and native frames
        // (through rawCommand: JMH's own cstack option does not know vm)
        ("rawCommand=cstack=vm,features=mixed+vtable+comptask", "")
    }
    val prof = (s"async:output=jfr;dir=$out" :: lib.map("libPath=" + _).toList ::: raw :: opts).mkString(";")
    val kinds = events.flatMap {
      case k @ ("wall" | "alloc" | "lock") => List(k)
      // per-event graphs of a multi-event session: jfrconv --hw <event>
      case e if e.contains("+") => e.split("\\+").toList.map(ev => "hw:" + ev.takeWhile(_ != '@'))
      case _ => List("cpu")
    }.distinct
    // the jfrconv built beside that library, if any, understands its JFR best
    val jfrconv = lib.map(l => new File(l).getParentFile.getParentFile / "bin" / "jfrconv").filter(_.exists).map(_.getPath).getOrElse("jfrconv")
    jmhRun(s, out, prof, jvmArgs + jmhArgs, s"benchLabJfrconv $out ${kinds.mkString(",")} $jfrconv${tag.fold("")(" " + _)}")
  }

  /** `benchLabJfrconv <dir> <kinds> [<jfrconv> [<tag>]]`: flame graphs of each JFR file under `<dir>`, for each kind
   *  of sample (`cpu`, `wall`, `alloc`, `lock`, `hw:<event>`, comma-separated), and an `index.html` linking them;
   *  `<tag>` is appended to the titles.
   */
  val jfrconvCommand: Command = Command.arb(parser("benchLabJfrconv", "<dir>")) { case (s, (dirArg, rest)) =>
    val dir = file(dirArg)
    val (kindsArg, jfrconv, tag) = rest.split(" ").toList.filter(_.nonEmpty) match {
      case k :: j :: t :: _ => (k, j, s" ($t)")
      case k :: j :: _ => (k, j, "")
      case k :: _ => (k, "jfrconv", "")
      case Nil => ("cpu", "jfrconv", "")
    }
    val jfrs = (dir ** "*.jfr").get.sortBy(_.getPath)
    if (jfrs.isEmpty) s.log.warn(s"no JFR files under $dir")
    val kinds = kindsArg.split(",").toList.filter(_.nonEmpty)
    // the graphs of one recording, for the index: (kind label, variant, file)
    val graphs = for (jfr <- jfrs) yield {
      val base = jfr.getPath.stripSuffix(".jfr")
      val bench = benchLabel(jfr)
      jfr -> (for (kind <- kinds; (variant, extra) <- variants) yield {
        // hw:<event>: one event of a multi-event hardware counter session
        val (kindArgs, label) =
          if (kind.startsWith("hw:")) (Seq("--hw", kind.drop(3)), "hw-" + kind.drop(3)) else (Seq(s"--$kind"), kind)
        val out = file(s"$base-$label${if (variant == "bench") "" else "-" + variant}.html")
        val title = s"$bench: ${label.stripPrefix("hw-")}, $variant$tag"
        val cmd = Seq(jfrconv) ++ kindArgs ++ Seq("--minwidth", "0.2", "--title", title) ++ extra ++ Seq(jfr.getPath, out.getPath)
        if (SysProcess(cmd).!(ProcessLogger(_ => ())) != 0) s.log.warn(s"failed: ${cmd.mkString(" ")}")
        (label.stripPrefix("hw-"), variant, out)
      })
    }
    writeIndex(dir, graphs)
    writeRootIndex(dir.getParentFile)
    s.log.info(s"flame graphs in $dir, index: ${dir / "index.html"}; all runs: ${dir.getParentFile / "index.html"}")
    s
  }

  // The benchmark threads, by name (JMH's stub frame is missing from kernel-walked kperf stacks); the rest are JIT, GC
  // and JMH itself. Then all threads, split by thread and by category (GC, JIT compilation, interpreter, ...).
  private val variants = List(
    "bench" -> Seq("--threads", "-I", ".*jmh-worker.*"),
    "reverse" -> Seq("--threads", "-I", ".*jmh-worker.*", "--reverse"),
    "threads" -> Seq("--threads"),
    "classify" -> Seq("--classify"))

  /** `ParserBenchmark.parse corpus=scalaz` from JMH's per-benchmark directory `<class>.<method>-<mode>-<param>-<value>...` */
  private def benchLabel(jfr: File): String = {
    val name = jfr.getParentFile.getName.stripPrefix("dotty.tools.benchlab.")
    name.split("-", -1).toList match {
      case bench :: _ :: params => (bench :: params.grouped(2).map(_.mkString("=")).toList).mkString(" ")
      case _ => name
    }
  }

  private def esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

  private val style =
    """:root { --bg: #f6f7f9; --panel: #fff; --fg: #1d232b; --muted: #5d6875; --line: #dde2e8; --link: #2f6fb2; }
      |@media (prefers-color-scheme: dark) { :root { --bg: #14181d; --panel: #1b2027; --fg: #e3e8ee; --muted: #94a0ad; --line: #2c333c; --link: #6ea8e6; color-scheme: dark; } }
      |body { margin: 0; background: var(--bg); color: var(--fg); font: 14px/1.5 -apple-system, system-ui, sans-serif; }
      |main { max-width: 960px; margin: 0 auto; padding: 20px 16px 40px; display: grid; gap: 14px; }
      |h1 { font-size: 20px; margin: 0; overflow-wrap: anywhere; } h2 { font-size: 15px; margin: 0 0 8px; overflow-wrap: anywhere; } a { color: var(--link); }
      |section { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 14px 16px; }
      |th { text-align: left; font-weight: 600; padding: 2px 16px 2px 0; } .k { color: var(--muted); font-size: 12px; margin: 8px 0 0; }
      |""".stripMargin

  /** `<root>/index.html`: every run directory under `<root>` that has an index, newest first, with its JMH scores. */
  private def writeRootIndex(root: File): Unit = {
    val runs = IO.listFiles(root).filter(d => (d / "index.html").exists).sortBy(d => -(d / "index.html").lastModified)
    def scores(d: File): String = {
      // the primary metric of each benchmark in result.json: name, params, score (the first "score" after a benchmark)
      val f = d / "result.json"
      if (!f.exists) "" else {
        val json = IO.read(f)
        json.split("\"jmhVersion\"").toList.drop(1).flatMap { r =>
          val bench = "\"benchmark\"\\s*:\\s*\"[^\"]*\\.([^.\"]+\\.[^.\"]+)\"".r.findFirstMatchIn(r).map(_.group(1))
          val params = "\"params\"\\s*:\\s*\\{([^}]*)\\}".r.findFirstMatchIn(r).map(_.group(1)).getOrElse("")
            .split(",").map(_.replaceAll("[\"\\s]", "").replace(":", "=")).filter(p => p.nonEmpty && !p.endsWith("=")).mkString(" ")
          val score = "\"primaryMetric\"\\s*:\\s*\\{\\s*\"score\"\\s*:\\s*([0-9.eE+-]+)\\s*,\\s*\"scoreError\"\\s*:\\s*([0-9.eE+-]+|\"NaN\")".r.findFirstMatchIn(r)
          for (b <- bench; m <- score) yield {
            val err = scala.util.Try(m.group(2).toDouble).toOption.fold("")(e => f" ± $e%.0f")
            f"$b $params: ${m.group(1).toDouble}%.1f$err"
          }
        }.mkString("; ")
      }
    }
    val rows = runs.map { d =>
      val when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date((d / "index.html").lastModified))
      s"""<section><h2><a href="${esc(d.getName)}/index.html">${esc(d.getName)}</a></h2><p class="k">$when · ${esc(scores(d))}</p></section>"""
    }
    IO.write(root / "index.html",
      s"""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
         |<title>bench-lab runs</title>
         |<style>$style</style>
         |<main><h1>bench-lab runs</h1>
         |<p class="k">One section per profile run (latest first): its flame graphs and JMH scores (ms/op).</p>
         |${rows.mkString("\n")}
         |</main>
         |""".stripMargin)
  }

  private def writeIndex(dir: File, graphs: Seq[(File, List[(String, String, File)])]): Unit = {
    val sections = graphs.map { case (jfr, gs) =>
      val rows = gs.groupBy(_._1).toList.sortBy { case (k, _) => gs.indexWhere(_._1 == k) }.map { case (kind, vs) =>
        val links = vs.map { case (_, v, f) => s"""<a href="${esc(IO.relativize(dir, f).getOrElse(f.getPath))}">$v</a>""" }
        s"<tr><th>${esc(kind)}</th><td>${links.mkString(" · ")}</td></tr>"
      }
      val jfrLink = esc(IO.relativize(dir, jfr).getOrElse(jfr.getPath))
      s"""<section><h2>${esc(benchLabel(jfr))}</h2><table>${rows.mkString}</table><p class="k">recording: <a href="$jfrLink">${esc(jfr.getName)}</a></p></section>"""
    }
    IO.write(dir / "index.html",
      s"""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
         |<title>${esc(dir.getName)}</title>
         |<style>$style</style>
         |<main><h1>${esc(dir.getName)}</h1>
         |<p class="k"><a href="../index.html">all runs</a></p>
         |<p class="k">bench: benchmark threads · reverse: the same, by leaf · threads: all threads · classify: all threads by category</p>
         |${sections.mkString("\n")}
         |</main>
         |""".stripMargin)
  }

  val commands: Seq[Command] = Seq(xctraceCommand, asyncCommand, jfrconvCommand, auditCommand, auditFoldCommand)
}
