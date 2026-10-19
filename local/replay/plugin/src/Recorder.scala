// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import java.io.{BufferedWriter, FileWriter}
import java.util.IdentityHashMap
import scala.collection.mutable

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.config.Properties
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.SymDenotations.SymDenotation
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.DenotTransformers.DenotTransformer
import dotty.tools.dotc.printing.XprintMode
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}
import dotty.tools.dotc.transform.MegaPhase
import dotty.tools.dotc.transform.MegaPhase.MiniPhase
import dotty.tools.dotc.typer.Implicits.{SearchResult, SearchSuccess, SearchFailure}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

/** One per compiler run, installed by `ReplayPlugin.init` (via `Install`: the fork's `ctx.base.observer`,
 *  or the agent's `Hooks.observer`). Writes schema v1
 *  (see `../SCHEMA-v1.md`).
 *
 *  One arena (tree identity -> dense id) serves both the typer event hooks and the per-phase walks
 *  done by `RecordPhase`. A tree gets its id the first time either sees it; its subtree is interned
 *  first, so children always have smaller ids than their parents.
 *
 *  Hooks only allocate ids and buffer. Anything that could force a completion (printing types,
 *  symbol names, messages, `tree.symbol`) waits for the end of the phase, when the buffered
 *  records are written: definitions first (sym, type, node), then `ev` lines in seq order.
 */
final class Recorder(outPath: String, groupName: Map[Phase, String], options: List[String]) extends Observer:
  private val withSyms = !options.contains("nosyms")
  private val withText = !options.contains("notext")
  private val withDerive = !options.contains("noderive")
  private val withEvents = !options.contains("noevents")
  private val noTypeText = options.contains("notypetext") // reproducibility experiment: print no type text at all
  private val check = options.contains("check") // verify offsets: slice == node's own show
  private val jvm: JvmRecorder | Null = if options.contains("nojvm") then null else JvmRecorder(this)

  private val out = new BufferedWriter(new FileWriter(outPath), 1 << 16)
  private var started = false
  private var closed = false

  // ---- arena ----------------------------------------------------------------------------------

  private val nodeIds = new IdentityHashMap[Tree[?], Integer]
  private val nodeTree = mutable.ArrayBuffer[Tree[?]]()
  private val nodeKind = mutable.ArrayBuffer[String]()
  private val nodeSpan = mutable.ArrayBuffer[Long]()         // packed (start, end), -1 if none
  private val nodeChildren = mutable.ArrayBuffer[Array[Int]]()
  private val nodeSym = mutable.ArrayBuffer[Int]()           // filled when the node record is written
  private val nodeName = mutable.ArrayBuffer[String | Null]()
  private val nodeTpe = mutable.ArrayBuffer[Type | Null]()   // type object at the last types pass
  private val nodeTypeText = mutable.ArrayBuffer[String | Null]() // last text emitted in version.types
  private var nodesWritten = 0                               // node records [0, nodesWritten) are out

  private val symIds = new IdentityHashMap[Symbol, Integer]
  private val symById = mutable.ArrayBuffer[Symbol]()
  private val symFirst = mutable.ArrayBuffer[(Int, Int)]()   // (running phase group, seq) at first sight
  private val symWritten = new java.util.BitSet
  private val symLast = mutable.HashMap[Int, (String, String, Int, String)]() // last syminfo (info, flags, owner, name)
  private val symFp = new IdentityHashMap[Symbol, (AnyRef, AnyRef, Long, Symbol, Name)]() // cheap change test

  private val typeIds = new IdentityHashMap[Type, Integer]
  private val pendingTypes = mutable.ArrayBuffer[Type]()
  private var typesWritten = 0

  private var unitOf = Map[SourceFile, Int]()

  // ---- events ---------------------------------------------------------------------------------

  private var nextSeq = 0
  private val stack = mutable.ArrayBuffer[Int]()
  private var suspended = 0
  var perturbations = 0

  private var running: Phase | Null = null      // phase group the Run is executing
  private def runningId: Int = if running == null then -1 else running.nn.id
  private var lastEnd: (Phase | Null, Int) = (null, -1) // group and seq of the last PhaseEnd

  /** A buffered event. `syms` holds Symbols, or trees whose symbol is resolved at flush. */
  private final class Ev(val seq: Int, val parent: Int, val kind: String, val phase: Int, val unit: Int,
      val span: Span, val nodes: Array[Int], val syms: List[Symbol | tpd.Tree], val payload: String | Diagnostic,
      val reporter: String = "")
  private val evBuf = mutable.ArrayBuffer[Ev]()
  private val edgeBuf = mutable.ArrayBuffer[(String, Symbol, Symbol | tpd.Tree, Int)]()
  private val evCount = mutable.HashMap[String, Int]()

  // per phase group lineage, cleared at each phaseBegin: new tree -> tree it was made from
  private var copiedFrom = new IdentityHashMap[Tree[?], Tree[?]]
  private var typedFrom = new IdentityHashMap[Tree[?], Tree[?]]
  private var retypedFrom = new IdentityHashMap[Tree[?], Tree[?]]

  // per unit: previous version
  private val prevVersion = mutable.HashMap[Int, (Int, java.util.BitSet, Array[Int])]() // unit -> (phase, live, reachable)
  private val prevText = mutable.HashMap[Int, (Int, String, List[List[Int]])]()

  // stats
  var offsetChecks, offsetFailures, textMismatches, versionsPrinted, versionsSkipped = 0
  private val deriveCount = mutable.HashMap[(String, String), Int]()
  val failKinds = mutable.HashMap[String, Int]()

  private def spanOf(sp: Span): Long = if sp.exists then (sp.start.toLong << 32) | sp.end.toLong else -1L
  private def spanJson(sp: Long): Any = if sp < 0 then null else List((sp >>> 32).toInt, sp.toInt)

  private def quietly[T](op: => T): T =
    suspended += 1
    try op finally suspended -= 1

  // ---- start / close --------------------------------------------------------------------------

  private def ensureStarted()(using Context): Unit =
    if !started then
      started = true
      val units = ctx.run.nn.units
      unitOf = units.zipWithIndex.map((u, i) => u.source -> i).toMap
      val st = ctx.settingsState
      val args = ctx.settings.userSetSettings(st).map(s => s"${s.name}=${s.valueIn(st)}")
      emit(obj("t" -> "meta", "schema" -> "v1", "compiler" -> Properties.simpleVersionString,
        "args" -> args.toList,
        "requires" -> (if jvm == null then Nil else List("-Ybackend-parallelism:1")), "units" -> units.zipWithIndex.map((u, i) =>
          obj("id" -> i, "path" -> u.source.file.path, "text" -> String(u.source.content)))))
      for p <- flatPhases if p.exists && !p.isInstanceOf[RecordPhase] && p.phaseName != "terminal"
          && !p.isInstanceOf[dotty.tools.dotc.transform.TreeChecker] do
        emit(obj("t" -> "phase", "id" -> p.id, "name" -> p.phaseName, "mini" -> p.isInstanceOf[MiniPhase],
          "group" -> groupOf(p).id, "megaOf" -> groupName.get(p).orNull,
          "denotTransformer" -> p.isInstanceOf[DenotTransformer]))

  def close()(using Context): Unit =
    if !closed then
      closed = true
      flush()
      // JVM versions, after the tree versions, when the last group run was genBCode
      if jvm != null && lastEnd._1 != null && lastEnd._1.nn.phaseName == "genBCode" then
        quietly(jvm.nn.emit(lastEnd._1.nn.id, lastEnd._2, ctx.base.allPhases.map(_.id).max + 1))
      emit(obj("t" -> "stats", "events" -> nextSeq, "nodes" -> nodeKind.length, "syms" -> symById.length,
        "types" -> typesWritten, "perturbations" -> perturbations,
        "versionsPrinted" -> versionsPrinted, "versionsSkipped" -> versionsSkipped,
        "evByKind" -> obj(evCount.toList.sortBy(_._1)*),
        "deriveByHow" -> obj(deriveCount.toList.sortBy(_._1).map((k, v) => s"${k._1}/${k._2}" -> v)*),
        "jvm" -> (if jvm == null then null else obj(jvm.nn.stats*))))
      out.close()
      Install.uninstall(this)
      if options.contains("timing") && jvm != null then
        System.err.println(f"[replay] jvm recording: ${jvm.nn.nanos / 1e6}%.0f ms")
      if check then
        System.err.println(s"[replay] offset checks: $offsetChecks, failures: $offsetFailures ${failKinds.toList.sortBy(-_._2).mkString(" ")}, text mismatches: $textMismatches")

  // ---- interning ------------------------------------------------------------------------------

  private def children(t: Tree[?]): List[Tree[?]] =
    val b = List.newBuilder[Tree[?]]
    def add(x: Any): Unit = x match
      case c: Tree[?] => b += c
      case xs: List[?] => xs.foreach(add)
      case _ =>
    t.productIterator.foreach(add)
    b.result()

  /** The arena id of `t`, interning it (and first its subtree) if new. Safe at hook time: reads
   *  only fields, no symbols or types.
   */
  private def nodeId(t: Tree[?]): Int =
    val known = nodeIds.get(t)
    if known != null then known.intValue
    else
      val kids = children(t).map(nodeId).toArray
      val id = nodeKind.length
      nodeIds.put(t, id)
      nodeTree += t
      nodeKind += t.productPrefix
      nodeSpan += spanOf(t.span)
      nodeChildren += kids
      nodeSym += -1
      nodeName += null
      nodeTpe += null
      nodeTypeText += null
      id

  private def nodeIdOrNull(t: Tree[?] | Null): Any = if t == null || t.isEmpty then null else nodeId(t)

  private def symId(sym: Symbol)(using Context): Int =
    if !sym.exists then -1
    else
      val known = symIds.get(sym)
      if known != null then known.intValue
      else
        val id = symById.length
        symIds.put(sym, id)
        symById += sym
        symFirst += ((runningId, if stack.isEmpty then -1 else stack.last))
        id

  private def typeId(tp: Type | Null): Any =
    if tp == null then null
    else
      val known = typeIds.get(tp)
      if known != null then known.intValue
      else
        val id = typeIds.size
        typeIds.put(tp, id)
        pendingTypes += tp
        id

  private def unitId(src: SourceFile): Any = unitOf.get(src) match
    case Some(i) => i
    case None => null

  private def symOf(t: Tree[?])(using Context): Symbol =
    if t.hasType then
      try t.asInstanceOf[tpd.Tree].symbol catch case _: Exception => NoSymbol
    else NoSymbol

  /** Write pending definitions: node records (resolving their symbols), then symbols they or the
   *  events refer to (owners first), then type texts. Callers write the records using them after.
   */
  private def flushDefs(printPhase: Phase | Null)(using Context): Unit = quietly:
    // resolve node symbols and names before writing symbols, since they intern more symbols
    var i = nodesWritten
    while i < nodeKind.length do
      val t = nodeTree(i)
      val s = symOf(t)
      if s.exists then nodeSym(i) = symId(s)
      nodeName(i) = t match
        case t: NameTree[?] => t.name.toString
        case t: Literal[?] => t.const.show
        case _ => null
      i += 1
    writeSyms()
    val pctx = if printPhase != null && printPhase.nn.next.exists then ctx.withPhase(printPhase.nn.next) else ctx
    while typesWritten < pendingTypes.length do
      val tp = pendingTypes(typesWritten)
      val text = if noTypeText then "" else try tp.show(using pctx) catch case ex: Exception => s"<error: ${ex.getClass.getSimpleName}>"
      emit(obj("t" -> "type", "id" -> typesWritten, "text" -> text))
      typesWritten += 1
    while nodesWritten < nodeKind.length do
      val n = nodesWritten
      emit(obj("t" -> "node", "id" -> n, "kind" -> nodeKind(n), "span" -> spanJson(nodeSpan(n)),
        "unit" -> unitId(nodeTree(n).source), "children" -> nodeChildren(n).toList,
        "sym" -> (if nodeSym(n) < 0 then null else nodeSym(n)), "name" -> nodeName(n)))
      nodesWritten += 1

  private def writeSyms()(using Context): Unit =
    var i = 0
    while i < symById.length do
      writeSym(i)
      i += 1

  private def writeSym(id: Int)(using Context): Unit =
    if !symWritten.get(id) then
      symWritten.set(id)
      val sym = symById(id)
      val owner = sym.maybeOwner
      val ownerId = if owner.exists && !owner.isRoot then symId(owner) else -1
      if ownerId >= 0 then writeSym(ownerId)
      val local = sym.isDefinedInCurrentRun
      val (ph, sq) = symFirst(id)
      emit(obj("t" -> "sym", "id" -> id, "name" -> sym.name.toString, "owner" -> (if ownerId < 0 then null else ownerId),
        "fullName" -> sym.showFullName, "kind" -> (if sym.isClass then "class" else if sym.isType then "type" else "term"),
        "local" -> local, "unit" -> (if local then unitId(sym.source) else null),
        "firstPhase" -> ph, "firstSeq" -> (if sq < 0 then null else sq)))

  // ---- phases ---------------------------------------------------------------------------------

  /** The runtime phase (a MegaPhase, or the phase itself) that runs `p`. */
  def groupOf(p: Phase)(using Context): Phase =
    ctx.base.allPhases.find:
      case m: MegaPhase => m.miniPhases.exists(_ eq p)
      case q => q eq p
    .getOrElse(p)

  private def allocSeq(): Int =
    val s = nextSeq
    nextSeq += 1
    s

  override def phaseBegin(phase: Phase)(using Context): Unit =
    if phase.isInstanceOf[RecordPhase] then suspended += 1
    else
      ensureStarted()
      running = phase
      copiedFrom = new IdentityHashMap
      typedFrom = new IdentityHashMap
      retypedFrom = new IdentityHashMap
      val members = phase match
        case m: MegaPhase => m.miniPhases.toList.map(_.phaseName)
        case p => List(p.phaseName)
      addEv(Ev(allocSeq(), -1, "PhaseBegin", phase.id, -1, dotty.tools.dotc.util.Spans.NoSpan, Array(), Nil,
        s"""{"name":${jstr(phase.phaseName)},"members":[${members.map(jstr).mkString(",")}]}"""))

  override def phaseEnd(phase: Phase, units: List[CompilationUnit])(using Context): Unit =
    if phase.isInstanceOf[RecordPhase] then suspended -= 1
    else
      val s = allocSeq()
      addEv(Ev(s, -1, "PhaseEnd", phase.id, -1, dotty.tools.dotc.util.Spans.NoSpan, Array(), Nil, "{}"))
      lastEnd = (phase, s)
      flush(phase)
      running = null

  /** Write everything buffered: definitions, then events in seq order, then edges. */
  def flush(printPhase: Phase | Null = running)(using Context): Unit =
    ensureStarted()
    flushDefs(printPhase)
    val evs = evBuf.sortBy(_.seq)
    evBuf.clear()
    val lines = mutable.ArrayBuffer[String]()
    quietly:
      for e <- evs do
        val syms = e.syms.map {
          case s: Symbol => symId(s)
          case t: tpd.Tree => symId(symOf(t))
        }.filter(_ >= 0)
        val payload = e.payload match
          case s: String => s
          case dia: Diagnostic =>
            val msg = try dia.msg.message catch case ex: Exception => s"<${ex.getClass.getSimpleName}>"
            s"""{"level":${dia.level},"msg":${jstr(msg)},"reporter":${jstr(e.reporter)}}"""
        val sb = new java.lang.StringBuilder(160)
        sb.append("""{"t":"ev","seq":""").append(e.seq)
          .append(""","parent":""").append(if e.parent < 0 then "null" else e.parent.toString)
          .append(""","kind":"""").append(e.kind)
          .append("""","phase":""").append(e.phase)
          .append(""","unit":""").append(if e.unit < 0 then "null" else e.unit.toString)
          .append(""","span":""").append(if e.span.exists then s"[${e.span.start},${e.span.end}]" else "null")
          .append(""","nodes":[""").append(e.nodes.map(n => if n < 0 then "null" else n.toString).mkString(","))
          .append("""],"syms":[""").append(syms.mkString(","))
          .append("""],"payload":""").append(payload).append('}')
        lines += sb.toString
      // edges between the top-level classes enclosing `from` and `to`, aggregated per phase
      val agg = mutable.LinkedHashMap[(String, Int, Int), (Int, Int)]()
      for (kind, from, to0, seq) <- edgeBuf do
        val to = to0 match
          case s: Symbol => s
          case t: tpd.Tree => symOf(t)
        if from.exists && to.exists && !to.is(Package) then
          val f = from.topLevelClass
          val t = to.topLevelClass
          if (f ne t) && f.exists && t.exists && !t.is(PackageClass) then
            val key = (kind, symId(f), symId(t))
            agg(key) = agg.get(key) match
              case Some((w, s0)) => (w + 1, s0)
              case None => (1, seq)
      edgeBuf.clear()
      for ((kind, f, t), (w, s)) <- agg do
        lines += s"""{"t":"edge","kind":"$kind","from":$f,"to":$t,"weight":$w,"ev":$s,"phase":${if printPhase == null then "null" else printPhase.nn.id}}"""
    flushDefs(printPhase) // symbols interned while rendering events and edges
    for l <- lines do { out.write(l); out.write('\n') }
    out.flush()

  // ---- event hooks ----------------------------------------------------------------------------

  private def addEv(e: Ev): Unit =
    evBuf += e
    evCount(e.kind) = evCount.getOrElse(e.kind, 0) + 1

  private def enter(): Unit =
    if suspended > 0 || !withEvents then perturbations += (if suspended > 0 then 1 else 0)
    else stack += allocSeq()

  private def active: Boolean = suspended == 0 && withEvents

  /** Pop the event opened by the matching `enter`; returns its seq, or -1 if it was not recorded. */
  private def exit(): Int =
    if !active then -1
    else
      val s = stack.remove(stack.length - 1)
      s

  private def parent: Int = if stack.isEmpty then -1 else stack.last
  private def curUnit(using Context): Int = unitOf.getOrElse(ctx.source, -1)

  override def typedEnter(tree: untpd.Tree, pt: Type)(using Context): Unit = enter()
  override def typedExit(tree: untpd.Tree, pt: Type, res: tpd.Tree | Null)(using Context): Unit =
    val seq = exit()
    if seq >= 0 then
      val retyped = !ctx.phase.isTyper
      val in = nodeId(tree)
      val outId = if res == null then -1 else nodeId(res)
      if res != null && (res ne tree) then
        (if retyped then retypedFrom else typedFrom).put(res, tree)
      val payload =
        if res == null then s"""{"pt":${typeId(pt)},"aborted":true}"""
        else s"""{"pt":${typeId(pt)},"tpe":${typeId(res.typeOpt)},"result":"${res.productPrefix}"}"""
      val syms: List[Symbol | tpd.Tree] = res match
        case res: tpd.RefTree =>
          edgeBuf += (("resolves", ctx.owner, res, seq))
          List(res)
        case res: tpd.MemberDef => List(res)
        case _ => Nil
      addEv(Ev(seq, parent, if retyped then "Retyped" else "Typed", runningId, curUnit, tree.span, Array(in, outId), syms, payload))

  override def adaptEnter(tree: tpd.Tree, pt: Type)(using Context): Unit = enter()
  override def adaptExit(tree: tpd.Tree, pt: Type, res: tpd.Tree | Null)(using Context): Unit =
    val seq = exit()
    if seq >= 0 then
      if (res eq tree) && nextSeq == seq + 1 then
        nextSeq = seq // a no-op adapt with nothing nested: give the seq back, so seq has no gaps
      else
        val outId = if res == null then -1 else nodeId(res)
        addEv(Ev(seq, parent, "Adapt", runningId, curUnit, tree.span, Array(nodeId(tree), outId), Nil,
          s"""{"pt":${typeId(pt)},"changed":${res ne tree}}"""))

  override def completeEnter(denot: SymDenotation)(using Context): Unit = enter()
  override def completeExit(denot: SymDenotation, ok: Boolean)(using Context): Unit =
    val seq = exit()
    if seq >= 0 then
      val sym = denot.symbol
      edgeBuf += (("forces", ctx.owner, sym, seq))
      addEv(Ev(seq, parent, "Complete", runningId, curUnit, sym.span, Array(), List(sym),
        s"""{"ok":$ok,"forcedBy":${val o = symId(ctx.owner); if o < 0 then "null" else o}}"""))

  override def implicitSearchEnter(pt: Type, argument: tpd.Tree, span: Span)(using Context): Unit = enter()
  override def implicitSearchExit(pt: Type, argument: tpd.Tree, span: Span, res: SearchResult | Null)(using Context): Unit =
    val seq = exit()
    if seq >= 0 then
      val (result, syms) = res match
        case res: SearchSuccess =>
          edgeBuf += (("implicit", ctx.owner, res.tree, seq))
          ("\"success\"", List(res.tree))
        case res: SearchFailure => (jstr(res.reason.getClass.getSimpleName), Nil)
        case null => ("null", Nil)
      val arg = if argument.isEmpty then -1 else nodeId(argument)
      val chosen = res match
        case res: SearchSuccess => nodeId(res.tree)
        case _ => -1
      addEv(Ev(seq, parent, "ImplicitSearch", runningId, curUnit, span, Array(arg, chosen), syms,
        s"""{"pt":${typeId(pt)},"result":$result}"""))

  override def candidateEnter(ref: TermRef, span: Span)(using Context): Unit = enter()
  override def candidateExit(ref: TermRef, span: Span, res: SearchResult | Null)(using Context): Unit =
    val seq = exit()
    if seq >= 0 then
      val (accepted, reason) = res match
        case _: SearchSuccess => ("true", "null")
        case res: SearchFailure => ("false", jstr(res.reason.getClass.getSimpleName))
        case null => ("false", "\"aborted\"")
      val tree = res match
        case res: SearchSuccess => nodeId(res.tree)
        case _ => -1
      addEv(Ev(seq, parent, "Candidate", runningId, curUnit, span, Array(tree), List(ref.symbol),
        s"""{"ref":${typeId(ref)},"accepted":$accepted,"reason":$reason}"""))

  override def report(dia: Diagnostic, reporter: Reporter)(using Context): Unit =
    if !active then perturbations += (if suspended > 0 then 1 else 0)
    else
      val pos = dia.pos
      val span = if pos.exists then pos.span else dotty.tools.dotc.util.Spans.NoSpan
      val u = if pos.exists then unitOf.getOrElse(pos.source, -1) else -1
      addEv(Ev(allocSeq(), parent, "Report", runningId, u, span, Array(), Nil, dia, reporter.getClass.getSimpleName))

  override def copied(tree: Tree[?], copy: Tree[?]): Unit =
    if suspended == 0 then copiedFrom.put(copy, tree)

  // ---- JVM backend hooks (see JvmRecorder) --------------------------------------------------

  override def classNodes(typeDef: tpd.TypeDef, classNode: org.objectweb.asm.tree.ClassNode,
      mirror: org.objectweb.asm.tree.ClassNode | Null)(using Context): Unit =
    if jvm != null then quietly(jvm.nn.classNodes(typeDef, classNode, mirror))
  override def genMethod(dd: tpd.DefDef, method: org.objectweb.asm.tree.MethodNode)(using Context): Unit =
    if jvm != null then jvm.nn.genMethod(dd, method)
  override def genLoadEnter(tree: tpd.Tree, method: org.objectweb.asm.tree.MethodNode)(using Context): Unit =
    if jvm != null then jvm.nn.genLoadEnter(tree, method)
  override def genLoadExit(tree: tpd.Tree, method: org.objectweb.asm.tree.MethodNode)(using Context): Unit =
    if jvm != null then jvm.nn.genLoadExit(tree, method)
  override def optimizerPass(owner: String, method: org.objectweb.asm.tree.MethodNode, pass: String): Unit =
    if jvm != null then jvm.nn.optimizerPass(owner, method, pass)
  override def inlined(owner: String, method: org.objectweb.asm.tree.MethodNode, calleeOwner: String,
      callee: org.objectweb.asm.tree.MethodNode,
      insnMap: Map[org.objectweb.asm.tree.AbstractInsnNode, org.objectweb.asm.tree.AbstractInsnNode]): Unit =
    if jvm != null then jvm.nn.inlined(owner, method, calleeOwner, callee, insnMap)
  override def classWritten(classNode: org.objectweb.asm.tree.ClassNode): Unit =
    if jvm != null then jvm.nn.classWritten(classNode)

  // services for JvmRecorder
  private[replay] def unitIdOf(src: SourceFile): Int = unitOf.getOrElse(src, -1)
  private[replay] def treeNodeId(t: Tree[?]): Int = { val i = nodeIds.get(t); if i == null then -1 else i.intValue }
  private[replay] def copySource(t: Tree[?]): Tree[?] | Null = copiedFrom.get(t)
  private[replay] def lastTreeVersion(u: Int): (java.util.BitSet, Array[Int]) =
    prevVersion.get(u).map(v => (v._2, v._3)).getOrElse((new java.util.BitSet, Array()))
  private[replay] def nodeKindOf(id: Int): String = nodeKind(id)
  private[replay] def nodeSpanOf(id: Int): Long = nodeSpan(id)
  private[replay] def nodeSymOf(id: Int): Int = nodeSym(id)
  private[replay] def symIdOf(sym: Symbol): Int = { val i = symIds.get(sym); if i == null then -1 else i.intValue }
  private[replay] def emitObj(fields: (String, Any)*): Unit = emit(obj(fields*))
  private[replay] def countDerive(how: String, conf: String, n: Int): Unit =
    deriveCount((how, conf)) = deriveCount.getOrElse((how, conf), 0) + n
  /** A node outside the tree arena (`ir:"jvm"`): allocated in the shared id space, written at once. */
  private[replay] def newExtNode(fields: (String, Any)*): Int =
    assert(nodesWritten == nodeKind.length)
    val id = nodeKind.length
    val kind = fields.collectFirst { case ("kind", k: String) => k }.get
    nodeTree += null; nodeKind += kind; nodeSpan += -1L; nodeChildren += Array(); nodeSym += -1
    nodeName += null; nodeTpe += null; nodeTypeText += null
    val get = fields.toMap
    val std = List("t" -> "node", "id" -> id, "ir" -> "jvm", "kind" -> kind, "span" -> null, "unit" -> get.getOrElse("unit", null),
      "children" -> get.getOrElse("children", Nil), "sym" -> get.getOrElse("sym", null), "name" -> get.getOrElse("name", null))
    val stdKeys = std.map(_._1).toSet
    emit(obj((std ++ fields.filterNot((k, _) => stdKeys(k)))*))
    nodesWritten += 1
    id

  /** Agent only (the fork has no such hook). Not an enter/exit pair: a leaf under the current event. */
  override def rejected(ref: TermRef, reason: String)(using Context): Unit =
    if !active then perturbations += (if suspended > 0 then 1 else 0)
    else
      addEv(Ev(allocSeq(), parent, "Rejected", runningId, curUnit, dotty.tools.dotc.util.Spans.NoSpan, Array(), List(ref.symbol),
        s"""{"ref":${typeId(ref)},"reason":${jstr(reason)}}"""))

  // ---- versions -------------------------------------------------------------------------------

  /** Record one unit's tree as output of the phase group that ran last. */
  def recordUnit(group: Phase, unit: CompilationUnit)(using Context): Unit =
    val (endPhase, endSeq) = lastEnd
    if endPhase == null || (endPhase.nn ne group) then return // the group did not run (e.g. not runnable)
    val root: Tree[?] = if unit.tpdTree.isEmpty then unit.untpdTree else unit.tpdTree
    val u = unitOf(unit.source)
    val pid = group.id
    val reachable = mutable.ArrayBuffer[Int]()
    val seen = new java.util.BitSet
    // walk order, not IdentityHashMap order: identity hashes differ between runs
    val symSeen = new IdentityHashMap[Symbol, Unit]
    val syms = mutable.ArrayBuffer[Symbol]()
    var typeObjectsChanged = false

    def walk(t: Tree[?]): Unit =
      val id = nodeId(t)
      if !seen.get(id) then
        seen.set(id)
        reachable += id
        val tp = if t.hasType then t.typeOpt else null
        if tp ne nodeTpe(id) then typeObjectsChanged = true
        val s = symOf(t)
        if s.exists && !symSeen.containsKey(s) then { symSeen.put(s, ()); syms += s }
        children(t).foreach(walk)

    walk(root)
    val rootId = nodeId(root)
    val prev = prevVersion.get(u)
    val fresh = reachable.filter(id => prev.forall(!_._2.get(id))).sorted

    // symbols: cheap change test on (denotation, info, flags, owner, name) before printing anything
    var symsChanged = false
    val symLines = mutable.ArrayBuffer[Obj]()
    if withSyms then
      for sym <- syms do
        symId(sym)
        if sym.isDefinedInCurrentRun then
          val d = sym.denot
          val fp = (d: AnyRef, d.infoOrCompleter: AnyRef, d.flags.bits: Long, d.maybeOwner, d.name: Name)
          val last = symFp.get(sym)
          if last == null || !(last._1 eq fp._1) || !(last._2 eq fp._2) || last._3 != fp._3 || !(last._4 eq fp._4) || !(last._5 eq fp._5) then
            symFp.put(sym, fp)
            symsChanged = true
            val id = symId(sym)
            val owner = sym.maybeOwner
            val ownerId = if owner.exists && !owner.isRoot then symId(owner) else -1
            val info = try sym.info.show catch case ex: Exception => s"<error: ${ex.getClass.getSimpleName}>"
            val cur = (info, sym.flagsString, ownerId, sym.name.toString)
            if !symLast.get(id).contains(cur) then
              symLast(id) = cur
              symLines += obj("t" -> "syminfo", "sym" -> id, "fromPhase" -> pid, "name" -> cur._4, "info" -> cur._1,
                "flags" -> cur._2, "owner" -> (if ownerId < 0 then null else ownerId))

    val skip = prev.isDefined && fresh.isEmpty && !typeObjectsChanged && !symsChanged
    val versionFields = mutable.ListBuffer[(String, Any)]("t" -> "version", "phase" -> pid, "unit" -> u,
      "seq" -> endSeq, "root" -> rootId)
    if skip then
      versionsSkipped += 1
      prevText.get(u) match
        case Some((pp, _, _)) if withText => versionFields += "textSameAs" -> pp += "offsetsSameAs" -> pp
        case _ =>
    else
      versionsPrinted += 1
      if withText then
        val idOf: Tree[?] => Int = t => { val i = nodeIds.get(t); if i == null then -1 else i.intValue }
        val pctx = ctx.fresh.setPrinterFn(MarkingPrinter(_, idOf)).setProperty(XprintMode, ())
        val marked = root.show(using pctx)
        val flat = MarkingPrinter.flatten(marked)
        if check then verify(root, flat)
        // pre-order (by start, outer first) so a viewer can build the nesting with a stack
        val offs = flat.offsets.grouped(3).toList.sortBy(t => (t(1), -t(2), -t(0))).map(_.toList)
        prevText.get(u) match
          case Some((pp, t, _)) if t == flat.text => versionFields += "textSameAs" -> pp
          case _ => versionFields += "text" -> flat.text
        prevText.get(u) match
          case Some((pp, _, o)) if o == offs => versionFields += "offsetsSameAs" -> pp
          case _ => versionFields += "offsets" -> offs
        prevText(u) = (pid, flat.text, offs)
      // per-version type text, for nodes whose printed type changed since it was last listed
      val fullTypes = symsChanged || flatPhases.exists(p => (groupOf(p) eq group) && p.isInstanceOf[DenotTransformer])
      val memo = new IdentityHashMap[Type, String]
      val types = mutable.ArrayBuffer[List[Any]]()
      for id <- reachable do
        val t = nodeTree(id)
        val tp = if t.hasType then t.typeOpt else null
        if tp != null && (fullTypes || (tp ne nodeTpe(id))) then
          nodeTpe(id) = tp
          var text = memo.get(tp)
          if text == null then
            text = if noTypeText then "" else try tp.show catch case ex: Exception => s"<error: ${ex.getClass.getSimpleName}>"
            memo.put(tp, text)
          if text != nodeTypeText(id) then
            nodeTypeText(id) = text
            types += List(id, text)
      if types.nonEmpty then versionFields += "types" -> types
    versionFields += "nodes" -> reachable.length
    versionFields += "new" -> fresh.length

    flushDefs(group) // node records (symbols, names) are needed by derive
    val deriveLines = if withDerive && prev.isDefined then derive(pid, u, fresh, seen, prev.get) else Nil
    emit(obj(versionFields.toList*))
    symLines.foreach(emit)
    deriveLines.foreach(emit)
    val live = new java.util.BitSet
    reachable.foreach(live.set)
    prevVersion(u) = (pid, live, reachable.toArray)
  end recordUnit

  /** Lineage of the nodes new in this version relative to the previous one.
   *
   *  Exact sources come from the copier hook and from `Typed`/`Retyped` events (new tree -> tree it
   *  was made from, followed until a node of the previous version); the rest is matched by span
   *  (Track A's heuristic). 1:1 pairs that change nothing but children which are themselves pure
   *  copies are flagged `copy`. Several new nodes from one old node are a `split`. Old nodes that
   *  left the tree with no successor are `elide`; new nodes with no ancestor are `synth`.
   */
  private def derive(pid: Int, u: Int, fresh: collection.Seq[Int], live: java.util.BitSet,
      prev: (Int, java.util.BitSet, Array[Int]))(using Context): List[Obj] =
    val (_, prevLive, prevReach) = prev
    val src = new Array[Int](nodeKind.length)          // new id -> old id, -1 none
    java.util.Arrays.fill(src, -1)
    val how = mutable.HashMap[Int, String]()           // new id -> rewrite | retype
    val exact = new java.util.BitSet

    def exactSource(t0: Tree[?]): (Int, String) =
      var t = t0
      var h = "rewrite"
      var steps = 0
      while steps < 1000 do
        // a retyping typer (Erasure, Inlining) also copies; label the step by the retyping
        var s = retypedFrom.get(t)
        if s != null then h = "retype"
        else
          s = copiedFrom.get(t)
          if s == null then s = typedFrom.get(t)
        if s == null then return (-1, h)
        val sid = nodeIds.get(s)
        if sid != null && prevLive.get(sid.intValue) then return (sid.intValue, h)
        t = s
        steps += 1
      (-1, h)

    val bySpan = mutable.HashMap[Long, mutable.ArrayBuffer[Int]]()
    for o <- prevReach if nodeSpan(o) >= 0 do bySpan.getOrElseUpdate(nodeSpan(o), mutable.ArrayBuffer()) += o
    val synth = mutable.ArrayBuffer[Int]()
    for n <- fresh do
      val (o, h) = exactSource(nodeTree(n))
      if o >= 0 then
        src(n) = o; how(n) = h; exact.set(n)
      else
        val sp = nodeSpan(n)
        bySpan.get(if sp < 0 then Long.MinValue else sp) match
          case Some(cands) =>
            // prefer old nodes that were dropped in this version (replaced), then same kind;
            // ties go to the outermost (largest id = latest in post-order)
            def rank(o: Int) = (if live.get(o) then 0 else 2) + (if nodeKind(o) == nodeKind(n) then 1 else 0)
            src(n) = cands.maxBy(o => (rank(o), o))
            how(n) = "rewrite"
          case None => synth += n

    // pure copies, children first (post-order ids)
    val pure = new java.util.BitSet
    for n <- fresh if src(n) >= 0 do
      val o = src(n)
      val nk = nodeChildren(n); val ok = nodeChildren(o)
      if nodeKind(n) == nodeKind(o) && nodeName(n) == nodeName(o) && nodeSym(n) == nodeSym(o)
         && nodeSpan(n) == nodeSpan(o) && nodeTypeText(n) == nodeTypeText(o) && nk.length == ok.length
         && nk.indices.forall(i => nk(i) == ok(i) || pure.get(nk(i)) && src(nk(i)) == ok(i))
      then pure.set(n)

    val lines = mutable.ArrayBuffer[Obj]()
    def rec(h: String, conf: String, news: collection.Seq[Int], olds: collection.Seq[Int]): Unit =
      deriveCount((h, conf)) = deriveCount.getOrElse((h, conf), 0) + news.length.max(olds.length)
      lines += obj("t" -> "derive", "phase" -> pid, "unit" -> u, "new" -> news.toList, "old" -> olds.toList,
        "how" -> h, "confidence" -> conf)
    val usedOld = new java.util.BitSet
    val groups = mutable.LinkedHashMap[(Int, Boolean, String), mutable.ArrayBuffer[Int]]()
    for n <- fresh if src(n) >= 0 do
      usedOld.set(src(n))
      val conf = if exact.get(n) then "exact" else "heuristic"
      if pure.get(n) then rec("copy", conf, List(n), List(src(n)))
      else groups.getOrElseUpdate((src(n), exact.get(n), how(n)), mutable.ArrayBuffer()) += n
    for ((o, ex, h), ns) <- groups do
      rec(if ns.length > 1 then "split" else h, if ex then "exact" else "heuristic", ns, List(o))
    if synth.nonEmpty then rec("synth", "heuristic", synth, Nil)
    val elided = prevReach.filter(o => !live.get(o) && !usedOld.get(o))
    if elided.nonEmpty then rec("elide", "heuristic", Nil, elided.toSeq)
    lines.toList

  /** Spot-check offsets: every slice must equal the node's own `show` modulo whitespace, and the
   *  marker-free text must equal the plain printer's output.
   */
  private def verify(root: Tree[?], flat: MarkingPrinter.Flat)(using Context): Unit =
    val plainCtx = ctx.fresh.setProperty(XprintMode, ())
    val plain = root.show(using plainCtx)
    if plain != flat.text then
      textMismatches += 1
      if textMismatches <= 3 then
        val i = plain.zip(flat.text).indexWhere(_ != _) match { case -1 => plain.length min flat.text.length; case i => i }
        System.err.println(s"[replay] text mismatch at $i:\n  plain=${plain.slice(i - 60, i + 60)}\n  flat =${flat.text.slice(i - 60, i + 60)}")
    def norm(s: String) = s.replaceAll("(<empty>|[\\w$]+)\\.(this|super)\\b", "$2").replaceAll("[()\\s]", "")
    var k = 0
    while k < flat.offsets.length do
      val id = flat.offsets(k)
      val slice = flat.text.substring(flat.offsets(k + 1), flat.offsets(k + 2))
      offsetChecks += 1
      val own = nodeTree(id).show(using plainCtx)
      val ok = norm(slice) == norm(own) || norm("new " + slice) == norm(own)
        || nodeKind(id) == "TypeDef" && norm(slice).endsWith(norm(own))
      if !ok then
        offsetFailures += 1
        failKinds(nodeKind(id)) = failKinds.getOrElse(nodeKind(id), 0) + 1
        if failKinds(nodeKind(id)) <= 2 then
          System.err.println(s"[replay] offset mismatch node $id ${nodeKind(id)}:\n  slice=${slice.take(400)}\n  show =${own.take(400)}")
      k += 3

  // --- minimal JSON ----------------------------------------------------------------------------

  private final class Obj(val fields: Seq[(String, Any)])
  private def obj(fields: (String, Any)*): Obj = Obj(fields)

  private def emit(o: Obj): Unit =
    val sb = new java.lang.StringBuilder(128)
    write(sb, o)
    sb.append('\n')
    out.write(sb.toString)

  private def jstr(s: String): String =
    val sb = new java.lang.StringBuilder(s.length + 2)
    str(sb, s)
    sb.toString

  private def write(sb: java.lang.StringBuilder, x: Any): Unit = x match
    case null => sb.append("null")
    case s: String => str(sb, s)
    case i: Int => sb.append(i)
    case i: Integer => sb.append(i.intValue)
    case b: Boolean => sb.append(b)
    case o: Obj =>
      sb.append('{')
      var first = true
      for (k, v) <- o.fields do
        if !first then sb.append(',')
        first = false
        str(sb, k); sb.append(':'); write(sb, v)
      sb.append('}')
    case xs: Iterable[?] =>
      sb.append('[')
      var first = true
      for v <- xs do
        if !first then sb.append(',')
        first = false
        write(sb, v)
      sb.append(']')
    case other => str(sb, other.toString)

  private def str(sb: java.lang.StringBuilder, s: String): Unit =
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
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c => sb.append(c)
      i += 1
    sb.append('"')
end Recorder
