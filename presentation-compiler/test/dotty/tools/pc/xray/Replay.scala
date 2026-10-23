package dotty.tools.pc.xray

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.{tpd, Trees}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Completion, Interactive, InteractiveDriver}
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.util.{SourceFile, SourcePosition}
import dotty.tools.dotc.util.Spans.Span

/** The x-ray's replay: an editing session recorded from Metals (the events
 *  `trace_to_events.py` makes of an `lsp.trace.json`, the format of
 *  `QueueReplaySuite`: didOpen/didChange with the file's text, hover,
 *  definition, completion, document highlight, inlay hints, semantic tokens,
 *  and the client's cancellations), replayed with its own timing against a
 *  fresh driver once per mode, so that the modes answer the same requests at
 *  the same moments. Each request is recorded: when it was due, how long it
 *  queued behind the request before it, its latency, its time waiting on the
 *  completion lock or roots and on a statement another reader was typing, the
 *  trees it typed, the runs it started, its diagnostics against a whole-file
 *  run of the same content, and its answer, compared with the baseline's.
 *
 *  What the driver is given, per request kind, is what the presentation
 *  compiler's providers use: `runUpTo` (here `readUpTo`) for hover, definition
 *  and completion, `run` (here `readAll`) for document highlight, inlay hints
 *  and semantic tokens. The baseline runs as the presentation compiler does
 *  today: `-Yno-targeted-requests`, with `CachingDriver`'s cache (no run when
 *  the file and its content are those of the last run). The replay does not go
 *  through Metals' job queue (`Scala3CompilerAccess`), which serialises every
 *  request: `lanes=1` is that one queue, `lanes=2` (the default) gives the
 *  cursor requests (hover, definition, completion) a lane of their own beside
 *  the whole-file ones, as a presentation compiler with several readers would.
 *  didClose drops the file's text only (the driver keeps its unit).
 */
object Replay:

  /** A configuration of the driver the replay compares. */
  final case class Mode(key: String, title: String, flags: List[String])

  val modes: List[Mode] = List(
    Mode("baseline", "-Yno-targeted-requests (baseline: every request types the whole file)", List("-Yno-targeted-requests")),
    Mode("default", "default: targeting, the session, several readers serialised (step 3)", Nil),
    Mode("readers", "-Yinteractive-readers: independent readers (step 4)", List("-Yinteractive-readers")),
    Mode("bodyEdits", "-Yinteractive-body-edits: the session survives an edit inside a body (§12)", List("-Yinteractive-body-edits")))

  def modeNamed(key: String): Mode = modes.find(_.key == key).get

  private val cursorKinds = Set("hover", "definition", "completion")
  private val wholeFileKinds = Set("documentHighlight", "inlayHint", "semanticTokens")
  private val requestKinds = cursorKinds ++ wholeFileKinds

  // ---- the events ----

  /** One event of the trace: `t` in the trace's seconds. */
  final case class Event(idx: Int, t: Double, kind: String, id: String, uri: String, offset: Int, endOffset: Int, text: String | Null,
      expect: String | Null = null, label: String | Null = null):
    def file: String = Replay.fileOf(uri)

  /** The path of a trace uri below `src/main/scala/` (or its last segments). */
  def fileOf(uri: String): String =
    if uri == null then ""
    else
      val i = uri.indexOf("/src/main/scala/")
      if i >= 0 then uri.substring(i + "/src/main/scala/".length) else uri.split('/').takeRight(3).mkString("/")

  /** The event files: `trace` (`-Dxray.replay.events`, a recorded Metals
   *  session) and `bodyEdit` (`-Dxray.replay.bodyEditEvents`, the seeded
   *  scenario `BodyEditScenarioSuite` writes: edits inside bodies on
   *  Types.scala, each with what it must do, `kept` or `fresh`).
   */
  val sources: List[(String, String)] = List("trace" -> "xray.replay.events", "bodyEdit" -> "xray.replay.bodyEditEvents")

  private def eventsPathOf(source: String): Option[Path] =
    sources.find(_._1 == source).flatMap((_, prop) => sys.props.get(prop)).map(Paths.get(_)).filter(Files.exists(_))

  private val loaded = new ConcurrentHashMap[String, Vector[Event]]()
  private def eventsOf(source: String): Vector[Event] = loaded.computeIfAbsent(source, src => load(eventsPathOf(src))).nn

  private def load(path: Option[Path]): Vector[Event] = path match
    case None => Vector.empty
    case Some(p) =>
      JsonParser.parseString(Files.readString(p)).getAsJsonArray.asScala.zipWithIndex.map { (j, i) =>
        val o = j.getAsJsonObject
        def str(k: String): String | Null = if o.has(k) && !o.get(k).isJsonNull then o.get(k).getAsString else null
        def int(k: String): Int = if o.has(k) && !o.get(k).isJsonNull then o.get(k).getAsInt else -1
        Event(i, o.get("t").getAsDouble, o.get("kind").getAsString, str("id"), str("uri"), int("offset"), int("endOffset"), str("text"), str("expect"), str("label"))
      }.toVector

  /** A selection of the trace: events [from, to), the pauses between the
   *  trace's seconds capped at `idleCapMs`, the requests of one second spread
   *  over it at offsets drawn from `seed` (the trace has whole seconds only).
   */
  final case class Spec(from: Int, to: Int, seed: Long, idleCapMs: Long, lanes: Int, source: String = "trace")

  val defaultSpec: Spec = Spec(0, 175, 1L, 1000L, 2)

  /** A scheduled event: due `atMs` after the start, with the text of its
   *  file at that point of the trace.
   */
  final case class Step(ev: Event, atMs: Double, text: String | Null, sinceEdit: Int = 0, edit: Event | Null = null)

  /** The steps of `spec`: the files open when the window starts are opened
   *  at time 0 (with the text they had then).
   */
  def schedule(spec: Spec): Vector[Step] =
    val texts = mutable.LinkedHashMap.empty[String, String]
    val evs = eventsOf(spec.source)
    for e <- evs.take(spec.from) do e.kind match
      case "didOpen" | "didChange" => texts(e.uri) = e.text
      case "didClose" => texts.remove(e.uri)
      case _ =>
    val window = evs.slice(spec.from, spec.to)
    val usedUris = window.filter(e => requestKinds(e.kind)).map(_.uri).toSet
    val out = Vector.newBuilder[Step]
    for (uri, text) <- texts if usedUris(uri) do
      out += Step(Event(-1, 0, "didOpen", null, uri, -1, -1, text), 0.0, text)
    val rng = new scala.util.Random(spec.seed)
    var clock = 0.0
    var prevT = window.headOption.map(_.t).getOrElse(0.0)
    // per file: the last edit and how many requests came since
    val lastEdit = mutable.Map.empty[String, (Event, Int)]
    val groups = window.groupBy(_.t).toSeq.sortBy(_._1)
    for ((t, group), gi) <- groups.zipWithIndex do
      clock += math.min((t - prevT) * 1000, spec.idleCapMs.toDouble)
      prevT = t
      // the order of the trace within its time step (a second, or less), at
      // sorted random offsets inside the step
      val step = if gi + 1 < groups.size then math.min(1000.0, (groups(gi + 1)._1 - t) * 1000) else 1000.0
      val offsets = group.indices.map(_ => rng.nextDouble() * 0.9 * step).sorted
      for (e, off) <- group.sortBy(_.idx).zip(offsets) do
        var since = 0
        var edit: Event | Null = null
        e.kind match
          case "didOpen" => texts(e.uri) = e.text; lastEdit.remove(e.uri)
          case "didChange" => texts(e.uri) = e.text; lastEdit(e.uri) = (e, 0)
          case "didClose" => texts.remove(e.uri)
          case k if requestKinds(k) =>
            lastEdit.get(e.uri).foreach { (ed, n) => since = n + 1; edit = ed; lastEdit(e.uri) = (ed, n + 1) }
          case _ =>
        out += Step(e, clock + off, if e.uri == null then null else texts.get(e.uri).orNull, since, edit)
    out.result().filter(s => !requestKinds(s.ev.kind) || s.text != null)

  // ---- one request ----

  /** What a request did. */
  final case class Row(seq: Int, mode: String, idx: Int, kind: String, file: String, offset: Int, line: Int, lane: Int,
      dueMs: Double, queuedMs: Double, ms: Double, lockMs: Double, lockCount: Int, statementMs: Double,
      trees: Int, runs: Int, wholeFileRuns: Int, backedOff: Int, how: String, outcome: String,
      answer: String, key: String, diagnostics: Int, wholeFileDiagnostics: Int,
      sinceEdit: Int = 0, editLabel: String = "", editExpect: String = "", editHow: String = "", lastEdit: String = "", label: String = ""):
    def toMap: Map[String, Any] = Map("seq" -> seq, "mode" -> mode, "idx" -> idx, "kind" -> kind, "file" -> file,
      "offset" -> offset, "line" -> line, "lane" -> lane, "dueMs" -> r1(dueMs), "queuedMs" -> r1(queuedMs), "ms" -> r1(ms),
      "lockMs" -> r1(lockMs), "lockCount" -> lockCount, "statementMs" -> r1(statementMs), "trees" -> trees, "runs" -> runs,
      "wholeFileRuns" -> wholeFileRuns, "backedOff" -> backedOff, "how" -> how, "outcome" -> outcome, "answer" -> answer,
      "diagnostics" -> diagnostics, "wholeFileDiagnostics" -> wholeFileDiagnostics,
      "sinceEdit" -> sinceEdit, "editLabel" -> editLabel, "editExpect" -> editExpect, "editHow" -> editHow, "lastEdit" -> lastEdit, "label" -> label)

  private val freshNumber = "\\$\\d+".r

  private def r1(d: Double): Double = math.round(d * 10) / 10.0

  /** The answer of a request, as a provider would compute it from the typed
   *  tree: shown (short) and compared with the baseline's (the key).
   */
  private def answer(kind: String, unit: CompilationUnit, offset: Int, endOffset: Int)(using Context): (String, String) =
    val tree = unit.tpdTree
    def symAt: Symbol =
      Interactive.pathTo(tree, Span(offset)).headOption.map(_.symbol).getOrElse(NoSymbol)
    kind match
      case "hover" =>
        Interactive.pathTo(tree, Span(offset)).headOption match
          case Some(t) if t.hasType =>
            val sym = t.symbol
            val s = s"${if sym.exists then sym.showFullName + ": " else ""}${t.tpe.widenTermRefExpr.show}"
            (s.take(160), s)
          case Some(_) => ("<untyped>", "<untyped>")
          case None => ("<no tree>", "<no tree>")
      case "definition" =>
        val sym0 = symAt
        val sym = if sym0.is(dotty.tools.dotc.core.Flags.ModuleVal) then sym0.moduleClass else sym0
        if !sym.exists then ("<no symbol>", "<no symbol>")
        else
          val where = if sym.span.exists && sym.source == unit.source then s" @${sym.span.start}" else ""
          val s = s"${sym.showKind} ${sym.showFullName}$where"
          (s.take(160), s)
      case "completion" =>
        val labels = Completion.completions(SourcePosition(unit.source, Span(offset)))._2.map(_.label).distinct.sorted
        (s"${labels.size}: ${labels.take(6).mkString(", ")}", labels.mkString(","))
      case "documentHighlight" =>
        XrayServer.usagesAt(tree, offset, unit.source) match
          case m if m.get("found").contains(true) =>
            val us = m("usages").asInstanceOf[List[Map[String, Any]]].map(u => u("start")).sortBy(_.toString)
            (s"${us.size} × ${m("symbol")}".take(160), s"${m("symbol")}:${us.mkString(",")}")
          case _ => ("<no symbol>", "<no symbol>")
      case "semanticTokens" =>
        val toks = XrayServer.semanticTokens(tree, unit.source)
        val k = toks.map(t => s"${t("start")}-${t("end")}:${t("kind")}").mkString(",")
        (s"${toks.size} tokens #${(k.hashCode & 0xffff).toHexString}", k)
      case "inlayHint" =>
        val hints = List.newBuilder[String]
        val tr = new tpd.TreeTraverser:
          def traverse(t: tpd.Tree)(using Context): Unit =
            t match
              case vd: tpd.ValOrDefDef if (vd.source eq unit.source) && vd.span.exists && vd.span.start >= offset && vd.span.start < endOffset
                  && !vd.span.isSynthetic && vd.tpt.isInstanceOf[Trees.InferredTypeTree[?]] && !vd.symbol.is(dotty.tools.dotc.core.Flags.Synthetic) =>
                hints += s"${vd.name}: ${vd.tpt.tpe.show}"
              case _ =>
            traverseChildren(t)
        tr.traverse(tree)
        val hs = hints.result()
        val k = hs.mkString("; ")
        (s"${hs.size} hints #${(k.hashCode & 0xffff).toHexString}${hs.headOption.map(h => s" ($h…)").getOrElse("")}".take(160), k)
      case other => (other, other)

  // ---- the whole-file diagnostics (the check) ----

  private val wholeFileDiagnostics = new ConcurrentHashMap[String, Integer]()

  /** The diagnostics of `uri` in a whole-file run of `text` in a fresh
   *  driver of its own (cached by content).
   */
  private def wholeFileCount(uri: String, text: String): Int =
    wholeFileDiagnostics.computeIfAbsent(uri + "\u0000" + text, _ => {
      val d = new InteractiveDriver(XrayServer.baseSettings, CachedLogicalPackage.none)
      val u = URI.create(uri)
      val source = SourceFile.virtual(u, text)
      Integer.valueOf(ownDiagnostics(d.run(u, source), source))
    }).intValue

  private def ownDiagnostics(ds: List[Diagnostic], source: SourceFile): Int =
    ds.count { d => val f = d.pos.source.file; d.pos.exists && f != null && f.path == source.file.path }

  // ---- the run ----

  final class ModeRun(val mode: Mode, val spec: Spec):
    val rows = new ConcurrentLinkedQueue[Row]()
    @volatile var state: String = "preparing"
    @volatile var startedAt: Long = 0L
    @volatile var wallMs: Double = 0.0
    @volatile var total: Int = 0
    @volatile var error: String = ""
    val inFlight = new ConcurrentHashMap[Integer, String]()
    def done: Int = rows.size

  @volatile private var runs: Map[String, ModeRun] = Map.empty
  @volatile private var current: ModeRun | Null = null
  @volatile private var lastSpec: Spec = defaultSpec
  private val seq = new AtomicInteger
  private val stopRequested = new AtomicBoolean(false)
  @volatile private var runner: Thread | Null = null
  /** The file and offset of the latest request started, for the page's editor. */
  @volatile private var cursor: Map[String, Any] = Map.empty
  private val texts = new ConcurrentHashMap[String, String]()

  private def lineOf(text: String, offset: Int): Int =
    if text == null || offset < 0 then 0 else text.substring(0, math.min(offset, text.length)).count(_ == '\n') + 1

  /** Replay `spec` once per mode of `keys`, in order, each on a fresh driver. */
  def start(keys: List[String], spec: Spec): Map[String, Any] = synchronized {
    val r = runner
    if r != null && r.isAlive then Map("error" -> "a replay is running")
    else if eventsOf(spec.source).isEmpty then Map("error" -> s"no events for ${spec.source}: start the server with -D${sources.find(_._1 == spec.source).map(_._2).getOrElse("?")}=<events.json>")
    else
      if spec != lastSpec then runs = Map.empty
      lastSpec = spec
      stopRequested.set(false)
      val t = new Thread((() => {
        for k <- keys if !stopRequested.get do
          val mr = new ModeRun(modeNamed(k), spec)
          runs = runs.updated(k, mr)
          current = mr
          try replayMode(mr)
          catch
            case ex: StackOverflowError => mr.error = ex.toString; mr.state = "error"
            case scala.util.control.NonFatal(ex) => mr.error = ex.toString; mr.state = "error"
        current = null
      }): Runnable, "xray-replay")
      t.setDaemon(true)
      runner = t
      t.start()
      Map("started" -> keys)
  }

  def stop(): Unit = stopRequested.set(true)

  private def replayMode(mr: ModeRun): Unit =
    val steps = schedule(mr.spec)
    val requests = steps.filter(s => requestKinds(s.ev.kind))
    mr.total = requests.size
    // the check first, outside the timed part: a whole-file run of each content the whole-file requests see
    mr.state = "preparing: whole-file runs for the diagnostics check"
    for s <- requests if wholeFileKinds(s.ev.kind) do wholeFileCount(s.ev.uri, s.text)
    // a fresh driver with the mode's flags, warmed up on other content, shown by the page
    mr.state = "preparing: a fresh driver"
    XrayServer.resetDriver(mr.mode)
    val driver = XrayServer.driver
    val baselineCache = if mr.mode.key == "baseline" then Some(new BaselineCache) else None
    val lanes = Vector.fill(math.max(1, mr.spec.lanes))(Executors.newSingleThreadExecutor(r => { val t = new Thread(r, "xray-replay-lane"); t.setDaemon(true); t }))
    val cancels = new ConcurrentHashMap[String, AtomicBoolean]()
    val futures = mutable.ListBuffer.empty[Future[?]]
    mr.state = "running"
    val t0 = System.nanoTime()
    mr.startedAt = t0
    try
      for s <- steps if !stopRequested.get do
        val wait = t0 + (s.atMs * 1e6).toLong - System.nanoTime()
        if wait > 0 then Thread.sleep(wait / 1000000, (wait % 1000000).toInt)
        s.ev.kind match
          case "didOpen" | "didChange" => texts.put(s.ev.file, s.text)
          case "didClose" =>
          case "cancel" => Option(cancels.get(s.ev.id)).foreach(_.set(true))
          case kind if requestKinds(kind) =>
            val flag = new AtomicBoolean(false)
            if s.ev.id != null then cancels.put(s.ev.id, flag)
            val lane = if mr.spec.lanes >= 2 && wholeFileKinds(kind) then 1 else 0
            val submitted = System.nanoTime()
            futures += lanes(lane).submit((() => {
              val row = one(driver, baselineCache, mr, s, lane, flag, submitted, t0)
              mr.rows.add(row)
            }): Runnable)
          case _ =>
      for f <- futures do
        try f.get() catch case _: Exception => ()
      mr.wallMs = (System.nanoTime() - t0) / 1e6
      mr.state = if stopRequested.get then "stopped" else "done"
    finally lanes.foreach(_.shutdownNow())

  /** `CachingDriver`'s cache for the baseline: a request on the file and
   *  content of the last run reads its unit without a run (under a read lock,
   *  so that no run of the other lane replaces the unit meanwhile).
   */
  private final class BaselineCache:
    val lock = new ReentrantReadWriteLock()
    @volatile var last: (String, String) | Null = null
    @volatile var lastDiagnostics: List[Diagnostic] = Nil

  private def one(driver: InteractiveDriver, cache: Option[BaselineCache], mr: ModeRun, s: Step, lane: Int,
      flag: AtomicBoolean, submitted: Long, t0: Long): Row =
    val e = s.ev
    val started = System.nanoTime()
    val id = seq.incrementAndGet()
    cursor = Map("file" -> e.file, "offset" -> e.offset, "endOffset" -> e.endOffset, "kind" -> e.kind, "seq" -> id, "mode" -> mr.mode.key,
      "label" -> Option(e.label).getOrElse(""), "edit" -> (if s.edit == null then "" else Option(s.edit.nn.label).getOrElse("an edit")), "sinceEdit" -> s.sinceEdit)
    mr.inFlight.put(id, s"${e.kind} ${e.file.split('/').last}:${lineOf(s.text, e.offset)}")
    val uri = URI.create(e.uri)
    val source = SourceFile.virtual(uri, s.text)
    var outcome = "ok"
    var shown = ""
    var key = ""
    var diags = -1
    var how = ""
    var reader: dotty.tools.dotc.Reader | Null = null
    // the baseline's wait for the cache (the other lane's run): its one compiler is a lock too
    var cacheWaitNanos = 0L
    var cacheWaits = 0
    def timed(l: java.util.concurrent.locks.Lock): Unit =
      if !l.tryLock() then
        val w0 = System.nanoTime()
        l.lock()
        cacheWaitNanos += System.nanoTime() - w0
        cacheWaits += 1
    def read(ctx0: Context, unit: CompilationUnit, ds: List[Diagnostic]): Unit =
      given Context = ctx0.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using ctx0))
      val (a, k) = answer(e.kind, unit, e.offset, e.endOffset)
      // fresh names (`contextual$3`, `x$1`) are numbered in typing order, which targeting changes
      shown = a; key = freshNumber.replaceAllIn(k, "\\$n")
      diags = ownDiagnostics(ds, unit.source)
    try
      if flag.get then outcome = "cancelled before it started"
      else driver.withCancelChecker(() => flag.get || stopRequested.get) {
        reader = driver.readerOfThisThread
        cache match
          case Some(c) =>
            val hit =
              timed(c.lock.readLock)
              try
                val l = c.last
                val unit = driver.compilationUnits.get(uri)
                if l != null && l == (e.uri, s.text) && unit.exists(_.source.textContent() == s.text) then
                  read(driver.currentCtx, unit.get, c.lastDiagnostics); true
                else false
              finally c.lock.readLock.unlock()
            if !hit then
              timed(c.lock.writeLock)
              try
                c.last = null
                driver.readAll(uri, source) { (ctx, unit, ds) => c.lastDiagnostics = ds; read(ctx, unit, ds) }
                c.last = (e.uri, s.text)
              finally c.lock.writeLock.unlock()
          case None =>
            if cursorKinds(e.kind) then driver.readUpTo(uri, source, Span(e.offset))(read)
            else driver.readAll(uri, source)(read)
      }
    catch
      case _: CompilationUnit.CancelledCompilation => outcome = "cancelled"
      case ex: StackOverflowError => outcome = "error"; shown = "StackOverflowError"
      case scala.util.control.NonFatal(ex) => outcome = "error"; shown = ex.toString.take(200)
    finally mr.inFlight.remove(id)
    val end = System.nanoTime()
    val r = reader
    val runs = if r == null then 0 else r.runsStarted
    val trees = if r == null then 0 else r.typedTrees
    how =
      if outcome != "ok" then outcome
      else if r != null && r.wholeFileRunsStarted > 0 then "whole-file run"
      else if runs > 0 then "targeted run"
      else if trees > 0 then "resumed"
      else "served"
    Row(id, mr.mode.key, e.idx, e.kind, e.file, e.offset, lineOf(s.text, e.offset), lane,
      dueMs = (submitted - t0) / 1e6, queuedMs = (started - submitted) / 1e6, ms = (end - submitted) / 1e6,
      lockMs = ((if r == null then 0L else r.lockWaitNanos) + cacheWaitNanos) / 1e6, lockCount = (if r == null then 0 else r.lockWaitCount) + cacheWaits,
      statementMs = if r == null then 0 else r.statementWaitNanos / 1e6,
      trees = trees, runs = runs, wholeFileRuns = if r == null then 0 else r.wholeFileRunsStarted,
      backedOff = if r == null then 0 else r.waitCyclesBackedOff, how = how, outcome = outcome,
      answer = shown, key = key, diagnostics = diags,
      wholeFileDiagnostics = if wholeFileKinds(e.kind) && outcome == "ok" then wholeFileCount(e.uri, s.text) else -1,
      sinceEdit = s.sinceEdit,
      editLabel = if s.edit == null then "" else Option(s.edit.nn.label).getOrElse(""),
      editExpect = if s.edit == null then "" else Option(s.edit.nn.expect).getOrElse(""),
      // the first request after an edit: whether the edit kept the session's run
      editHow = if s.sinceEdit != 1 || outcome != "ok" then "" else if runs > 0 then "fresh" else "kept",
      lastEdit = if s.sinceEdit == 1 then driver.lastEdit(uri) else "",
      label = Option(e.label).getOrElse(""))

  // ---- the findings ----

  private def pct(xs: Seq[Double], p: Double): Double =
    if xs.isEmpty then Double.NaN
    else
      val s = xs.sorted
      s(math.min(s.size - 1, math.max(0, math.round(p / 100 * (s.size - 1)).toInt)))

  /** Per mode and request kind: the findings table's cells. */
  def findings: List[Map[String, Any]] =
    val base = runs.get("baseline").map(_.rows.asScala.filter(_.outcome == "ok").map(r => r.idx -> r).toMap).getOrElse(Map.empty)
    for
      m <- modes
      mr <- runs.get(m.key).toList
      kind <- (List("all") ++ (cursorKinds.toList.sorted ++ wholeFileKinds.toList.sorted) ++ List("first request after an edit") ++
        (cursorKinds.toList.sorted ++ wholeFileKinds.toList.sorted).map(k => s"$k after an edit"))
      rs = mr.rows.asScala.toList.filter(r => kind match
        case "all" => true
        case "first request after an edit" => r.sinceEdit == 1
        case k if k.endsWith(" after an edit") => r.sinceEdit >= 1 && r.kind == k.stripSuffix(" after an edit")
        case k => r.kind == k)
      if rs.nonEmpty
    yield
      val ok = rs.filter(_.outcome == "ok")
      def stats(f: Row => Double): Map[String, Any] =
        val xs = ok.map(f)
        Map("median" -> r1(pct(xs, 50)), "p90" -> r1(pct(xs, 90)), "max" -> r1(if xs.isEmpty then Double.NaN else xs.max))
      val checked = ok.filter(_.wholeFileDiagnostics >= 0)
      val differ =
        if m.key == "baseline" || base.isEmpty then Nil
        else ok.flatMap(r => base.get(r.idx).filter(_.key != r.key).map(b => (r, b)))
      Map("mode" -> m.key, "kind" -> kind, "count" -> rs.size, "ok" -> ok.size,
        "cancelled" -> rs.count(_.outcome.startsWith("cancelled")), "errors" -> rs.count(_.outcome == "error"),
        "latency" -> stats(_.ms), "queued" -> stats(_.queuedMs), "lock" -> stats(_.lockMs), "statement" -> stats(_.statementMs),
        "treesMedian" -> pct(ok.map(_.trees.toDouble), 50).toInt.max(0), "trees" -> ok.map(_.trees).sum,
        "runs" -> rs.map(_.runs).sum, "wholeFileRuns" -> rs.map(_.wholeFileRuns).sum, "backedOff" -> rs.map(_.backedOff).sum,
        "diagChecked" -> checked.size, "diagMatch" -> checked.count(r => r.diagnostics == r.wholeFileDiagnostics),
        "diagFirstMismatch" -> checked.find(r => r.diagnostics != r.wholeFileDiagnostics).map(r => s"${r.file.split('/').last}:${r.line} ${r.kind}: ${r.diagnostics} vs ${r.wholeFileDiagnostics}").getOrElse(""),
        "compared" -> (if m.key == "baseline" then 0 else ok.count(r => base.contains(r.idx))),
        // edits: of the first requests after an edit, how many found the run kept, and as expected
        "editsKept" -> rs.count(_.editHow == "kept"), "editsFresh" -> rs.count(_.editHow == "fresh"),
        "editsAsExpected" -> rs.count(r => r.editHow.nonEmpty && r.editHow == r.editExpect),
        "editsExpected" -> rs.count(r => r.editHow.nonEmpty && r.editExpect.nonEmpty),
        "differ" -> differ.size,
        "differExamples" -> differ.take(3).map((r, b) => Map("where" -> s"${r.kind} ${r.file.split('/').last}:${r.line} (event ${r.idx})", "idx" -> r.idx, "mode" -> r.answer, "baseline" -> b.answer)))

  /** What the replay plays: the window, the files, the event counts. */
  def describe(spec: Spec): Map[String, Any] =
    val steps = schedule(spec)
    val reqs = steps.filter(s => requestKinds(s.ev.kind))
    val allEvents = eventsOf(spec.source)
    Map("source" -> spec.source, "eventsFile" -> eventsPathOf(spec.source).map(_.toString).getOrElse(""), "eventsInTrace" -> allEvents.size,
      "from" -> spec.from, "to" -> spec.to, "seed" -> spec.seed, "idleCapMs" -> spec.idleCapMs, "lanes" -> spec.lanes,
      "events" -> steps.size, "requests" -> reqs.size,
      "edits" -> steps.count(_.ev.kind == "didChange"), "cancels" -> steps.count(_.ev.kind == "cancel"),
      "editsExpected" -> steps.filter(_.ev.kind == "didChange").groupBy(s => Option(s.ev.expect).getOrElse("?")).view.mapValues(_.size).toMap,
      "durationMs" -> steps.lastOption.map(s => r1(s.atMs)).getOrElse(0.0),
      "traceSeconds" -> (if spec.to > spec.from && allEvents.nonEmpty then s"${allEvents(spec.from.min(allEvents.size - 1)).t}–${allEvents((spec.to - 1).min(allEvents.size - 1)).t}" else ""),
      "kinds" -> reqs.groupBy(_.ev.kind).view.mapValues(_.size).toMap,
      "files" -> reqs.groupBy(_.ev.file).toList.sortBy(-_._2.size).map((f, ss) => Map("file" -> f, "requests" -> ss.size,
        "lines" -> Option(ss.head.text).map(_.count(_ == '\n') + 1).getOrElse(0))))

  def status(since: Int): Map[String, Any] =
    val c = current
    Map("running" -> (c != null), "current" -> (if c == null then "" else c.mode.key), "cursor" -> cursor,
      "spec" -> describe(lastSpec),
      "modes" -> modes.flatMap(m => runs.get(m.key).map(mr => Map("mode" -> m.key, "title" -> m.title, "flags" -> m.flags.mkString(" "),
        "state" -> mr.state, "done" -> mr.done, "total" -> mr.total, "wallMs" -> r1(mr.wallMs), "error" -> mr.error,
        "inFlight" -> mr.inFlight.values.asScala.toList,
        "elapsedMs" -> (if mr.startedAt == 0 then 0.0 else if mr.wallMs > 0 then mr.wallMs else r1((System.nanoTime() - mr.startedAt) / 1e6))))),
      "rows" -> runs.values.flatMap(_.rows.asScala).filter(_.seq > since).toList.sortBy(_.seq).map(_.toMap),
      "findings" -> findings)

  // ---- the report ----

  def markdown: String =
    val d = describe(lastSpec)
    val b = new StringBuilder
    b ++= "## Replay findings\n\n"
    b ++= s"Events ${d("from")}–${d("to")} of `${d("eventsFile")}` (trace seconds ${d("traceSeconds")}): ${d("requests")} requests, ${d("edits")} edits, ${d("cancels")} cancellations over ${d("durationMs")} ms (pauses capped at ${d("idleCapMs")} ms, seed ${d("seed")}, ${d("lanes")} lane${if d("lanes") == 1 then "" else "s"}).\n\n"
    b ++= "Files: " + d("files").asInstanceOf[List[Map[String, Any]]].map(f => s"`${f("file")}` (${f("requests")} requests, ${f("lines")} lines)").mkString(", ") + ".\n\n"
    for m <- modes; mr <- runs.get(m.key) do
      b ++= s"- **${m.key}** (`${if m.flags.isEmpty then "no flags" else m.flags.mkString(" ")}`): ${m.title}; ${mr.state}, ${r1(mr.wallMs)} ms wall\n"
    b ++= "\n| mode | kind | n | cancelled | latency ms median / p90 / max | queued ms median / p90 | lock wait ms median / p90 / max | statement wait ms p90 | trees median (total) | runs (whole-file) | edits kept / fresh (as expected) | diagnostics = whole-file | answers ≠ baseline |\n"
    b ++= "|---|---|---|---|---|---|---|---|---|---|---|---|---|\n"
    def s3(m: Any) = { val x = m.asInstanceOf[Map[String, Any]]; s"${x("median")} / ${x("p90")} / ${x("max")}" }
    def s2(m: Any) = { val x = m.asInstanceOf[Map[String, Any]]; s"${x("median")} / ${x("p90")}" }
    for f <- findings do
      b ++= s"| ${f("mode")} | ${f("kind")} | ${f("count")} | ${f("cancelled")} | ${s3(f("latency"))} | ${s2(f("queued"))} | ${s3(f("lock"))} | ${f("statement").asInstanceOf[Map[String, Any]]("p90")} | ${f("treesMedian")} (${f("trees")}) | ${f("runs")} (${f("wholeFileRuns")}) | ${if f("editsKept").asInstanceOf[Int] + f("editsFresh").asInstanceOf[Int] == 0 then "–" else s"${f("editsKept")} / ${f("editsFresh")} (${f("editsAsExpected")} of ${f("editsExpected")})"} | ${if f("diagChecked") == 0 then "–" else s"${f("diagMatch")}/${f("diagChecked")}"} | ${if f("mode") == "baseline" then "–" else s"${f("differ")}/${f("compared")}"} |\n"
    val ex = findings.filter(f => f("kind") != "all" && f("differ").asInstanceOf[Int] > 0)
    if ex.nonEmpty then
      b ++= "\nAnswers that differ from the baseline (first few):\n\n"
      for f <- ex; e <- f("differExamples").asInstanceOf[List[Map[String, Any]]] do
        b ++= s"- ${f("mode")}, ${e("where")}: `${e("mode")}`; baseline `${e("baseline")}`\n"
    val mism = findings.filter(f => f("kind") != "all" && f("diagFirstMismatch") != "")
    if mism.nonEmpty then
      b ++= "\nDiagnostics that differ from a whole-file run (first per mode and kind):\n\n"
      for f <- mism do b ++= s"- ${f("mode")}: ${f("diagFirstMismatch")}\n"
    b.toString

  // ---- HTTP ----

  def route(ex: HttpExchange, path: String): Unit =
    import XrayServer.{json, reply, field}
    val q = Option(ex.getRequestURI.getRawQuery).getOrElse("") + "&" + (if ex.getRequestMethod == "POST" then XrayServer.readBody(ex) else "")
    def spec: Spec = Spec(
      field(q, "from").map(_.toInt).getOrElse(lastSpec.from), field(q, "to").map(_.toInt).getOrElse(lastSpec.to),
      field(q, "seed").map(_.toLong).getOrElse(lastSpec.seed), field(q, "idleCap").map(_.toLong).getOrElse(lastSpec.idleCapMs),
      field(q, "lanes").map(_.toInt).getOrElse(lastSpec.lanes), field(q, "source").getOrElse(lastSpec.source))
    path match
      case "/replay/describe" => reply(ex, 200, "application/json", json(describe(spec)))
      case "/replay/run" =>
        val keys = field(q, "modes").map(_.split(',').toList.filter(k => modes.exists(_.key == k))).filter(_.nonEmpty).getOrElse(modes.map(_.key))
        reply(ex, 200, "application/json", json(start(keys, spec)))
      case "/replay/stop" => stop(); reply(ex, 200, "application/json", json(Map("stopping" -> true)))
      case "/replay/status" => reply(ex, 200, "application/json", json(status(field(q, "since").map(_.toInt).getOrElse(0))))
      case "/replay/source" =>
        val f = field(q, "file").getOrElse("")
        reply(ex, 200, "application/json", json(Map("file" -> f, "source" -> Option(texts.get(f)).getOrElse(""))))
      case "/replay/answer" =>
        // the full answers of one event in every mode run, for a difference from the baseline
        val idx = field(q, "idx").map(_.toInt).getOrElse(-1)
        reply(ex, 200, "application/json", json(runs.toList.flatMap((k, mr) => mr.rows.asScala.filter(_.idx == idx).map(r =>
          Map("mode" -> k, "kind" -> r.kind, "file" -> r.file, "line" -> r.line, "how" -> r.how, "answer" -> r.key)))))
      case "/replay/report.md" => reply(ex, 200, "text/markdown; charset=utf-8", markdown)
      case _ => reply(ex, 404, "text/plain", "not found")
