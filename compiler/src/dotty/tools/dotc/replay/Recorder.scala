package dotty.tools.dotc
package replay

import java.io.{BufferedWriter, FileWriter}
import java.util.IdentityHashMap
import scala.collection.mutable

import ast.{tpd, untpd, Positioned}
import core.*, Contexts.*, Symbols.*, Types.*, Flags.*, Phases.*, Denotations.*, SymDenotations.*
import reporting.{Diagnostic, Reporter}
import typer.Implicits.{SearchResult, SearchSuccess, SearchFailure}
import util.{SourceFile, Spans}, Spans.Span

/** Records typer events (`ev`), dependency edges (`edge`) and copier lineage (`derive`)
 *  as v0 replay JSONL. Installed on `ContextBase.recorder` by `-Yreplay-record:<file>`.
 *  Hook sites test `ctx.base.recorder != null` and call into here; nothing else is touched.
 *
 *  Events are written at exit, so a parent line follows its children; `seq` is allocated
 *  at entry and is the canonical order. Exploratory PoC (Track B), written with LLM assistance.
 */
final class Recorder(path: String):
  private val out = new BufferedWriter(new FileWriter(path), 1 << 16)

  private var nextSeq = 0
  private val stack = mutable.ArrayBuffer[Int]()
  // > 0 while the recorder itself is printing; hooks fired by printing are counted, not recorded
  private var suspended = 0
  var perturbations = 0

  private val nodeIds = IdentityHashMap[Positioned, Integer]()
  private val symIds = IdentityHashMap[Symbol, Integer]()
  private val typeIds = IdentityHashMap[Type, Integer]()
  private val unitIds = mutable.HashMap[SourceFile, Int]()
  private val edgesSeen = mutable.HashSet[(String, Int, Int)]()

  // Lineage bookkeeping (derive), per phase group, for the copier-vs-heuristic comparison
  private val derivedNew = IdentityHashMap[Positioned, String]()
  private var prevVersion = IdentityHashMap[Positioned, Positioned]()
  private var prevSpans = mutable.HashMap[Long, Int]()

  private def spanKey(span: Span): Long = (span.start.toLong << 32) | span.end

  // ---- JSON helpers ---------------------------------------------------------------------

  private def str(s: String): String =
    val sb = new java.lang.StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
        case c => sb.append(c)
      i += 1
    sb.append('"').toString

  private def line(s: String): Unit = { out.write(s); out.write('\n') }

  private def spanJson(span: Span): String =
    if span.exists then s"[${span.start},${span.end}]" else "null"

  private def ints(xs: Iterable[Int]): String = xs.mkString("[", ",", "]")

  /** Run `op` with hooks suspended; count any events it would have produced. */
  private def quietly[T](op: => T): T =
    suspended += 1
    try op finally suspended -= 1

  // ---- interning ------------------------------------------------------------------------

  def unitId(src: SourceFile): Int =
    if !src.exists then -1
    else unitIds.getOrElseUpdate(src, {
      val id = unitIds.size
      line(s"""{"t":"unit","id":$id,"path":${str(src.path)}}""")
      id
    })

  def nodeId(tree: Positioned)(using Context): Int =
    val id = nodeIds.get(tree)
    if id != null then id
    else
      val n = nodeIds.size
      nodeIds.put(tree, n)
      val (kind, name) = tree match
        case t: untpd.NameTree => (t.getClass.getSimpleName, str(t.name.toString))
        case t => (t.getClass.getSimpleName, "null")
      line(s"""{"t":"node","id":$n,"kind":"$kind","span":${spanJson(tree.span)},"unit":${unitId(tree.source)},"name":$name}""")
      n

  def symId(sym: Symbol)(using Context): Int =
    if !sym.exists then -1
    else
      val id = symIds.get(sym)
      if id != null then id
      else
        val owner = if sym.maybeOwner.exists then symId(sym.maybeOwner) else -1
        val n = symIds.size
        symIds.put(sym, n)
        line(s"""{"t":"sym","id":$n,"name":${str(sym.name.toString)},"owner":$owner,"createdPhase":null,"createdBy":null}""")
        n

  def typeId(tp: Type)(using Context): Int =
    if tp == null then -1
    else
      val id = typeIds.get(tp)
      if id != null then id
      else
        val n = typeIds.size
        typeIds.put(tp, n)
        val text = quietly(try tp.show catch case ex: Throwable => s"<${ex.getClass.getSimpleName}>")
        line(s"""{"t":"type","id":$n,"text":${str(text)}}""")
        n

  // The phase (group) the Run is executing. Not `ctx.phase`: completers run at the symbol's
  // creation phase and denotation transformers at `phase.next`.
  private var running: Phase = NoPhase
  private def phaseId: Int = running.id

  def beforePhase(phase: Phase)(using Context): Unit =
    running = phase
    val mini = phase match
      case mp: transform.MegaPhase => mp.miniPhases.map(p => str(p.phaseName)).mkString("[", ",", "]")
      case _ => "null"
    line(s"""{"t":"phase","id":${phase.id},"name":${str(phase.phaseName)},"mini":$mini}""")

  // ---- events ---------------------------------------------------------------------------

  /** Allocate a seq and make it the parent of events until the matching `exit`. */
  def enter(): Int =
    if suspended > 0 then { perturbations += 1; -1 }
    else
      val s = nextSeq
      nextSeq += 1
      stack += s
      s

  private def parentOf(seq: Int): String =
    // called after the pop: the stack top is the parent
    if stack.isEmpty then "null" else stack.last.toString

  private def pop(seq: Int): Unit =
    assert(stack.nonEmpty && stack.last == seq, s"replay recorder: unbalanced exit $seq, stack=$stack")
    stack.remove(stack.length - 1)

  /** Whether events were recorded since `seq` was allocated. */
  private def hadChildren(seq: Int): Boolean = nextSeq > seq + 1

  private def ev(seq: Int, kind: String, span: Span, nodes: Iterable[Int], syms: Iterable[Int], payload: String)(using Context): Unit =
    line(s"""{"t":"ev","seq":$seq,"parent":${parentOf(seq)},"kind":"$kind","phase":$phaseId,"unit":${unitId(ctx.source)},"span":${spanJson(span)},"nodes":${ints(nodes)},"syms":${ints(syms)},"payload":$payload}""")

  /** Edge between the top-level classes enclosing `from` and `to`, first time only. */
  private def edge(kind: String, from: Symbol, to: Symbol, seq: Int)(using Context): Unit =
    if from.exists && to.exists && !to.is(Package) then
      val f = quietly(from.topLevelClass)
      val t = quietly(to.topLevelClass)
      if (f ne t) && f.exists && t.exists && !t.is(PackageClass) then
        val key = (kind, symId(f), symId(t))
        if edgesSeen.add(key) then
          line(s"""{"t":"edge","kind":"$kind","from":${key._2},"to":${key._3},"seq":$seq}""")

  def exitTyped(seq: Int, tree: untpd.Tree, pt: Type, res: tpd.Tree | Null)(using Context): Unit =
    if seq >= 0 then
      pop(seq)
      val in = nodeId(tree)
      val payload =
        if res == null then s"""{"pt":${typeId(pt)},"aborted":true}"""
        else s"""{"pt":${typeId(pt)},"tpe":${typeId(res.typeOpt)},"kind":"${res.getClass.getSimpleName}"}"""
      val nodes = if res == null then List(in) else List(in, nodeId(res))
      val syms = res match
        case res: tpd.RefTree if res.symbol.exists =>
          edge("resolves", ctx.owner, res.symbol, seq)
          List(symId(res.symbol))
        case res: tpd.MemberDef if res.symbol.exists => List(symId(res.symbol))
        case _ => Nil
      ev(seq, "Typed", tree.span, nodes, syms, payload)

  def exitAdapt(seq: Int, tree: tpd.Tree, pt: Type, res: tpd.Tree | Null)(using Context): Unit =
    if seq >= 0 then
      pop(seq)
      if (res ne tree) || hadChildren(seq) then
        val nodes = if res == null then List(nodeId(tree)) else List(nodeId(tree), nodeId(res))
        val outKind = if res == null then "null" else s""""${res.getClass.getSimpleName}""""
        ev(seq, "Adapt", tree.span, nodes, Nil, s"""{"pt":${typeId(pt)},"changed":${res ne tree},"outKind":$outKind}""")

  def exitComplete(seq: Int, denot: SymDenotation, ok: Boolean)(using Context): Unit =
    if seq >= 0 then
      pop(seq)
      val sym = denot.symbol
      edge("forces", ctx.owner, sym, seq)
      ev(seq, "Complete", sym.span, Nil, List(symId(sym)), s"""{"ok":$ok,"forcedBy":${symId(ctx.owner)}}""")

  def exitImplicitSearch(seq: Int, pt: Type, argument: tpd.Tree, span: Span, res: SearchResult | Null)(using Context): Unit =
    if seq >= 0 then
      pop(seq)
      val (result, syms) = res match
        case res: SearchSuccess =>
          edge("implicit", ctx.owner, res.ref.symbol, seq)
          (s""""success"""", List(symId(res.ref.symbol)))
        case res: SearchFailure => (str(res.reason.getClass.getSimpleName), Nil)
        case null => ("null", Nil)
      val nodes = if argument.isEmpty then Nil else List(nodeId(argument))
      ev(seq, "ImplicitSearch", span, nodes, syms, s"""{"pt":${typeId(pt)},"result":$result}""")

  def exitCandidate(seq: Int, ref: TermRef, span: Span, res: SearchResult | Null)(using Context): Unit =
    if seq >= 0 then
      pop(seq)
      val (accepted, reason) = res match
        case _: SearchSuccess => ("true", "null")
        case res: SearchFailure => ("false", str(res.reason.getClass.getSimpleName))
        case null => ("false", "\"aborted\"")
      ev(seq, "Candidate", span, Nil, List(symId(ref.symbol)), s"""{"ref":${typeId(ref)},"accepted":$accepted,"reason":$reason}""")

  def report(dia: Diagnostic, reporter: Reporter)(using Context): Unit =
    if suspended > 0 then perturbations += 1
    else
      val seq = nextSeq
      nextSeq += 1
      val msg = quietly(try dia.msg.message catch case ex: Throwable => s"<${ex.getClass.getSimpleName}>")
      val pos = dia.pos
      val span = if pos.exists then pos.span else Spans.NoSpan
      ev(seq, "Report", span, Nil, Nil,
        s"""{"level":${dia.level},"msg":${str(msg)},"reporter":"${reporter.getClass.getSimpleName}"}""")

  // ---- lineage --------------------------------------------------------------------------

  /** TreeCopier produced `copied` from `tree`. */
  def derive(tree: Positioned, copied: Positioned)(using Context): Unit =
    if suspended == 0 && !running.isTyper then
      derivedNew.put(copied, "copy")
      val old = nodeId(tree)
      line(s"""{"t":"derive","phase":$phaseId,"new":[${nodeId(copied)}],"old":[$old],"how":"copy"}""")

  /** After each (fused) phase: walk the units' trees, classify nodes that are new since the
   *  previous phase by whether the copier or a re-typing (`Typed` in a later phase) accounts
   *  for them, versus Track A's span-equality heuristic. Emits a `lineageStats` line.
   */
  def afterPhase(phase: Phase, units: List[CompilationUnit])(using Context): Unit =
    val cur = IdentityHashMap[Positioned, Positioned]()
    val curSpans = mutable.HashMap[Long, Int]()
    var total, fresh, byCopier, byTyped, byHeuristic, ambiguous, both = 0
    for unit <- units do
      val walker = new tpd.TreeTraverser:
        def traverse(t: tpd.Tree)(using Context): Unit =
          if !cur.containsKey(t) then
            cur.put(t, t)
            total += 1
            if t.span.exists then curSpans.updateWith(spanKey(t.span))(n => Some(n.getOrElse(0) + 1))
            if !prevVersion.containsKey(t) then
              fresh += 1
              val how = derivedNew.get(t)
              val c = how != null
              val matches = if t.span.exists then prevSpans.getOrElse(spanKey(t.span), 0) else 0
              val h = matches > 0
              if matches > 1 then ambiguous += 1
              if how == "copy" then byCopier += 1
              if how == "typed" then byTyped += 1
              if h then byHeuristic += 1
              if c && h then both += 1
            traverseChildren(t)
      walker.traverse(unit.tpdTree)
    if !phase.isTyper && prevVersion.size > 0 then
      line(s"""{"t":"lineageStats","phase":${str(phase.phaseName)},"nodes":$total,"new":$fresh,"copier":$byCopier,"typed":$byTyped,"heuristic":$byHeuristic,"heuristicAmbiguous":$ambiguous,"both":$both}""")
    prevVersion = cur
    prevSpans = curSpans
    derivedNew.clear()

  /** Record a `Typed` during a non-typer phase (e.g. Erasure's retyping) as lineage too. */
  def noteRetyped(res: Positioned)(using Context): Unit =
    if !running.isTyper then derivedNew.put(res, "typed")

  def close(): Unit =
    line(s"""{"t":"stats","events":$nextSeq,"nodes":${nodeIds.size},"syms":${symIds.size},"types":${typeIds.size},"perturbations":$perturbations}""")
    out.close()

object Recorder:
  def start(path: String)(using Context): Recorder =
    // Experiment: consume N identity hashes to test whether the stream depends on the JVM's hash sequence
    val perturb = System.getenv("REPLAY_PERTURB_IDHASH")
    if perturb != null then for _ <- 0 until perturb.toInt do System.identityHashCode(new Object)
    val r = Recorder(path)
    r.line(s"""{"t":"meta","compiler":${r.str(config.Properties.versionNumberString)},"args":[${ctx.settings.userSetSettings(ctx.settingsState).map(s => r.str(s"${s.name}=${s.valueIn(ctx.settingsState)}")).mkString(",")}],"units":[]}""")
    r

  // Hook helpers. The disabled path is a field read and a branch (plus a `try`, which is free).

  inline def typed(tree: untpd.Tree, pt: Type)(inline body: tpd.Tree)(using Context): tpd.Tree =
    val rec = ctx.base.recorder
    val seq = if rec == null then -1 else rec.enter()
    var res: tpd.Tree | Null = null
    try
      res = body
      res
    finally
      if rec != null then
        rec.exitTyped(seq, tree, pt, res)
        if res != null then rec.noteRetyped(res)

  inline def adapt(tree: tpd.Tree, pt: Type)(inline body: tpd.Tree)(using Context): tpd.Tree =
    val rec = ctx.base.recorder
    val seq = if rec == null then -1 else rec.enter()
    var res: tpd.Tree | Null = null
    try
      res = body
      res
    finally if rec != null then rec.exitAdapt(seq, tree, pt, res)

  inline def complete(denot: SymDenotation)(inline body: Unit)(using Context): Unit =
    val rec = ctx.base.recorder
    val seq = if rec == null then -1 else rec.enter()
    var ok = false
    try
      body
      ok = true
    finally if rec != null then rec.exitComplete(seq, denot, ok)

  inline def implicitSearch(pt: Type, argument: tpd.Tree, span: Span)(inline body: SearchResult)(using Context): SearchResult =
    val rec = ctx.base.recorder
    val seq = if rec == null then -1 else rec.enter()
    var res: SearchResult | Null = null
    try
      res = body
      res
    finally if rec != null then rec.exitImplicitSearch(seq, pt, argument, span, res)

  inline def candidate(ref: TermRef, span: Span)(inline body: SearchResult)(using Context): SearchResult =
    val rec = ctx.base.recorder
    val seq = if rec == null then -1 else rec.enter()
    var res: SearchResult | Null = null
    try
      res = body
      res
    finally if rec != null then rec.exitCandidate(seq, ref, span, res)
