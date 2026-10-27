package dotty.tools.pc.xray

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.language.unsafeNulls
import scala.jdk.CollectionConverters.*

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import dotty.tools.dotc.{CompilationUnit, Run}
import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Completion, Interactive, InteractiveDriver}
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.util.{SourceFile, SourcePosition}
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.tests.buildinfo.BuildInfo

/** A toy IDE over the patched `InteractiveDriver` (targeted typing along the
 *  path to the target, resumable runs, cancellation that unwinds): one page, one virtual file `A.scala`,
 *  and an x-ray of what the driver did for each request. Every number in the
 *  x-ray is read from the driver; nothing is simulated.
 *
 *  Run from the scala3 worktree with the session patch applied:
 *    sbt "scala3-presentation-compiler/Test/runMain dotty.tools.pc.xray.XrayServer"
 *  then open http://127.0.0.1:8791/ (`-Dxray.port` to change). The explorer
 *  shows `out/xray-sources` if it exists (a snapshot of the base commit's
 *  sources), else the worktree's own compiler sources.
 */
object XrayServer:

  val generatedUri: URI = URI.create("file:///A.scala")
  val objectCount = 60

  /** A snapshot of the compiler's sources at the base commit, for the
   *  real-sources mode: `out/xray-sources`, made from the worktree root with
   *  `git archive d168df2a76 compiler/src/dotty/tools/dotc | tar -x -C out/xray-sources`
   *  (found by walking up from the working directory), or `-Dxray.sources=<dir>`.
   *  A snapshot rather than the live checkout, so that the files do not move
   *  under the page and the patch's own edits are not what is shown.
   */
  private[xray] val sourcesRoot: Option[Path] =
    sys.props.get("xray.sources").map(Paths.get(_)).filter(Files.isDirectory(_)).orElse(
      Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
        .map(_.resolve("out/xray-sources/compiler/src/dotty/tools/dotc")).find(Files.isDirectory(_))).orElse(
      // no snapshot: the worktree's own compiler sources (with the patch's edits)
      Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
        .map(_.resolve("compiler/src/dotty/tools/dotc")).find(Files.isDirectory(_)))

  /** Paths of the snapshot's sources, relative to the root, package by package. */
  private def sourceFiles(): List[String] = sourcesRoot match
    case None => Nil
    case Some(root) =>
      val s = Files.walk(root)
      try s.iterator().asScala.filter(f => f.toString.endsWith(".scala")).map(f => root.relativize(f).toString).toList.sorted
      finally s.close()

  /** The snapshot's top-level definitions, by fully qualified name, from
   *  one parse pass over every file (built on the first definition request):
   *  what a go-to-definition needs to find the file of a symbol; the
   *  definition inside the file is then found by a parse of that file.
   */
  private lazy val definitionIndex: Map[String, String] = sourcesRoot match
    case None => Map.empty
    case Some(root) =>
      given Context = driver.currentCtx
      val t0 = System.nanoTime()
      val index = Map.newBuilder[String, String]
      var files = 0
      for rel <- sourceFiles() do
        val code = Files.readString(root.resolve(rel))
        val source = SourceFile.virtual(root.resolve(rel).toUri, code)
        val tree = new Parsers.Parser(source)(using ctx.fresh.setSource(source)).parse()
        files += 1
        def walk(t: untpd.Tree, pkg: String): Unit = t match
          case untpd.PackageDef(pid, stats) =>
            val name = pid.show
            val pkg1 = if pkg.isEmpty then name else s"$pkg.$name"
            stats.foreach(walk(_, if name == "<empty>" then pkg else pkg1))
          case m: untpd.ModuleDef => index += (s"$pkg.${m.name}" -> rel)
          case d: untpd.TypeDef => index += (s"$pkg.${d.name}" -> rel)
          case d: untpd.DefDef => index += (s"$pkg.${d.name}" -> rel)
          case v: untpd.ValDef => index += (s"$pkg.${v.name}" -> rel)
          case _ =>
        walk(tree, "")
      val result = index.result()
      println(f"[xray] definition index: ${result.size} top-level definitions in $files files, ${(System.nanoTime() - t0) / 1e6}%.0f ms")
      result

  /** Find the definition named by `chain` (the owner chain of names from the
   *  top-level definition down to the symbol) in `rel`, by a parse of it:
   *  the span of the innermost matching definition.
   */
  private def locateIn(rel: String, chain: List[String]): Option[(Int, Int)] = sourcesRoot.flatMap { root =>
    given Context = driver.currentCtx
    val code = Files.readString(root.resolve(rel))
    val source = SourceFile.virtual(root.resolve(rel).toUri, code)
    val tree = new Parsers.Parser(source)(using ctx.fresh.setSource(source)).parse()
    def nameOf(t: untpd.Tree): Option[String] = t match
      case m: untpd.ModuleDef => Some(m.name.toString)
      case d: untpd.TypeDef => Some(d.name.toString)
      case d: untpd.DefDef => Some(d.name.toString)
      case v: untpd.ValDef => Some(v.name.toString)
      case _ => None
    def body(t: untpd.Tree): List[untpd.Tree] = t match
      case m: untpd.ModuleDef => m.impl.body
      case d: untpd.TypeDef => d.rhs match { case impl: untpd.Template => impl.body; case _ => Nil }
      case _ => Nil
    def stats(t: untpd.Tree): List[untpd.Tree] = t match
      case untpd.PackageDef(_, ss) => ss.flatMap(s => s match { case _: untpd.PackageDef => stats(s); case _ => List(s) })
      case _ => Nil
    def find(candidates: List[untpd.Tree], chain: List[String]): Option[untpd.Tree] = chain match
      case Nil => None
      case name :: rest =>
        val here = candidates.filter(c => nameOf(c).contains(name))
        if rest.isEmpty then here.headOption
        else here.view.flatMap(h => find(body(h), rest)).headOption.orElse(here.headOption)
    find(stats(tree), chain).map(t => (t.span.start, t.span.end))
  }

  /** The uri of the file a request is on: the generated one, or a real source. */
  private def uriOf(file: String): URI =
    if file.isEmpty then generatedUri
    else sourcesRoot.map(_.resolve(file).toUri).getOrElse(generatedUri)

  /** The same shape as `CompletionSessionSuite.bigSource`, without the marker;
   *  the last object also has a `giant` method of 200 local vals, each
   *  depending on the one before and the first on `tests.macros.Latch.await`,
   *  a macro that blocks the typer while the latch file exists (see `Latch`
   *  in the testcases project): a request at the last statement types the
   *  whole chain in order (big enough for a cancellation to land, the typer
   *  polls every 1024 trees) and blocks at the macro first; a request
   *  anywhere else does neither.
   */
  def generatedSource(tag: String, n: Int = objectCount): String =
    val objects = (0 until n).map { i =>
      val giant =
        if i == n - 1 then "\n  def giant(xs: List[Int]): Int = {\n    val latched = tests.macros.Latch.await // a macro: blocks the typer only when the statement at the cursor depends on it\n" + (0 until 200).map(j => s"    val a$j = xs.map(_ + $j).filter(_ > $j).sum + ${if j == 0 then "latched" else s"a${j - 1}"}").mkString("\n") + "\n    a0 + a199\n  }"
        else ""
      s"""|object M$i {
          |  def method$i(xs: List[Int]): Int = xs.map(_ + $i).filter(_ > $i).sum$giant
          |  val r = this.meth
          |}""".stripMargin
    }.mkString("\n")
    s"// $tag\n$objects\n"

  /** The whole test JVM's classpath, so that the compiler's own sources
   *  resolve against its classes (the generated file sees it too).
   */
  /** The options the build compiles the compiler with (`thisBuildSettings`
   *  in project/Build.scala, `scalacOptions`), so that the compiler's own
   *  sources are typed as the build types them and a whole-file run's
   *  diagnostics are the build's: without `-language:implicitConversions` and
   *  `-Yexplicit-nulls` the x-ray reported warnings and errors the build never
   *  does. All but `-Werror` (a warning stays a warning in the editor). Keep
   *  in step with Build.scala.
   */
  val buildOptions: List[String] = List(
    "-feature", "-deprecation", "-unchecked",
    "-Wconf:msg=package scala contains object and package with same name:i",
    "-Wconf:src=scaladoc-testcases/.*:s",
    "-Wconf:src=presentation-compiler-testcases/.*:s",
    "-Wconf:msg=The RHS of reassignment must be transitively initialized:i",
    "-Wconf:msg=Could not verify that the method argument is transitively initialized:i",
    "-Wconf:msg=may cause initialization errors:i",
    "-Wconf:cat=deprecation&origin=scala\\.collection\\.Iterable\\.stringPrefix:s",
    "-encoding", "UTF8",
    "-language:implicitConversions",
    "--java-output-version:17",
    "-Yexplicit-nulls",
    "-Wsafe-init")

  private[xray] val baseSettings =
    buildOptions ++ List("-color:never", "-Ximport-suggestion-timeout", "0",
      "-classpath", sys.props.getOrElse("java.class.path", BuildInfo.ideTestsDependencyClasspath.map(_.toPath.toString).mkString(File.pathSeparator)))
    // `-Dxray.readers=parallel`: `-Yinteractive-parallel` (step 4); otherwise serialised (step 3)

  /** The flags of the driver the page drives by hand (freeform mode), set by
   *  `/reset?mode=` to one of the replay's modes. `-Dxray.readers=parallel`
   *  starts it with `-Yinteractive-parallel` (step 4); otherwise serialised (step 3).
   */
  @volatile private[xray] var freeformMode: Replay.Mode =
    if sys.props.get("xray.readers").contains("parallel") then Replay.modeNamed("readers") else Replay.modeNamed("default")
  private def settings: List[String] = baseSettings ++ freeformMode.flags

  @volatile private[xray] var driver = new InteractiveDriver(settings, CachedLogicalPackage.none)

  /** Reset the typer state: a fresh driver (a new symbol table, run ids from
   *  1), warmed up like the first one. */
  /** A driver for `mode`: `CachingDriver` (Metals' own, with its
   *  same-content cache) for a mode that says so, the plain driver otherwise.
   */
  private[xray] def newDriver(mode: Replay.Mode): InteractiveDriver =
    val flags = baseSettings ++ mode.flags
    if mode.cache then dotty.tools.pc.CachingDriver(flags, () => java.util.List.of[java.nio.file.Path](), null, scala.meta.pc.SourcePathMode.DISABLED)
    else new InteractiveDriver(flags, CachedLogicalPackage.none)

  private[xray] def resetDriver(mode: Replay.Mode = freeformMode): Map[String, Any] = lock.synchronized {
    val t0 = System.nanoTime()
    freeformMode = mode
    driver = newDriver(mode)
    driver.run(generatedUri, SourceFile.virtual(generatedUri, generatedSource("warmup", 5)))
    backgroundState = Map("state" -> "idle")
    Map("warmupMs" -> (System.nanoTime() - t0) / 1000000, "mode" -> mode.key, "flags" -> mode.flags.mkString(" "))
  }

  // ---- the background pass: another reader completes the session (`readAll`)
  // on its own thread, concurrently with the foreground requests (the
  // read/write regime of step 3), and reports what the typed tree holds:
  // semantic tokens and diagnostics, for the page to paint.

  @volatile private[xray] var backgroundState: Map[String, Any] = Map("state" -> "idle")
  private val foregroundCount = new java.util.concurrent.atomic.AtomicInteger(0)
  /** The background reader and what it set out to type, for the progress the
   *  page polls: the skipped statements at the start, and those still skipped. */
  @volatile private var backgroundReader: dotty.tools.dotc.Reader | Null = null
  @volatile private var backgroundSpansAtStart: List[Span] = Nil
  @volatile private var backgroundUri: URI | Null = null

  /** The lock waits of a reader, for a result: total, count, the longest. */
  private def lockWaitsOf(r: dotty.tools.dotc.Reader | Null): Map[String, Any] =
    if r == null then Map("lockWaitMs" -> 0.0, "lockWaitCount" -> 0, "lockWaits" -> Nil, "backedOff" -> 0)
    else
      val waits = r.lockWaits.iterator().asScala.toList.sortBy(-_.nanos)
      // the times the reader backed off from a wait-for cycle and was retried (step 4): a livelock shows as a growing count
      Map("lockWaitMs" -> math.round(r.lockWaitNanos / 1e5) / 10.0, "lockWaitCount" -> r.lockWaitCount, "backedOff" -> r.waitCyclesBackedOff,
        "lockWaits" -> waits.take(5).map(w => Map("ms" -> math.round(w.nanos / 1e5) / 10.0, "root" -> w.root, "current" -> w.current, "holder" -> w.holder)))

  /** Every itemised lock wait of the foreground requests since the last
   *  reset (`/lockwaits`), for the distribution in the design notes.
   */
  private val allForegroundWaits = new java.util.concurrent.ConcurrentLinkedQueue[(Double, Double, String, String, String)]()

  /** The state of the background pass, with its progress while it runs. */
  private def backgroundStatus(): Map[String, Any] =
    val st = backgroundState
    if st("state") != "running" then st
    else
      val r = backgroundReader
      val uri = backgroundUri
      val remaining = if uri == null then Nil else driver.untypedSpans(uri)
      val typingNow = if uri == null then Nil else driver.typingSpans(uri)
      val typedSoFar = backgroundSpansAtStart.filterNot(sp => remaining.exists(_ == sp) || typingNow.exists(_ == sp))
      val owner = driver.completionOwnerNow
      st ++ lockWaitsOf(r) ++ Map(
        "typedTrees" -> (if r == null then 0 else r.typedTrees),
        "spansAtStart" -> backgroundSpansAtStart.size, "spansRemaining" -> (remaining.size + typingNow.size),
        "typedSpans" -> typedSoFar.map(sp => Map("start" -> sp.start, "end" -> sp.end)),
        "typingSpans" -> typingNow.map(sp => Map("start" -> sp.start, "end" -> sp.end)),
        "completionOwner" -> owner)

  /** The diagnostics of the file in `ds` for the page, those still stale marked. */
  private def problemsOf(uri: URI, source: SourceFile, ds: List[dotty.tools.dotc.reporting.Diagnostic]): List[Map[String, Any]] =
    val stale = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[dotty.tools.dotc.reporting.Diagnostic, java.lang.Boolean])
    driver.staleDiagnostics(uri).foreach(stale.add)
    ds.filter { d => val f = d.pos.source.file; d.pos.exists && f != null && f.path == source.file.path }.map(d => Map(
      "start" -> d.pos.start, "end" -> d.pos.end,
      "level" -> (if d.level >= dotty.tools.dotc.interfaces.Diagnostic.ERROR then "error" else if d.level >= dotty.tools.dotc.interfaces.Diagnostic.WARNING then "warning" else "info"),
      "message" -> d.msg.message, "stale" -> stale.contains(d)))

  /** The diagnostics of a whole-file run of `code` in a fresh driver of its
   *  own: the count the background pass must match (cached by content).
   */
  private val oracleRuns = new java.util.concurrent.ConcurrentHashMap[String, List[(Int, String)]]()
  private def oracleDiagnostics(uri: URI, code: String): List[(Int, String)] =
    oracleRuns.computeIfAbsent(code, _ => {
      val d = new InteractiveDriver(settings, CachedLogicalPackage.none)
      val source = SourceFile.virtual(uri, code)
      d.run(uri, source).filter { dg => val f = dg.pos.source.file; dg.pos.exists && f != null && f.path == source.file.path }
        .map(dg => (dg.pos.start, dg.msg.message))
    }).nn

  /** The background pass: on `code`, the statements overlapping
   *  [visibleFrom, visibleTo) first (a targeted request at `visibleFrom` makes
   *  the session if there is none, then the session's completion types the
   *  visible skipped statements before the rest), on its own thread and
   *  reader, while the page's requests are served by another.
   */
  private def startBackground(code: String, file: String, visibleFrom: Int, visibleTo: Int): Map[String, Any] =
    if backgroundState("state") == "running" then backgroundStatus()
    else
      val uri = uriOf(file)
      val source = SourceFile.virtual(uri, code)
      val visible = if visibleFrom >= 0 && visibleTo > visibleFrom then Span(visibleFrom, visibleTo) else dotty.tools.dotc.util.Spans.NoSpan
      backgroundState = Map("state" -> "running", "code" -> code)
      backgroundUri = uri
      backgroundSpansAtStart = Nil
      backgroundReader = null
      val t = new Thread((() => {
        val t0 = System.nanoTime()
        val before = foregroundCount.get
        try
          backgroundState = driver.withCancelChecker(() => false) {
           backgroundReader = driver.readerOfThisThread
           // the visible lines first: a targeted request at their start (a
           // fresh targeted run, or served/resumed in the session)
           if visible.exists then driver.runUpTo(uri, source, Span(visible.start))
           backgroundSpansAtStart = driver.untypedSpans(uri)
           val runBefore = driver.runIdOf(uri)
           val tFirst = (System.nanoTime() - t0) / 1e6
           driver.readAll(uri, source, visible) { (ctx, unit, diags) =>
            given Context = ctx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using ctx))
            val runAfter = driver.runIdOf(uri)
            val own = diags.filter { d => val f = d.pos.source.file; d.pos.exists && f != null && f.path == unit.source.file.path }
            Map("state" -> "done", "code" -> code,
              "outcome" -> (if runAfter == runBefore && runBefore != -1 then "completed the session" else "fresh whole-file run"),
              "runId" -> ctx.runId, "ms" -> (System.nanoTime() - t0) / 1000000,
              "visibleFirstMs" -> math.round(tFirst * 10) / 10.0,
              "statements" -> backgroundSpansAtStart.size,
              "foregroundDuring" -> (foregroundCount.get - before),
              "diagnosticCount" -> own.size,
              // the unit's own `SourceFile` (the trees carry the session's, not this request's copy)
              "tokens" -> semanticTokens(unit.tpdTree, unit.source),
              "diagnostics" -> own.map(d => Map(
                "start" -> d.pos.start, "end" -> d.pos.end,
                "level" -> (if d.level >= dotty.tools.dotc.interfaces.Diagnostic.ERROR then "error" else if d.level >= dotty.tools.dotc.interfaces.Diagnostic.WARNING then "warning" else "info"),
                "message" -> d.msg.message)))
          }} ++ lockWaitsOf(backgroundReader)
          // the check: a whole-file run in a fresh driver reports the same diagnostics
          val oracle = oracleDiagnostics(uri, code)
          val mine = backgroundState.get("diagnostics") match
            case Some(ds: List[?]) => ds.collect { case m: Map[?, ?] => (m.asInstanceOf[Map[String, Any]]("start").asInstanceOf[Int], m.asInstanceOf[Map[String, Any]]("message").toString) }
            case _ => Nil
          def lineOf(o: Int) = code.substring(0, math.min(o, code.length)).count(_ == '\n') + 1
          val missing = oracle.diff(mine).map((o, m) => Map("line" -> lineOf(o), "message" -> m.take(200)))
          val extra = mine.diff(oracle).map((o, m) => Map("line" -> lineOf(o), "message" -> m.take(200)))
          backgroundState = backgroundState ++ Map("oracleDiagnosticCount" -> oracle.size,
            "diagnosticsMatch" -> (missing.isEmpty && extra.isEmpty), "missingDiagnostics" -> missing, "extraDiagnostics" -> extra)
        catch
          case ex: CompilationUnit.CancelledCompilation => backgroundState = Map("state" -> "error", "error" -> "cancelled")
          case scala.util.control.NonFatal(ex) => backgroundState = Map("state" -> "error", "error" -> ex.toString)
      }): Runnable, "xray-background")
      t.setDaemon(true); t.start()
      backgroundState

  /** The identifiers of the typed tree with a symbol, by kind, for semantic
   *  highlighting: the name part of each `Ident`, `Select` and definition.
   */
  private[xray] def semanticTokens(tree: tpd.Tree, source: SourceFile)(using Context): List[Map[String, Any]] =
    import dotty.tools.dotc.core.Flags.*
    val out = List.newBuilder[Map[String, Any]]
    def kindOf(sym: Symbol): String =
      if sym.is(Package) then "package"
      else if sym.is(Module) || sym.is(ModuleClass) then "object"
      else if sym.is(Trait) then "trait"
      else if sym.isClass then "class"
      else if sym.isType then "type"
      else if sym.is(Method) then "method"
      else if sym.is(Param) then "param"
      else if sym.is(Mutable) then "var"
      else "val"
    def add(start: Int, end: Int, sym: Symbol): Unit =
      if start >= 0 && end > start && end <= source.length then
        out += Map("start" -> start, "end" -> end, "kind" -> kindOf(sym), "info" -> (try sym.showLocated catch case scala.util.control.NonFatal(_) => sym.name.toString))
    val traverser = new tpd.TreeTraverser:
      def traverse(t: tpd.Tree)(using Context): Unit =
        if (t.source eq source) && t.span.exists && !t.span.isSynthetic then
          t match
            case t: tpd.Ident if t.symbol.exists && !t.name.isEmpty => add(t.span.start, t.span.end, t.symbol)
            // a constructor has no name in the source (`<init>`; the primary
            // one's name span is its parameter list's opening)
            case t: tpd.Select if t.symbol.exists && t.span.point >= 0 && !t.symbol.isConstructor => add(t.span.point, t.span.point + t.name.toString.length, t.symbol)
            case t: tpd.MemberDef if t.symbol.exists && !t.symbol.is(Synthetic) && !t.symbol.isConstructor =>
              val p = t.nameSpan; add(p.start, p.end, t.symbol)
            case _ =>
        traverseChildren(t)
    traverser.traverse(tree)
    out.result().distinctBy(m => (m("start"), m("end")))

  /** The trees typed under the reader of the last request
   *  (`Reader.typedTrees`): the reader is per request, so the count is the
   *  request's own, whatever run it typed in, and readable from the status
   *  thread while the request runs on another.
   */
  private def typedTreeCount(run: Run): Int = driver.readerOfLastRequest.typedTrees

  case class Stat(name: String, start: Int, end: Int)

  /** The top-level statements of `source`, by a parse of their own. */
  private def statements(source: SourceFile): List[Stat] =
    given Context = driver.currentCtx.fresh.setSource(source)
    val tree = new Parsers.Parser(source).parse()
    // `package a.b.c` at the top wraps the whole file in a nested PackageDef: show its statements
    def statsOf(t: untpd.Tree): List[untpd.Tree] = t match
      case untpd.PackageDef(_, (inner: untpd.PackageDef) :: Nil) => statsOf(inner)
      case untpd.PackageDef(_, stats) => stats
      case _ => Nil
    val stats = statsOf(tree)
    stats.map { s =>
      val name = s match
        case m: untpd.ModuleDef => s"object ${m.name}"
        case t: untpd.TypeDef => s"${if t.mods.is(dotty.tools.dotc.core.Flags.Trait) then "trait" else "class"} ${t.name}"
        case d: untpd.DefDef => s"def ${d.name}"
        case v: untpd.ValDef => s"val ${v.name}"
        case i: untpd.Import => s"import ${i.expr.show.take(30)}"
        case p: untpd.PackageDef => s"package ${p.pid.show}"
        case other => other.getClass.getSimpleName
      Stat(name, s.span.start, s.span.end)
    }

  /** The innermost definition (def, val, class, object, trait) containing
   *  `offset`, from a parse: what the page shows as the definition at the
   *  cursor (a top-level statement of a real source is the whole file).
   */
  /** The definitions of `source`, by a parse: for the structure view and the
   *  go-to-symbol picker (name, kind, offsets, nesting depth).
   */
  private def definitionsOf(source: SourceFile): List[Map[String, Any]] =
    given Context = driver.currentCtx.fresh.setSource(source)
    val tree = new Parsers.Parser(source).parse()
    val out = List.newBuilder[Map[String, Any]]
    val finder = new untpd.UntypedTreeTraverser:
      var depth = 0
      def traverse(t: untpd.Tree)(using Context): Unit =
        val kind = t match
          case m: untpd.ModuleDef => "object"
          case d: untpd.TypeDef if d.isClassDef => if d.mods.is(dotty.tools.dotc.core.Flags.Trait) then "trait" else if d.mods.is(dotty.tools.dotc.core.Flags.Enum) then "enum" else "class"
          case d: untpd.TypeDef => "type"
          case d: untpd.DefDef => "def"
          case v: untpd.ValDef => if v.mods.is(dotty.tools.dotc.core.Flags.Mutable) then "var" else "val"
          case _ => ""
        if kind.nonEmpty && t.span.exists then
          val name = t.asInstanceOf[untpd.NameTree].name.toString
          if !name.contains("$") && !t.asInstanceOf[untpd.MemberDef].mods.is(dotty.tools.dotc.core.Flags.Synthetic) then
            out += Map("name" -> name, "kind" -> kind, "start" -> t.span.start, "end" -> t.span.end, "point" -> t.span.point, "depth" -> depth)
          depth += 1
          traverseChildren(t)
          depth -= 1
        else traverseChildren(t)
    finder.traverse(tree)
    out.result()

  /** The top-level definitions of the snapshot (the definition index), for
   *  the go-to-symbol picker across files. */
  private def indexedSymbols(): List[Map[String, Any]] =
    definitionIndex.toList.sortBy(_._1).map((fqn, file) => Map("name" -> fqn.split('.').last, "fqn" -> fqn, "file" -> file))

  /** Every typed reference to the symbol of the innermost typed tree at
   *  `offset` in the unit's typed tree, by name position: the usages the
   *  editor highlights at the caret (only in the typed statements).
   */
  private[xray] def usagesAt(tree: tpd.Tree, offset: Int, source: SourceFile)(using Context): Map[String, Any] =
    val sym0 = Interactive.pathTo(tree, Span(offset)).headOption.map(_.symbol).getOrElse(NoSymbol)
    val sym = if sym0.is(dotty.tools.dotc.core.Flags.ModuleVal) then sym0.moduleClass else sym0
    if !sym.exists then Map("found" -> false)
    else
      val out = List.newBuilder[Map[String, Any]]
      def same(s: Symbol) = (s eq sym) || (s.is(dotty.tools.dotc.core.Flags.ModuleVal) && (s.moduleClass eq sym)) || (sym.is(dotty.tools.dotc.core.Flags.ModuleVal) && (sym.moduleClass eq s))
      val tr = new tpd.TreeTraverser:
        def traverse(t: tpd.Tree)(using Context): Unit =
          if (t.source eq source) && t.span.exists && !t.span.isSynthetic then
            t match
              case t: tpd.Ident if same(t.symbol) => out += Map("start" -> t.span.start, "end" -> t.span.end, "write" -> false)
              case t: tpd.Select if same(t.symbol) && t.span.point >= 0 => out += Map("start" -> t.span.point, "end" -> (t.span.point + t.name.toString.length), "write" -> false)
              case t: tpd.MemberDef if same(t.symbol) && !t.symbol.isConstructor => val p = t.nameSpan; out += Map("start" -> p.start, "end" -> p.end, "write" -> true)
              case _ =>
          traverseChildren(t)
      tr.traverse(tree)
      Map("found" -> true, "symbol" -> sym.showLocated, "usages" -> out.result().distinctBy(m => m("start")))

  private def enclosingDef(source: SourceFile, offset: Int): Map[String, Any] | Null =
    given Context = driver.currentCtx.fresh.setSource(source)
    val tree = new Parsers.Parser(source).parse()
    var best: untpd.Tree | Null = null
    var bestName = ""
    val finder = new untpd.UntypedTreeTraverser:
      def traverse(t: untpd.Tree)(using Context): Unit =
        if t.span.exists && t.span.start <= offset && offset <= t.span.end then
          t match
            case m: untpd.ModuleDef => best = t; bestName = s"object ${m.name}"
            case d: untpd.TypeDef => best = t; bestName = s"${if d.mods.is(dotty.tools.dotc.core.Flags.Trait) then "trait" else "class"} ${d.name}"
            case d: untpd.DefDef => best = t; bestName = s"def ${d.name}"
            case v: untpd.ValDef => best = t; bestName = s"val ${v.name}"
            case _ =>
          traverseChildren(t)
    finder.traverse(tree)
    best match
      case null => null
      case b => Map("name" -> bestName, "start" -> b.span.start, "end" -> b.span.end)

  /** The spans of the typed statements of the current unit of `uri`, if that
   *  unit is for `source` (after an aborted fresh run the previous unit stays
   *  current, and must not be read as typed state of the new content).
   */
  private def typedSpans(uri: URI, source: SourceFile): List[Span] =
    def spansOf(t: tpd.Tree): List[Span] = t match
      case tpd.PackageDef(_, (inner: tpd.PackageDef) :: Nil) => spansOf(inner)
      case tpd.PackageDef(_, stats) => stats.map(_.span)
      case _ => Nil
    driver.compilationUnits.get(uri) match
      case Some(unit) if unit.source.textContent() == source.textContent() => spansOf(unit.tpdTree)
      case _ => Nil

  private def isTyped(stat: Stat, spans: List[Span]): Boolean =
    spans.exists(sp => sp.exists && sp.start <= stat.start && stat.end <= sp.end)

  /** Symbols of this file in the empty package's scope: how many have `Touched`
   *  set without being completed (`isCompleting`), and how many are completed.
   */
  private def scopeCounts(uri: URI): (Int, Int, Int) =
    given Context = driver.currentCtx
    val file = driver.openedFiles.get(uri).map(_.file)
    def ofFile(s: Symbol) = file.exists(f => s.associatedFile != null && s.associatedFile.path == f.path)
    // The packages the file's symbols live in: from the root, through the package clause
    def walk(pkg: Symbol): List[Symbol] =
      pkg.info.decls.toList.flatMap { s =>
        if s.is(dotty.tools.dotc.core.Flags.Package) then walk(s.moduleClass)
        else if ofFile(s) then List(s)
        else Nil
      }
    val syms = walk(defn.RootClass)
    (syms.count(_.isCompleting), syms.count(_.isCompleted), syms.size)

  private val sb = new StringBuilder

  private def jsonStr(s: String): String =
    val b = new StringBuilder("\"")
    s.foreach {
      case '"' => b.append("\\\"")
      case '\\' => b.append("\\\\")
      case '\n' => b.append("\\n")
      case '\r' => b.append("\\r")
      case '\t' => b.append("\\t")
      case c if c < ' ' => b.append(f"\\u${c.toInt}%04x")
      case c => b.append(c)
    }
    b.append('"').toString

  private[xray] def json(v: Any): String = v match
    case null => "null"
    case s: String => jsonStr(s)
    case b: Boolean => b.toString
    case n: Int => n.toString
    case n: Long => n.toString
    case n: Double => n.toString
    case m: Map[?, ?] => m.map((k, v) => jsonStr(k.toString) + ":" + json(v)).mkString("{", ",", "}")
    case xs: Seq[?] => xs.map(json).mkString("[", ",", "]")
    case other => jsonStr(other.toString)

  private val lock = new Object
  private val requestSeq = new java.util.concurrent.atomic.AtomicInteger
  /** One line per request, enough to replay it: `#n file kind@offset ... -> outcome, result`. */
  private def logRequest(seq: Int, file: String, kind: String, offset: Int, cancel: Long, latch: Boolean, code: String, r: Map[String, Any]): Unit =
    val h = r.get("hover").collect { case m: Map[?, ?] => m.asInstanceOf[Map[String, Any]] }
    val tail = kind match
      case "typeAt" => h.map(m => s"${m.getOrElse("symbol", "")} | ${String.valueOf(m.getOrElse("type", "")).take(80)}").getOrElse("")
      case "definition" => h.map(m => if m.get("found").contains(true) then s"${m.getOrElse("symbol", "")} -> ${m.getOrElse("file", "")} [${m.getOrElse("start", "")}, ${m.getOrElse("end", "")}) ${m.getOrElse("how", "")}" else s"not found: ${m.getOrElse("reason", "")}").getOrElse("")
      case _ => r.get("completions").collect { case l: List[?] => s"${l.size} completions, first ${l.headOption.getOrElse("-")}" }.getOrElse("")
    val line = code.substring(0, math.min(offset, code.length)).count(_ == '\n') + 1
    val snippet = code.substring(math.max(0, offset - 30), math.min(code.length, offset + 10)).replace("\n", "\\n")
    println(s"[xray] #$seq ${if file.isEmpty then "A.scala" else file}:$line $kind@$offset${if cancel >= 0 then s" cancel=$cancel" else ""}${if latch then " latched" else ""} -> ${r.getOrElse("outcome", "?")} run=${r.getOrElse("runId", "?")} typedTop=${r.getOrElse("prefixBefore", "?")}->${r.getOrElse("prefix", "?")} skipped=${r.getOrElse("untypedSpans", "?")} trees=${r.getOrElse("typedTrees", "?")} backedOff=${r.getOrElse("backedOff", "?")} ${r.getOrElse("ms", "?")}ms touched=${r.getOrElse("touched", "?")}${if r.get("tainted").contains(true) then " TAINTED" else ""} | $tail${r.get("error").collect { case e: String => s" | ERROR $e" }.getOrElse("")}${r.get("where").collect { case w: String => s" | $w" }.getOrElse("")} | «$snippet»")

  /** The latch file `xray.Latch.await` polls, armed for a latched request. */
  /** The latch file the `Latch.await` macro polls: `-Dxray.latch`, else one
   *  under the temp directory, set as the property so that the macro (which
   *  runs in this JVM, inside the typer) finds it.
   */
  private val latchFile: Option[java.io.File] =
    Some(new java.io.File(sys.props.getOrElseUpdate("xray.latch",
      Paths.get(sys.props("java.io.tmpdir"), s"xray-latch-${ProcessHandle.current.pid}").toString)))
  private def armLatch(): Unit = latchFile.foreach { f => f.getParentFile.mkdirs(); f.createNewFile() }
  private def releaseLatch(): Unit = latchFile.foreach(_.delete())
  private def latched: Boolean = latchFile.exists(_.exists())

  /** The request in flight (a latched one runs on its own thread), its
   *  result once done, and the cancel flag the checker polls.
   */
  @volatile private var inFlight: Map[String, Any] | Null = null
  @volatile private var lastResult: Map[String, Any] | Null = null
  @volatile private var cancelNow = false
  @volatile private var countAtStart = 0
  @volatile private var runAtStart: Run | Null = null
  @volatile private var startedAt = 0L

  /** Trees typed by the request in flight: the reader of the request is
   *  fresh, so its counter is the request's own.
   */
  private def treesSoFar: Int =
    val run = driver.currentCtx.run
    if run == null then 0
    else typedTreeCount(run)

  private def status(): Map[String, Any] =
    val f = inFlight
    Map("inFlight" -> (f != null), "request" -> f, "latched" -> latched, "cancelRequested" -> cancelNow,
      "treesSoFar" -> (if f != null then treesSoFar else 0),
      "elapsedMs" -> (if f != null then (System.nanoTime() - startedAt) / 1000000 else 0),
      "result" -> lastResult)

  /** One request against the driver, with its x-ray. `kind` is `complete`
   *  (`runUpTo` at `offset`, then the completions there) or `full` (`run`);
   *  `cancelAfterMs >= 0` installs a cancel checker that fires that long after
   *  the request started.
   */
  def request(kind: String, code: String, offset: Int, cancelAfterMs: Long, latch: Boolean = false, file: String = ""): Map[String, Any] = lock.synchronized {
    foregroundCount.incrementAndGet()
    cancelNow = false
    if latch then armLatch() else releaseLatch()
    // (an async request without the latch runs the same way, on its own thread, cancellable through /cancel)
    inFlight = Map("kind" -> kind, "offset" -> offset, "cancelAfterMs" -> cancelAfterMs, "latch" -> latch)
    startedAt = System.nanoTime()
    locally {
      val run = driver.currentCtx.run
      runAtStart = run
      countAtStart = if run == null then 0 else typedTreeCount(run)
    }
    try
      val r = request0(uriOf(file), kind, code, offset, cancelAfterMs, file) + ("latch" -> latch) + ("cancelledByButton" -> cancelNow) + ("file" -> file)
      lastResult = r
      r
    finally
      inFlight = null
      releaseLatch()
  }

  private def request0(uri: URI, kind: String, code: String, offset: Int, cancelAfterMs: Long, file: String = ""): Map[String, Any] = {
    val source = SourceFile.virtual(uri, code)
    val stats = statements(source)
    val typedBefore = typedSpans(uri, source)
    val runIdBefore = driver.currentCtx.runId
    val sessionRunBefore = driver.runIdOf(uri)
    val prefixBefore = driver.typedTopLevelCount(uri)
    val untypedBefore = driver.untypedSpans(uri)
    val (touchedBefore, _, _) = scopeCounts(uri)

    // The run whose typer polled the checker, and its tree count at the last poll:
    // for a fresh run that is aborted this is the only trace of it.
    @volatile var polledRun: Run | Null = null
    @volatile var polledCount = 0
    var polls = 0
    val start = System.nanoTime()
    val bgRunningAtStart = backgroundState("state") == "running"
    val deadline = if cancelAfterMs >= 0 then start + cancelAfterMs * 1000000L else Long.MaxValue
    val checker: () => Boolean = () =>
      polls += 1
      val run = driver.currentCtx.run
      if run != null then
        polledRun = run
        polledCount = typedTreeCount(run)
      cancelNow || System.nanoTime() >= deadline

    val countBefore =
      val run = driver.currentCtx.run
      if run != null then typedTreeCount(run) else 0

    var cancelled = false
    var error: String | Null = null
    var completions: List[Completion] = Nil
    var hover: Map[String, Any] | Null = null
    var diagnostics = 0
    var diagnosticText: List[String] = Nil
    var union: List[dotty.tools.dotc.reporting.Diagnostic] = Nil
    def keep(ds: List[dotty.tools.dotc.reporting.Diagnostic]): Int =
      diagnosticText = ds.take(8).map(d => s"${d.pos.line + 1}: ${d.msg.message.take(160)}")
      union = ds
      ds.size
    try
      driver.withCancelChecker(checker) {
        kind match
          case "full" =>
            diagnostics = keep(driver.run(uri, source))
          case "typeAt" =>
            diagnostics = keep(driver.runUpTo(uri, source, Span(offset)))
            driver.compilationUnits.get(uri).foreach { unit =>
              given Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using driver.currentCtx))
              hover = typeAt(unit.tpdTree, offset)
            }
          case "definition" =>
            diagnostics = keep(driver.runUpTo(uri, source, Span(offset)))
            driver.compilationUnits.get(uri).foreach { unit =>
              given Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using driver.currentCtx))
              hover =
                try definitionAt(unit.tpdTree, offset, source, file)
                catch case scala.util.control.NonFatal(ex) => Map("found" -> false, "reason" -> s"definition failed: ${ex.toString.take(160)}")
            }
          case _ =>
            diagnostics = keep(driver.runUpTo(uri, source, Span(offset)))
            driver.compilationUnits.get(uri).foreach { unit =>
              given Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using driver.currentCtx))
              val pos = SourcePosition(source, Span(offset))
              completions = Completion.completions(pos)._2
            }
      }
    catch
      case _: CompilationUnit.CancelledCompilation => cancelled = true
      case scala.util.control.NonFatal(ex) => error = ex.toString
    val ms = (System.nanoTime() - start) / 1000000.0

    val runIdAfter = driver.currentCtx.runId
    val sessionRunAfter = driver.runIdOf(uri)
    val prefixAfter = driver.typedTopLevelCount(uri)
    val typedAfter = typedSpans(uri, source)
    val untypedAfter = driver.untypedSpans(uri)
    val (touched, completed, total) = scopeCounts(uri)
    val runAfter = driver.currentCtx.run
    val countAfter = if runAfter != null then typedTreeCount(runAfter) else 0
    val sameRun = runIdAfter == runIdBefore
    // the request's reader counted only this request's trees
    val typedTrees =
      if cancelled && polledRun != null then polledCount
      else countAfter

    val statRows = stats.map { st =>
      val before = isTyped(st, typedBefore)
      val after = isTyped(st, typedAfter)
      val state = if after && before then "typed" else if after then "typed-now" else "untyped"
      // Members of this statement the targeted run skipped (typed statements only)
      val skippedMembers = untypedAfter.filter(sp => st.start <= sp.start && sp.end <= st.end && !(sp.start == st.start && sp.end == st.end))
      Map("name" -> st.name, "start" -> st.start, "end" -> st.end, "state" -> state,
        "skippedMembers" -> skippedMembers.map(sp => Map("start" -> sp.start, "end" -> sp.end, "text" -> code.substring(sp.start, math.min(sp.end, sp.start + 40)).takeWhile(_ != '\n'))))
    }
    val cursorStat = stats.indexWhere(st => st.start <= offset && offset <= st.end)
    val firstUntyped = stats.indexWhere(st => !isTyped(st, typedAfter))

    val outcome =
      if error != null then "error"
      else if cancelled then
        if sessionRunAfter == -1 then "cancelled-fresh"
        else "cancelled-resume"
      else if kind == "full" then "full"
      else if !sameRun then "fresh"
      else if prefixAfter > prefixBefore || (sessionRunBefore == sessionRunAfter && untypedAfter.size != untypedBefore.size) then "resumed"
      else "served"

    val where =
      if !cancelled then null
      else if sessionRunAfter == -1 then "a fresh run, discarded whole (the driver restored the previous context)"
      else if cursorStat >= 0 then s"while typing ${stats(cursorStat).name}: it is re-parsed and re-indexed, everything typed before stays"
      else "outside any statement"

    Map(
      "kind" -> kind, "offset" -> offset, "cancelAfterMs" -> cancelAfterMs,
      "outcome" -> outcome, "where" -> where, "error" -> error,
      "runIdBefore" -> runIdBefore, "runId" -> runIdAfter, "runChanged" -> !sameRun,
      "sessionRunBefore" -> sessionRunBefore, "sessionRun" -> sessionRunAfter,
      "prefixBefore" -> prefixBefore, "prefix" -> prefixAfter,
      "fullyTyped" -> driver.isFullyTyped(uri),
      "tainted" -> driver.isTainted(uri),
      "cyclesPrevented" -> driver.readerOfLastRequest.cyclesPrevented.size,
      "statements" -> statRows, "cursorStatement" -> cursorStat,
      "cursorDef" -> (try enclosingDef(source, offset) catch case scala.util.control.NonFatal(_) => null),
      "typedStatements" -> statRows.count(_("state") != "untyped"),
      "typedNow" -> statRows.count(_("state") == "typed-now"),
      "typedTrees" -> typedTrees, "polls" -> polls,
      "untypedSpans" -> untypedAfter.size,
      "ms" -> math.round(ms * 10) / 10.0,
      "touched" -> touched, "touchedBefore" -> touchedBefore, "completed" -> completed, "scopeSize" -> total,
      "diagnostics" -> diagnostics, "diagnosticText" -> diagnosticText,
      // the session's diagnostic set (the union of its stores, §12), for the
      // Problems tab and the gutter; an edited definition's old ones are stale
      "problems" -> problemsOf(uri, source, union),
      "completions" -> completions.map(c => c.label + (if c.description.nonEmpty then " " + c.description else "")).take(50),
      "hover" -> hover,
      // what the request did with an edit (-Yinteractive-body-edits): kept the run, or why not
      "lastEdit" -> driver.lastEdit(uri)
    ) ++ {
      // this request's reader (bound on this thread by the request): its waits on the completion lock
      val r = driver.readerOfThisThread
      val lw = lockWaitsOf(r)
      r.lockWaits.iterator().asScala.foreach(w => allForegroundWaits.add((math.round(w.nanos / 1e5) / 10.0, math.round(ms * 10) / 10.0, w.root, w.current, w.holder)))
      lw ++ Map("concurrentWithBackground" -> (bgRunningAtStart || backgroundState("state") == "running"))
    }
  }

  /** The definition of the symbol of the innermost typed tree at `offset`:
   *  in this file by the symbol's own span; otherwise its top-level class
   *  through the definition index, and the member inside that file by its
   *  owner chain of names.
   */
  private def definitionAt(tree: tpd.Tree, offset: Int, source: SourceFile, file: String)(using Context): Map[String, Any] =
    val path = Interactive.pathTo(tree, Span(offset))
    val sym0 = path.headOption.map(_.symbol).getOrElse(NoSymbol)
    // the member selected, not its accessor or module val
    val sym = if sym0.is(dotty.tools.dotc.core.Flags.ModuleVal) then sym0.moduleClass else sym0
    if !sym.exists then Map("found" -> false, "reason" -> "no symbol at the offset")
    else if sym.is(dotty.tools.dotc.core.Flags.Package) then Map("found" -> false, "symbol" -> s"package ${sym.showFullName}", "reason" -> "a package has no definition to go to")
    else if !sym.owner.exists then Map("found" -> false, "symbol" -> sym.show, "reason" -> "a symbol without an owner")
    else
      val top = sym.topLevelClass
      val pkg = top.owner
      val fqn = s"${if pkg.isEffectiveRoot then "<empty>" else pkg.showFullName}.${top.name.stripModuleClassSuffix}"
      val chain = (sym.ownersIterator.takeWhile(o => o != top && !o.isEffectiveRoot).toList.reverse :+ sym)
        .filter(o => !o.isAnonymousClass && !o.isLocalDummy && !o.is(dotty.tools.dotc.core.Flags.Package))
        .map(_.name.stripModuleClassSuffix.toString)
      val chainNames = (top.name.stripModuleClassSuffix.toString :: chain).distinct
      val symbolText = s"${sym.showKind} ${sym.showFullName}"
      val inThisFile = sym.source == source || (sym.associatedFile != null && sym.associatedFile.path == source.file.path)
      if inThisFile && sym.span.exists then
        Map("found" -> true, "symbol" -> symbolText, "file" -> file, "start" -> sym.span.start, "end" -> sym.span.end, "how" -> "the symbol's span in this file")
      else definitionIndex.get(fqn) match
        case Some(rel) =>
          locateIn(rel, chainNames) match
            case Some((start, end)) => Map("found" -> true, "symbol" -> symbolText, "file" -> rel, "start" -> start, "end" -> end, "how" -> s"index: $fqn in $rel, then ${chainNames.mkString(".")} by a parse")
            case None => Map("found" -> false, "symbol" -> symbolText, "file" -> rel, "reason" -> s"$fqn is in $rel but ${chainNames.mkString(".")} was not found in it")
        case None =>
          Map("found" -> false, "symbol" -> symbolText, "reason" -> s"$fqn is not a top-level definition of the snapshot (${sym.associatedFile})")

  /** The innermost typed tree at `offset` (the path drops untyped heads: a
   *  skipped statement's inside is not there), its type and its symbol, as
   *  a hover would show them.
   */
  private def typeAt(tree: tpd.Tree, offset: Int)(using Context): Map[String, Any] =
    val path = Interactive.pathTo(tree, Span(offset))
    path.headOption match
      case None => Map("found" -> false)
      case Some(t) =>
        val sym = t.symbol
        val tpe = if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>"
        Map("found" -> true, "tree" -> t.getClass.getSimpleName, "start" -> t.span.start, "end" -> t.span.end,
          "type" -> tpe,
          "symbol" -> (if sym.exists then s"${sym.showKind} ${sym.showFullName}" else ""),
          "info" -> (if sym.exists then sym.info.show.take(300) else ""),
          "owner" -> (if sym.exists then sym.owner.showFullName else ""),
          "enclosing" -> path.collectFirst { case d: tpd.DefDef => s"def ${d.name}"; case c: tpd.TypeDef => s"${c.name}" }.getOrElse(""))


  private def findPage(): Path =
    sys.props.get("xray.page").map(Paths.get(_)).filter(Files.exists(_)).getOrElse {
      val rel = Paths.get("presentation-compiler", "test", "dotty", "tools", "pc", "xray", "xray.html")
      Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null).map(_.resolve(rel)).find(Files.exists(_))
        .getOrElse(throw new IllegalStateException(s"xray.html not found from ${Paths.get("").toAbsolutePath}; pass -Dxray.page"))
    }

  private[xray] def readBody(ex: HttpExchange): String =
    new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  /** The request body is `{"kind","code","offset","cancelAfterMs"}`; the
   *  code is sent URL-encoded as a separate field to keep the parser trivial.
   */
  private[xray] def field(body: String, name: String): Option[String] =
    body.split('&').map(_.split("=", 2)).collectFirst { case Array(k, v) if k == name => java.net.URLDecoder.decode(v, StandardCharsets.UTF_8) }

  private[xray] def reply(ex: HttpExchange, status: Int, contentType: String, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.add("Content-Type", contentType)
    ex.getResponseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(status, bytes.length)
    val out = ex.getResponseBody
    out.write(bytes); out.close()

  /** The git tip of the checkout the server runs from, and whether it has
   *  local changes, for the page's title bar: a screenshot names its binary.
   */
  private[xray] lazy val gitTip: String =
    try
      def git(args: String*) =
        val p = new ProcessBuilder(("git" +: args)*).redirectErrorStream(true).start()
        val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
        p.waitFor(); out
      val tip = git("rev-parse", "--short=10", "HEAD")
      val branch = git("rev-parse", "--abbrev-ref", "HEAD")
      val dirty = git("status", "--porcelain", "--untracked-files=no").nonEmpty
      s"$branch @ $tip${if dirty then " + local changes" else ""}"
    catch case scala.util.control.NonFatal(_) => "unknown build"

  private val httpThreads = new java.util.concurrent.atomic.AtomicInteger
  /** The HTTP requests being handled: what, on which thread, since when. */
  private val inFlightRequests = new java.util.concurrent.ConcurrentHashMap[Object, (String, Thread, Long)]()
  private def inFlightRequest(ex: HttpExchange)(handle: => Unit): Unit =
    val key = new Object
    inFlightRequests.put(key, (s"${ex.getRequestMethod} ${ex.getRequestURI}", Thread.currentThread, System.nanoTime()))
    try handle finally inFlightRequests.remove(key)

  def main(args: Array[String]): Unit =
    val port = sys.props.get("xray.port").map(_.toInt).getOrElse(8791)
    val page = findPage()
    println(s"[xray] page: $page")
    println(s"[xray] build: $gitTip")
    println(s"[xray] latch file: ${latchFile.map(_.getPath).getOrElse("none (-Dxray.latch not set: latched requests disabled)")}")
    releaseLatch()
    println(s"[xray] warming up the driver on other content")
    val t0 = System.nanoTime()
    driver.run(generatedUri, SourceFile.virtual(generatedUri, generatedSource("warmup", 5)))
    println(s"[xray] compiler sources: ${sourcesRoot.map(r => s"$r (${sourceFiles().size} files)").getOrElse("not found")}")
    println(f"[xray] warm-up full run: ${(System.nanoTime() - t0) / 1e6}%.0f ms")
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    // a thread per request: with the default executor every handler ran on the
    // dispatcher thread, and one request blocked in the driver (I61) wedged the page
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool { r =>
      val t = new Thread(r, s"xray-http-${httpThreads.incrementAndGet()}"); t.setDaemon(true); t
    })
    server.createContext("/", ex => inFlightRequest(ex)(ex.getRequestURI.getPath match
      case "/" | "/index.html" =>
        reply(ex, 200, "text/html; charset=utf-8", Files.readString(page))
      case "/source" =>
        val query = Option(ex.getRequestURI.getRawQuery).getOrElse("")
        val file = field(query, "file").getOrElse("")
        val source =
          if file.isEmpty then generatedSource("x-ray demo")
          else sourcesRoot.map(root => Files.readString(root.resolve(file))).getOrElse("// no compiler sources found")
        reply(ex, 200, "application/json", json(Map("source" -> source, "file" -> file, "objects" -> objectCount)))
      case "/files" =>
        reply(ex, 200, "application/json", json(Map("root" -> sourcesRoot.map(_.toString).getOrElse(""), "files" -> sourceFiles())))
      case "/request" if ex.getRequestMethod == "POST" =>
        val body = readBody(ex)
        val kind = field(body, "kind").getOrElse("complete")
        val code = field(body, "code").getOrElse("")
        val offset = field(body, "offset").map(_.toInt).getOrElse(0)
        val cancel = field(body, "cancelAfterMs").map(_.toLong).getOrElse(-1L)
        val latch = field(body, "latch").contains("1")
        val async = latch || field(body, "async").contains("1")
        val file = field(body, "file").getOrElse("")
        def go(): Map[String, Any] =
          val seq = requestSeq.incrementAndGet()
          val result =
            try request(kind, code, offset, cancel, latch, file)
            catch
              case scala.util.control.NonFatal(ex) => Map("outcome" -> "error", "error" -> ex.toString)
              case ex: StackOverflowError => Map("outcome" -> "error", "error" -> s"StackOverflowError at ${ex.getStackTrace.take(3).mkString(", ")}")
          logRequest(seq, file, kind, offset, cancel, latch, code, result)
          result + ("seq" -> seq)
        if async then
          // Runs on its own thread; the page polls /status and releases or cancels.
          if latch && latchFile.isEmpty then reply(ex, 400, "text/plain", "the server was started without -Dxray.latch")
          else if inFlight != null then reply(ex, 409, "application/json", json(status()))
          else
            val t = new Thread((() => { lastResult = null; go(); () }): Runnable, "xray-latched-request")
            t.setDaemon(true); t.start()
            while inFlight == null && t.isAlive do Thread.sleep(5)
            reply(ex, 202, "application/json", json(status()))
        else reply(ex, 200, "application/json", json(go()))
      case "/status" =>
        reply(ex, 200, "application/json", json(status()))
      case "/release" if ex.getRequestMethod == "POST" =>
        releaseLatch()
        reply(ex, 200, "application/json", json(status()))
      case "/symbols" =>
        val query = Option(ex.getRequestURI.getRawQuery).getOrElse("")
        val file = field(query, "file").getOrElse("")
        val code = sourcesRoot.filter(_ => file.nonEmpty).map(root => Files.readString(root.resolve(file))).getOrElse(generatedSource("x-ray demo"))
        reply(ex, 200, "application/json", json(Map("file" -> file, "definitions" -> definitionsOf(SourceFile.virtual(uriOf(file), code)), "index" -> (if field(query, "index").contains("1") then indexedSymbols() else Nil))))
      case "/usages" if ex.getRequestMethod == "POST" =>
        // the typed tree of the current unit of the file, under the read lock: a read action
        val body = readBody(ex)
        val code = field(body, "code").getOrElse("")
        val offset = field(body, "offset").map(_.toInt).getOrElse(0)
        val file = field(body, "file").getOrElse("")
        val uri = uriOf(file)
        val source = SourceFile.virtual(uri, code)
        val result =
          try driver.readUpTo(uri, source, Span(offset)) { (ctx, unit, _) =>
            given Context = ctx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using ctx))
            usagesAt(unit.tpdTree, offset, unit.source)
          }
          catch case scala.util.control.NonFatal(e) => Map("found" -> false, "error" -> e.toString)
        reply(ex, 200, "application/json", json(result))
      case "/reset" if ex.getRequestMethod == "POST" =>
        val body = readBody(ex)
        val mode = field(body, "mode").flatMap(k => Replay.modes.find(_.key == k)).getOrElse(freeformMode)
        reply(ex, 200, "application/json", json(resetDriver(mode)))
      case "/modes" =>
        reply(ex, 200, "application/json", json(Map("freeform" -> freeformMode.key, "build" -> gitTip,
          "modes" -> Replay.modes.map(m => Map("key" -> m.key, "title" -> m.title, "flags" -> m.flags.mkString(" "))))))
      case p if p.startsWith("/replay") =>
        Replay.route(ex, p)
      case p if p.startsWith("/throughput") =>
        ThroughputDemo.route(ex, p, sourcesRoot)
      case "/background" if ex.getRequestMethod == "POST" =>
        val body = readBody(ex)
        reply(ex, 202, "application/json", json(startBackground(field(body, "code").getOrElse(""), field(body, "file").getOrElse(""),
          field(body, "visibleFrom").map(_.toInt).getOrElse(-1), field(body, "visibleTo").map(_.toInt).getOrElse(-1))))
      case "/hover" if ex.getRequestMethod == "POST" =>
        // a lean hover (no x-ray bookkeeping): runUpTo at the offset and the
        // type of the innermost typed tree there, as a read action; its wall
        // time and its time blocked on the completion lock
        val body = readBody(ex)
        val code = field(body, "code").getOrElse("")
        val offset = field(body, "offset").map(_.toInt).getOrElse(0)
        val file = field(body, "file").getOrElse("")
        val uri = uriOf(file)
        val source = SourceFile.virtual(uri, code)
        val beside0 = backgroundState("state") == "running"
        val t0 = System.nanoTime()
        val result =
          try
            val text = driver.readUpTo(uri, source, Span(offset)) { (ctx, unit, _) =>
              given Context = ctx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using ctx))
              Interactive.pathTo(unit.tpdTree, Span(offset)).headOption match
                case Some(t) if t.hasType => t.tpe.widenTermRefExpr.show
                case Some(_) => "<untyped>"
                case None => "<no tree>"
            }
            Map("hover" -> text)
          catch case scala.util.control.NonFatal(e) => Map("error" -> e.toString)
        val ms = (System.nanoTime() - t0) / 1e6
        val r = driver.readerOfThisThread
        r.lockWaits.iterator().asScala.foreach(w => allForegroundWaits.add((math.round(w.nanos / 1e5) / 10.0, math.round(ms * 10) / 10.0, w.root, w.current, w.holder)))
        reply(ex, 200, "application/json", json(result ++ lockWaitsOf(r) ++ Map("ms" -> math.round(ms * 100) / 100.0,
          "statementWaitMs" -> math.round(r.statementWaitNanos / 1e5) / 10.0,
          "concurrentWithBackground" -> (beside0 || backgroundState("state") == "running"))))
      case "/lockwaits" =>
        // every itemised wait of the foreground requests since the last reset: (ms, request wall ms, root, current, holder)
        val q = Option(ex.getRequestURI.getRawQuery).getOrElse("")
        val rows = allForegroundWaits.iterator().asScala.toList
        if field(q, "reset").contains("1") then allForegroundWaits.clear()
        reply(ex, 200, "application/json", json(Map("waits" -> rows.map((ms, wall, root, cur, holder) => Map("ms" -> ms, "wall" -> wall, "root" -> root, "current" -> cur, "holder" -> holder)))))
      case "/background" =>
        reply(ex, 200, "application/json", json(backgroundStatus()))
      case "/cancel" if ex.getRequestMethod == "POST" =>
        // The checker sees the flag at the next checkpoint (every 1024 typed trees); the latch is released so that typing gets there.
        cancelNow = true
        releaseLatch()
        reply(ex, 200, "application/json", json(status()))
      case "/inflight" =>
        // the requests in flight, with where each one's thread is: for a page that waits too long
        val now = System.nanoTime()
        reply(ex, 200, "application/json", json(Map("requests" -> inFlightRequests.values.asScala.toList.filterNot(_._2 eq Thread.currentThread).sortBy(_._3).map((what, thread, since) =>
          Map("request" -> what, "thread" -> thread.getName, "ms" -> (now - since) / 1000000, "state" -> thread.getState.toString,
            "where" -> thread.getStackTrace.iterator.map(_.toString).filter(_.startsWith("dotty")).take(8).toList)))))
      case _ =>
        reply(ex, 404, "text/plain", "not found")))
    server.start()
    println(s"[xray] listening on http://127.0.0.1:$port/")
    Thread.currentThread.join()
