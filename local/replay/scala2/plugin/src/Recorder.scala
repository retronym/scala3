// LLM-assisted (Claude), see LLM_POLICY.md. Prototype; lives in local/, not for upstream.
package replay.nsc

import java.io.{BufferedWriter, FileWriter, PrintWriter, StringWriter}
import java.util.IdentityHashMap

import scala.annotation.nowarn
import scala.collection.mutable
import scala.reflect.internal.util.{Position, SourceFile}
import scala.tools.nsc.Global
import scala.reflect.internal.Phase

/** One per compiler run, installed by `ReplayPlugin.init`; the agent's hooks reach it through `Bridge`.
 *  Writes schema v1 (`../../SCHEMA-v1.md`) for nsc, see `../DESIGN.md`.
 *
 *  The arena interns trees by identity, as on Scala 3, but a node record is a snapshot of the tree's
 *  symbol, span and children: nsc mutates trees in place (typer and erasure set `tpe`/`symbol` on the same
 *  node, `setPos` moves it), so a tree whose snapshot no longer matches gets a fresh id, and the old id
 *  stays what the earlier version and events saw. `nodePrev` links a fresh id to the same tree's previous
 *  snapshot, which is exact lineage.
 *
 *  Hooks only allocate ids and buffer. Anything that prints (types, symbols, messages) waits for the end
 *  of the phase, when the buffered records are written: definitions first, then `ev` lines in seq order.
 */
final class Recorder(val global: Global, outPath: String, options: List[String]) {
  import global._
  import Recorder.Obj

  private val withSyms = !options.contains("nosyms")
  private val withText = !options.contains("notext")
  private val withDerive = !options.contains("noderive")
  private val withEvents = !options.contains("noevents")
  private val check = options.contains("check")
  val jvm: JvmRecorder = if (options.contains("nojvm")) null else new JvmRecorder(this)

  private val out = new BufferedWriter(new FileWriter(outPath), 1 << 16)
  private var started = false
  private var closed = false

  // ---- arena ----------------------------------------------------------------------------------

  private val nodeIds = new IdentityHashMap[Tree, Integer]        // latest snapshot of each tree
  private val nodeTree = mutable.ArrayBuffer[Tree]()
  private val nodeKind = mutable.ArrayBuffer[String]()
  private val nodeSpan = mutable.ArrayBuffer[Long]()               // packed (start, end), -1 if none
  private val nodeChildren = mutable.ArrayBuffer[Array[Int]]()
  private val nodeSymObj = mutable.ArrayBuffer[Symbol]()           // the snapshot's symbol (null if none)
  private val nodeSym = mutable.ArrayBuffer[Int]()                 // its id, filled when the record is written
  private val nodeName = mutable.ArrayBuffer[String]()
  private val nodeTpe = mutable.ArrayBuffer[Type]()                // type object at the last types pass
  private val nodeTypeText = mutable.ArrayBuffer[String]()         // last text emitted in version.types
  private val nodePrev = mutable.ArrayBuffer[Int]()                // the same tree's previous snapshot, -1
  private var nodesWritten = 0
  var reIds = 0
  var positionsRestored = 0

  private val symIds = new IdentityHashMap[Symbol, Integer]
  private val symById = mutable.ArrayBuffer[Symbol]()
  private val symFirst = mutable.ArrayBuffer[(Int, Int)]()
  private val symWritten = new java.util.BitSet
  private val symLast = mutable.HashMap[Int, (String, String, Int, String)]()
  private val symFp = new IdentityHashMap[Symbol, (AnyRef, Long, Symbol, Name)]()

  private val typeIds = new IdentityHashMap[Type, Integer]
  private val pendingTypes = mutable.ArrayBuffer[Type]()
  private var typesWritten = 0

  private var unitOf = Map[SourceFile, Int]()
  private var unitOfFile = Map[String, Int]()

  // ---- events ---------------------------------------------------------------------------------

  private var nextSeq = 0
  private val stack = mutable.ArrayBuffer[Int]()
  private var suspended = 0
  var perturbations = 0

  private var running: Phase = null
  private def runningId: Int = if (running == null) -1 else running.id
  private var lastEnd: (Phase, Int) = (null, -1)
  private lazy val typerId = currentRun.typerPhase.id

  private final class Ev(val seq: Int, val parent: Int, val kind: String, val phase: Int, val unit: Int, val span: Long,
      val nodes: Array[Int], val syms: List[AnyRef], val payload: () => String)
  private val evBuf = mutable.ArrayBuffer[Ev]()
  private val edgeBuf = mutable.ArrayBuffer[(String, Symbol, AnyRef, Int)]()
  private val evCount = mutable.HashMap[String, Int]()

  // per phase lineage, cleared at each phaseBegin: new tree -> tree it was made from
  private var copiedFrom = new IdentityHashMap[Tree, Tree]
  private var typedFrom = new IdentityHashMap[Tree, Tree]

  private val prevVersion = mutable.HashMap[Int, (Int, java.util.BitSet, Array[Int])]()
  private val prevText = mutable.HashMap[Int, (Int, String, List[List[Int]])]()

  var offsetChecks, offsetFailures, versionsPrinted, versionsSkipped = 0
  private val deriveCount = mutable.HashMap[(String, String), Int]()

  private def pack(s: Int, e: Int): Long = (s.toLong << 32) | (e.toLong & 0xffffffffL)
  def spanOf(pos: Position): Long =
    if (pos == null || (pos eq NoPosition) || !pos.isDefined) -1L
    else if (pos.isRange) pack(pos.start, pos.end)
    else pack(pos.point, pos.point)
  private def spanJson(sp: Long): Any = if (sp < 0) null else List((sp >>> 32).toInt, sp.toInt)
  private def spanStr(sp: Long): String = if (sp < 0) "null" else s"[${(sp >>> 32).toInt},${sp.toInt}]"

  private def quietly[T](op: => T): T = {
    suspended += 1
    try op finally suspended -= 1
  }

  // ---- start / close --------------------------------------------------------------------------

  private def phasesOfRun: List[Phase] = {
    val b = List.newBuilder[Phase]
    var p: Phase = currentRun.parserPhase
    while (p.hasNext) { b += p; p = p.next }
    b.result()
  }

  private def ensureStarted(): Unit = if (!started) {
    started = true
    val units = currentRun.units.toList
    unitOf = units.zipWithIndex.map { case (u, i) => u.source -> i }.toMap
    unitOfFile = units.zipWithIndex.map { case (u, i) => u.source.file.path -> i }.toMap
    val args = settings.userSetSettings.toList.map(s => s"${s.name}=${s.value}")
    emit(obj("t" -> "meta", "schema" -> "v1", "compiler" -> scala.tools.nsc.Properties.versionNumberString,
      "frontend" -> "nsc", "args" -> args,
      "requires" -> (if (jvm == null) Nil else List("-Ybackend-parallelism:1")),
      "units" -> units.zipWithIndex.map { case (u, i) =>
        obj("id" -> i, "path" -> u.source.file.path, "text" -> new String(u.source.content)) }))
    for (p <- phasesOfRun)
      emit(obj("t" -> "phase", "id" -> p.id, "name" -> p.name, "mini" -> false, "group" -> p.id, "megaOf" -> null,
        "denotTransformer" -> infoTransformerPhases(p.name)))
  }

  private lazy val infoTransformerPhases: Set[String] =
    phaseDescriptors.collect { case c: scala.tools.nsc.transform.InfoTransform => c.phaseName }.toSet

  def close(): Unit = if (!closed) {
    closed = true
    ensureStarted()
    flush()
    if (jvm != null && lastEnd._1 != null && lastEnd._1.name == "jvm")
      quietly(jvm.emit(lastEnd._1.id, lastEnd._2, phasesOfRun.map(_.id).max + 2))
    emit(obj("t" -> "stats", "events" -> nextSeq, "nodes" -> nodeKind.length, "syms" -> symById.length,
      "types" -> typesWritten, "perturbations" -> perturbations, "reIds" -> reIds, "positionsRestored" -> positionsRestored,
      "versionsPrinted" -> versionsPrinted, "versionsSkipped" -> versionsSkipped,
      "evByKind" -> obj(evCount.toList.sortBy(_._1): _*),
      "deriveByHow" -> obj(deriveCount.toList.sortBy(_._1).map { case (k, v) => s"${k._1}/${k._2}" -> v }: _*),
      "jvm" -> (if (jvm == null) null else obj(jvm.stats: _*))))
    out.close()
    ReplayPlugin.uninstall(this)
    if (check) System.err.println(s"[replay] offset checks: $offsetChecks, failures: $offsetFailures")
  }

  // ---- interning ------------------------------------------------------------------------------

  /** The symbol a node record carries: the tree's own (SymTree), or its function's for Apply/TypeApply. */
  private def recSym(t: Tree): Symbol = {
    val s = t match {
      case _: SymTree | _: Apply | _: TypeApply => t.symbol
      case _ => null
    }
    if (s == null || (s eq NoSymbol)) null else s
  }

  private def newNode(t: Tree, kids: Array[Int], prev: Int): Int = {
    val id = nodeKind.length
    nodeIds.put(t, id)
    nodeTree += t
    nodeKind += t.productPrefix
    nodeSpan += spanOf(t.pos)
    nodeChildren += kids
    nodeSymObj += recSym(t)
    nodeSym += -1
    nodeName += null
    nodeTpe += null
    nodeTypeText += null
    nodePrev += prev
    if (prev >= 0) reIds += 1
    id
  }

  private def sameSnapshot(i: Int, t: Tree, kids: Array[Int]): Boolean =
    (nodeSymObj(i) eq recSym(t)) && nodeSpan(i) == spanOf(t.pos) && java.util.Arrays.equals(nodeChildren(i), kids)

  /** Hook time: the id of `t`'s current snapshot, checking the tree's own fields and its children's latest
   *  ids one level down (a child typed or re-identified since shows here). Reads fields only.
   */
  def nodeId(t: Tree): Int = {
    val known = nodeIds.get(t)
    if (known != null) {
      val i = known.intValue
      val kids = nodeChildren(i)
      val cs = t.children
      var same = (nodeSymObj(i) eq recSym(t)) && nodeSpan(i) == spanOf(t.pos) && cs.length == kids.length
      var k = 0
      var it = cs
      while (same && it.nonEmpty) {
        val c = nodeIds.get(it.head)
        same = c != null && c.intValue == kids(k)
        it = it.tail; k += 1
      }
      if (same) i
      else newNode(t, cs.map(nodeId).toArray, i)
    } else newNode(t, t.children.map(nodeId).toArray, -1)
  }

  private def nodeIdOrNull(t: Tree): Int = if (t == null || t.isEmpty) -1 else nodeId(t)

  /** Version time: re-check the whole subtree (post-order), re-identifying every tree whose snapshot changed. */
  private def walkId(t: Tree, memo: IdentityHashMap[Tree, Integer], order: mutable.ArrayBuffer[Int]): Int = {
    val m = memo.get(t)
    if (m != null) m.intValue
    else {
      val kids = t.children.map(walkId(_, memo, order)).toArray
      val known = nodeIds.get(t)
      val id =
        if (known != null && sameSnapshot(known.intValue, t, kids)) known.intValue
        else newNode(t, kids, if (known == null) -1 else known.intValue)
      memo.put(t, id)
      order += id
      id
    }
  }

  def symId(sym: Symbol): Int =
    if (sym == null || (sym eq NoSymbol)) -1
    else {
      val known = symIds.get(sym)
      if (known != null) known.intValue
      else {
        val id = symById.length
        symIds.put(sym, id)
        symById += sym
        symFirst += ((runningId, if (stack.isEmpty) -1 else stack.last))
        id
      }
    }

  private def typeId(tp: Type): Any =
    if (tp == null) null
    else {
      val known = typeIds.get(tp)
      if (known != null) known.intValue
      else {
        val id = typeIds.size
        typeIds.put(tp, id)
        pendingTypes += tp
        id
      }
    }

  def unitIdOf(src: SourceFile): Int = if (src == null) -1 else unitOf.getOrElse(src, -1)
  private def unitOfPos(pos: Position): Int = if (pos == null || !pos.isDefined) -1 else unitIdOf(pos.source)

  private def safe(s: => String): String =
    try s catch { case ex: Throwable => s"<error: ${ex.getClass.getSimpleName}>" }

  /** At the end of `printPhase`, as `-Xprint:<phase>` shows it. */
  private def atEnd[T](printPhase: Phase)(op: => T): T =
    if (printPhase == null || (printPhase eq scala.reflect.internal.NoPhase)) op else exitingPhase(printPhase)(op)

  private def flushDefs(printPhase: Phase): Unit = quietly {
    var i = nodesWritten
    while (i < nodeKind.length) {
      val t = nodeTree(i)
      if (t != null) {
        val s = nodeSymObj(i)
        if (s != null) nodeSym(i) = symId(s)
        nodeName(i) = t match {
          case n: NameTree => safe(n.name.decoded)
          case Literal(c) => safe(c.escapedStringValue)
          case _ => null
        }
      }
      i += 1
    }
    writeSyms(printPhase)
    while (typesWritten < pendingTypes.length) {
      val tp = pendingTypes(typesWritten)
      val text = atEnd(printPhase)(safe(tp.toString))
      emit(obj("t" -> "type", "id" -> typesWritten, "text" -> text))
      typesWritten += 1
    }
    while (nodesWritten < nodeKind.length) {
      val n = nodesWritten
      val t = nodeTree(n)
      emit(obj("t" -> "node", "id" -> n, "kind" -> nodeKind(n), "span" -> spanJson(nodeSpan(n)),
        "unit" -> (if (t == null) null else { val u = unitOfPos(t.pos); if (u < 0) null else u }),
        "children" -> nodeChildren(n).toList, "sym" -> (if (nodeSym(n) < 0) null else nodeSym(n)), "name" -> nodeName(n)))
      nodesWritten += 1
    }
  }

  private def writeSyms(printPhase: Phase): Unit = {
    var i = 0
    while (i < symById.length) { writeSym(i, printPhase); i += 1 }
  }

  private def isLocal(sym: Symbol): Boolean = try currentRun.compiles(sym) catch { case _: Throwable => false }

  private def unitOfSym(sym: Symbol): Int =
    try {
      val f = sym.sourceFile
      if (f == null) -1 else unitOfFile.getOrElse(f.path, -1)
    } catch { case _: Throwable => -1 }

  private def writeSym(id: Int, printPhase: Phase): Unit = if (!symWritten.get(id)) {
    symWritten.set(id)
    val sym = symById(id)
    val owner = sym.owner
    val ownerId = if (owner != null && (owner ne NoSymbol) && !owner.isRoot) symId(owner) else -1
    if (ownerId >= 0) writeSym(ownerId, printPhase)
    val local = isLocal(sym)
    val (ph, sq) = symFirst(id)
    emit(obj("t" -> "sym", "id" -> id, "name" -> safe(sym.name.decoded), "owner" -> (if (ownerId < 0) null else ownerId),
      "fullName" -> safe(sym.fullName), "kind" -> (if (sym.isClass) "class" else if (sym.isType) "type" else "term"),
      "local" -> local, "unit" -> (if (local) { val u = unitOfSym(sym); if (u < 0) null else u } else null),
      "firstPhase" -> ph, "firstSeq" -> (if (sq < 0) null else sq)))
  }

  // ---- phases ---------------------------------------------------------------------------------

  private def isInit(phase: Phase): Boolean = phase.name == "<init phase>" // Global.InitPhase (private[nsc])

  private def allocSeq(): Int = { val s = nextSeq; nextSeq += 1; s }

  def phaseBegin(phase: Phase): Unit = if (!closed && !isInit(phase) && suspended == 0) {
    ensureStarted()
    running = phase
    copiedFrom = new IdentityHashMap
    typedFrom = new IdentityHashMap
    val name = jstr(phase.name)
    addEv(new Ev(allocSeq(), -1, "PhaseBegin", phase.id, -1, -1L, Array(), Nil, () => s"""{"name":$name,"members":[$name]}"""))
  }

  def phaseEnd(phase: Phase): Unit = if (!closed && !isInit(phase) && suspended == 0 && (running eq phase)) {
    val s = allocSeq()
    addEv(new Ev(s, -1, "PhaseEnd", phase.id, -1, -1L, Array(), Nil, () => "{}"))
    lastEnd = (phase, s)
    flush(phase)
    quietly {
      for (unit <- currentRun.units) recordUnit(phase, unit)
    }
    running = null
  }

  def runEnd(): Unit = close()

  def flush(printPhase: Phase = running): Unit = {
    ensureStarted()
    flushDefs(printPhase)
    val evs = evBuf.sortBy(_.seq)
    evBuf.clear()
    val lines = mutable.ArrayBuffer[String]()
    quietly {
      atEnd(printPhase) {
        for (e <- evs) {
          val syms = e.syms.map {
            case s: Symbol => symId(s)
            case t: Tree => symId(recSymOrSym(t))
            case _ => -1
          }.filter(_ >= 0)
          val payload = try e.payload() catch { case ex: Throwable => s"""{"error":${jstr(ex.getClass.getSimpleName)}}""" }
          val sb = new java.lang.StringBuilder(160)
          sb.append("""{"t":"ev","seq":""").append(e.seq)
            .append(""","parent":""").append(if (e.parent < 0) "null" else e.parent.toString)
            .append(""","kind":"""").append(e.kind)
            .append("""","phase":""").append(e.phase)
            .append(""","unit":""").append(if (e.unit < 0) "null" else e.unit.toString)
            .append(""","span":""").append(spanStr(e.span))
            .append(""","nodes":[""").append(e.nodes.map(n => if (n < 0) "null" else n.toString).mkString(","))
            .append("""],"syms":[""").append(syms.mkString(","))
            .append("""],"payload":""").append(payload).append('}')
          lines += sb.toString
        }
        val agg = mutable.LinkedHashMap[(String, Int, Int), (Int, Int)]()
        for ((kind, from, to0, seq) <- edgeBuf) {
          val to = to0 match {
            case s: Symbol => s
            case t: Tree => recSymOrSym(t)
            case _ => NoSymbol
          }
          if (from != null && (from ne NoSymbol) && to != null && (to ne NoSymbol) && !to.hasPackageFlag) {
            val f = safeTop(from)
            val t = safeTop(to)
            if ((f ne t) && (f ne NoSymbol) && (t ne NoSymbol) && !t.hasPackageFlag) {
              val key = (kind, symId(f), symId(t))
              agg(key) = agg.get(key) match {
                case Some((w, s0)) => (w + 1, s0)
                case None => (1, seq)
              }
            }
          }
        }
        edgeBuf.clear()
        for (((kind, f, t), (w, s)) <- agg)
          lines += s"""{"t":"edge","kind":"$kind","from":$f,"to":$t,"weight":$w,"ev":$s,"phase":${if (printPhase == null) "null" else printPhase.id}}"""
      }
    }
    flushDefs(printPhase)
    for (l <- lines) { out.write(l); out.write('\n') }
    out.flush()
  }

  private def safeTop(s: Symbol): Symbol = try s.enclosingTopLevelClass catch { case _: Throwable => NoSymbol }
  private def recSymOrSym(t: Tree): Symbol = {
    val s = try t.symbol catch { case _: Throwable => null }
    if (s == null) NoSymbol else s
  }

  // ---- event hooks ----------------------------------------------------------------------------

  private def addEv(e: Ev): Unit = {
    evBuf += e
    evCount(e.kind) = evCount.getOrElse(e.kind, 0) + 1
  }

  private def active: Boolean = suspended == 0 && withEvents && running != null && !closed

  private def enter(): Boolean =
    if (!active) { if (suspended > 0) perturbations += 1; false }
    else { stack += allocSeq(); true }

  private def exit(): Int = stack.remove(stack.length - 1)
  private def parent: Int = if (stack.isEmpty) -1 else stack.last

  // per typed call: (input id, typed at entry, typer); only for recorded calls
  private val typedStack = mutable.ArrayBuffer[(Int, Boolean, analyzer.Typer)]()
  private def currentOwner: Symbol =
    if (typedStack.isEmpty) NoSymbol else try typedStack.last._3.context.owner catch { case _: Throwable => NoSymbol }

  def typedEnter(typer: analyzer.Typer, tree: Tree, mode: Int, pt: Type): Unit =
    if (enter()) typedStack += ((nodeId(tree), tree.tpe ne null, typer))

  def typedExit(typer: analyzer.Typer, tree: Tree, mode: Int, pt: Type, res: Tree): Unit =
    if (typedStack.nonEmpty && stack.nonEmpty && active) {
      val seq = exit()
      val (in, wasTyped, _) = typedStack.remove(typedStack.length - 1)
      if (res != null && wasTyped && (res eq tree) && nextSeq == seq + 1) nextSeq = seq // already typed, nothing nested
      else {
        val retyped = runningId > typerId
        val outId = if (res == null) -1 else nodeId(res)
        if (res != null && (res ne tree)) typedFrom.put(res, tree)
        val ptId = typeId(pt)
        val tpeId = if (res == null) null else typeId(res.tpe)
        val payload =
          if (res == null) s"""{"pt":${js(ptId)},"aborted":true}"""
          else s"""{"pt":${js(ptId)},"tpe":${js(tpeId)},"result":"${res.productPrefix}"}"""
        val syms: List[AnyRef] = res match {
          case r: RefTree =>
            edgeBuf += (("resolves", currentOwner, r, seq))
            List(r)
          case r: MemberDef => List(r)
          case _ => Nil
        }
        addEv(new Ev(seq, parent, if (retyped) "Retyped" else "Typed", runningId, unitOfPos(tree.pos), spanOf(tree.pos),
          Array(in, outId), syms, () => payload))
      }
    }

  private val adaptStack = mutable.ArrayBuffer[Int]()
  def adaptEnter(typer: analyzer.Typer, tree: Tree, mode: Int, pt: Type): Unit =
    if (enter()) adaptStack += nodeId(tree)

  def adaptExit(typer: analyzer.Typer, tree: Tree, mode: Int, pt: Type, res: Tree): Unit =
    if (adaptStack.nonEmpty && stack.nonEmpty && active) {
      val seq = exit()
      val in = adaptStack.remove(adaptStack.length - 1)
      if ((res eq tree) && nextSeq == seq + 1) nextSeq = seq // a no-op adapt with nothing nested
      else {
        val outId = if (res == null) -1 else nodeId(res)
        val ptId = typeId(pt)
        val changed = res ne tree
        addEv(new Ev(seq, parent, "Adapt", runningId, unitOfPos(tree.pos), spanOf(tree.pos), Array(in, outId), Nil,
          () => s"""{"pt":${js(ptId)},"changed":$changed}"""))
      }
    }

  private val completeStack = mutable.ArrayBuffer[Boolean]()
  def completeEnter(sym: Symbol): Unit = completeStack += enter()
  def completeExit(sym: Symbol, ok: Boolean): Unit =
    if (completeStack.nonEmpty && completeStack.remove(completeStack.length - 1) && stack.nonEmpty) {
      val seq = exit()
      val owner = currentOwner
      edgeBuf += (("forces", owner, sym, seq))
      val o = symId(owner)
      symId(sym)
      addEv(new Ev(seq, parent, "Complete", runningId, unitOfPos(sym.pos), spanOf(sym.pos), Array(), List(sym),
        () => s"""{"ok":$ok,"forcedBy":${if (o < 0) "null" else o}}"""))
    }

  private def resultName(r: analyzer.SearchResult): String =
    if (r == null) "null"
    else if (r.isSuccess) "\"success\""
    else if (r.isAmbiguousFailure) "\"AmbiguousImplicits\""
    else if (r.isDivergent) "\"DivergentImplicit\""
    else jstr(r.getClass.getSimpleName.stripSuffix("$"))

  private val searchStack = mutable.ArrayBuffer[Boolean]()
  def implicitSearchEnter(search: analyzer.ImplicitSearch): Unit = searchStack += enter()
  def implicitSearchExit(search: analyzer.ImplicitSearch, res: analyzer.SearchResult): Unit =
    if (searchStack.nonEmpty && searchStack.remove(searchStack.length - 1) && stack.nonEmpty) {
      val seq = exit()
      val ok = res != null && res.isSuccess
      if (ok) edgeBuf += (("implicit", currentOwner, res.tree, seq))
      val arg = nodeIdOrNull(search.tree)
      val chosen = if (ok) nodeIdOrNull(res.tree) else -1
      val ptId = typeId(search.pt)
      val result = resultName(res)
      val pos = search.pos
      addEv(new Ev(seq, parent, "ImplicitSearch", runningId, unitOfPos(pos), spanOf(pos), Array(arg, chosen),
        if (ok) List(res.tree) else Nil, () => s"""{"pt":${js(ptId)},"result":$result,"view":${search.isView}}"""))
    }

  private val candStack = mutable.ArrayBuffer[Boolean]()
  def candidateEnter(search: analyzer.ImplicitSearch, info: analyzer.ImplicitInfo): Unit = candStack += enter()
  def candidateExit(search: analyzer.ImplicitSearch, info: analyzer.ImplicitInfo, res: analyzer.SearchResult): Unit =
    if (candStack.nonEmpty && candStack.remove(candStack.length - 1) && stack.nonEmpty) {
      val seq = exit()
      val ok = res != null && res.isSuccess
      val tree = if (ok) nodeIdOrNull(res.tree) else -1
      val reason = if (ok) "null" else if (res == null) "\"aborted\"" else resultName(res)
      val pos = search.pos
      addEv(new Ev(seq, parent, "Candidate", runningId, unitOfPos(pos), spanOf(pos), Array(tree), List(info.sym),
        () => s"""{"ref":${js(typeId(info.tpe))},"accepted":$ok,"reason":$reason,"name":${jstr(info.name.decoded)}}"""))
    }

  def report(reporter: AnyRef, pos: Position, msg: String, severity: Int): Unit =
    if (!active) { if (suspended > 0) perturbations += 1 }
    else {
      val cls = reporter.getClass.getSimpleName
      addEv(new Ev(allocSeq(), parent, "Report", runningId, unitOfPos(pos), spanOf(pos), Array(), Nil,
        () => s"""{"level":$severity,"msg":${jstr(msg)},"reporter":${jstr(cls)}}"""))
    }

  def bufferedReport(pos: Position, msg: String): Unit =
    if (!active) { if (suspended > 0) perturbations += 1 }
    else addEv(new Ev(allocSeq(), parent, "Report", runningId, unitOfPos(pos), spanOf(pos), Array(), Nil,
      () => s"""{"level":2,"msg":${jstr(msg)},"reporter":"BufferingReporter","buffered":true}"""))

  def copied(from: Tree, copy: Tree): Unit =
    if (suspended == 0 && (from ne copy)) copiedFrom.put(copy, from)

  // services for JvmRecorder
  private[nsc] def treeNodeId(t: Tree): Int = { val i = nodeIds.get(t); if (i == null) -1 else i.intValue }
  private[nsc] def copySource(t: Tree): Tree = copiedFrom.get(t)
  private[nsc] def lastTreeVersion(u: Int): (java.util.BitSet, Array[Int]) =
    prevVersion.get(u).map(v => (v._2, v._3)).getOrElse((new java.util.BitSet, Array[Int]()))
  private[nsc] def nodeKindOf(id: Int): String = nodeKind(id)
  private[nsc] def nodeSpanOf(id: Int): Long = nodeSpan(id)
  private[nsc] def nodeSymOf(id: Int): Int = nodeSym(id)
  private[nsc] def nodePrevOf(id: Int): Int = nodePrev(id)
  private[nsc] def symIdOf(sym: Symbol): Int = { val i = symIds.get(sym); if (i == null) -1 else i.intValue }
  private[nsc] def emitObj(fields: (String, Any)*): Unit = emit(obj(fields: _*))
  private[nsc] def countDerive(how: String, conf: String, n: Int): Unit =
    deriveCount((how, conf)) = deriveCount.getOrElse((how, conf), 0) + n
  private[nsc] def newExtNode(fields: (String, Any)*): Int = {
    assert(nodesWritten == nodeKind.length, "JVM nodes are written after every tree node")
    val id = nodeKind.length
    val kind = fields.collectFirst { case ("kind", k: String) => k }.get
    nodeTree += null; nodeKind += kind; nodeSpan += -1L; nodeChildren += Array[Int](); nodeSymObj += null; nodeSym += -1
    nodeName += null; nodeTpe += null; nodeTypeText += null; nodePrev += -1
    val get = fields.toMap
    val std = List("t" -> "node", "id" -> id, "ir" -> "jvm", "kind" -> kind, "span" -> null, "unit" -> get.getOrElse("unit", null),
      "children" -> get.getOrElse("children", Nil), "sym" -> get.getOrElse("sym", null), "name" -> get.getOrElse("name", null))
    val stdKeys = std.map(_._1).toSet
    emit(obj(std ++ fields.filterNot(f => stdKeys(f._1)): _*))
    nodesWritten += 1
    id
  }

  // ---- versions -------------------------------------------------------------------------------

  /** Record one unit's tree as output of `phase` (called from phaseEnd, events suspended). */
  def recordUnit(phase: Phase, unit: CompilationUnit): Unit = {
    val (endPhase, endSeq) = lastEnd
    if (endPhase ne phase) return
    val root = unit.body
    if (root == null) return
    val u = unitOf.getOrElse(unit.source, -1)
    if (u < 0) return
    val pid = phase.id
    val memo = new IdentityHashMap[Tree, Integer]
    val reachable = mutable.ArrayBuffer[Int]()
    val rootId = walkId(root, memo, reachable)
    val live = new java.util.BitSet
    reachable.foreach(live.set)
    var typeObjectsChanged = false
    val defSyms = mutable.ArrayBuffer[Symbol]()
    val defSeen = new IdentityHashMap[Symbol, Unit]
    for (id <- reachable) {
      val t = nodeTree(id)
      if (t.tpe ne nodeTpe(id)) typeObjectsChanged = true
      t match {
        case d: MemberDef =>
          val s = d.symbol
          if (s != null && (s ne NoSymbol) && !defSeen.containsKey(s)) { defSeen.put(s, ()); defSyms += s }
        case _ =>
      }
    }
    val prev = prevVersion.get(u)
    val fresh = reachable.filter(id => prev.forall(!_._2.get(id))).sorted

    // symbols defined in this unit's tree: cheap change test (info object at the end of the phase, flags, owner, name)
    var symsChanged = false
    val symLines = mutable.ArrayBuffer[Obj]()
    if (withSyms) atEnd(phase) {
      for (sym <- defSyms if isLocal(sym)) {
        symId(sym)
        val info: AnyRef = if (sym.isInitialized) (try sym.info catch { case _: Throwable => null }) else null
        val fp = (info, sym.flags, sym.owner, sym.name: Name)
        val last = symFp.get(sym)
        if (last == null || !(last._1 eq fp._1) || last._2 != fp._2 || !(last._3 eq fp._3) || !(last._4 eq fp._4)) {
          symFp.put(sym, fp)
          symsChanged = true
          val id = symId(sym)
          val owner = sym.owner
          val ownerId = if ((owner ne NoSymbol) && !owner.isRoot) symId(owner) else -1
          val infoText = if (info == null) "<lazy>" else safe(info.toString)
          val cur = (infoText, safe(sym.flagString), ownerId, safe(sym.name.decoded))
          if (!symLast.get(id).contains(cur)) {
            symLast(id) = cur
            symLines += obj("t" -> "syminfo", "sym" -> id, "fromPhase" -> pid, "name" -> cur._4, "info" -> cur._1,
              "flags" -> cur._2, "owner" -> (if (ownerId < 0) null else ownerId))
          }
        }
      }
    }

    // nsc's printer rebuilds initialized definitions from their symbols (`ClassDef(sym, impl)`), and the
    // factories' `atPos` gives position-less subtrees a position: undo that, as if nothing had printed.
    val noPos = reachable.iterator.map(nodeTree).filter(t => !t.pos.isDefined && t.canHaveAttrs).toArray
    def restorePositions(): Unit = for (t <- noPos if t.pos.isDefined) { t.setPos(NoPosition); positionsRestored += 1 }

    val skip = prev.isDefined && fresh.isEmpty && !typeObjectsChanged && !symsChanged
    val versionFields = mutable.ListBuffer[(String, Any)]("t" -> "version", "phase" -> pid, "unit" -> u, "seq" -> endSeq, "root" -> rootId)
    if (skip) {
      versionsSkipped += 1
      prevText.get(u) match {
        case Some((pp, _, _)) if withText => versionFields += "textSameAs" -> pp; versionFields += "offsetsSameAs" -> pp
        case _ =>
      }
    } else {
      versionsPrinted += 1
      if (withText) {
        val marked = atEnd(phase)(printMarked(root))
        restorePositions()
        val flat = Recorder.flatten(marked)
        if (check) { verify(phase, flat); restorePositions() }
        val offs = flat.offsets.grouped(3).toList.sortBy(t => (t(1), -t(2), -t(0))).map(_.toList)
        prevText.get(u) match {
          case Some((pp, t, _)) if t == flat.text => versionFields += "textSameAs" -> pp
          case _ => versionFields += "text" -> flat.text
        }
        prevText.get(u) match {
          case Some((pp, _, o)) if o == offs => versionFields += "offsetsSameAs" -> pp
          case _ => versionFields += "offsets" -> offs
        }
        prevText(u) = (pid, flat.text, offs)
      }
      val fullTypes = symsChanged || infoTransformerPhases(phase.name)
      val memoT = new IdentityHashMap[Type, String]
      val types = mutable.ArrayBuffer[List[Any]]()
      atEnd(phase) {
        for (id <- reachable) {
          val tp = nodeTree(id).tpe
          if (tp != null && (fullTypes || (tp ne nodeTpe(id)))) {
            nodeTpe(id) = tp
            var text = memoT.get(tp)
            if (text == null) { text = safe(tp.toString); memoT.put(tp, text) }
            if (text != nodeTypeText(id)) { nodeTypeText(id) = text; types += List(id, text) }
          }
        }
      }
      if (types.nonEmpty) versionFields += "types" -> types
    }
    versionFields += "nodes" -> reachable.length
    versionFields += "new" -> fresh.length

    flushDefs(phase)
    val deriveLines = if (withDerive && prev.isDefined) derive(pid, u, fresh, live, prev.get, phase.id <= typerId) else Nil
    emit(obj(versionFields.toList: _*))
    symLines.foreach(emit)
    deriveLines.foreach(emit)
    prevVersion(u) = (pid, live, reachable.toArray)
  }

  /** Lineage of this version's new nodes relative to the previous one. Exact sources: the same tree's
   *  earlier snapshot (in-place typing or retyping), the copier hook (`copyAttrs`), and `Typed` events
   *  (result -> input); the rest by span, as on Scala 3.
   */
  private def derive(pid: Int, u: Int, fresh: collection.Seq[Int], live: java.util.BitSet,
      prev: (Int, java.util.BitSet, Array[Int]), typing: Boolean): List[Obj] = {
    val (_, prevLive, prevReach) = prev
    val src = Array.fill(nodeKind.length)(-1)
    val how = mutable.HashMap[Int, String]()
    val exact = new java.util.BitSet

    // an id of tree `t` that the previous version had: its latest snapshot or an earlier one
    def prevIdOfTree(t: Tree): Int = {
      val k = nodeIds.get(t)
      var p = if (k == null) -1 else k.intValue
      while (p >= 0 && !prevLive.get(p)) p = nodePrev(p)
      p
    }
    def exactSource(n: Int): (Int, String) = {
      var p = nodePrev(n)
      while (p >= 0 && !prevLive.get(p)) p = nodePrev(p)
      if (p >= 0) return (p, if (typing) "rewrite" else "retype")
      var t = nodeTree(n)
      var steps = 0
      while (steps < 1000) {
        var s = copiedFrom.get(t)
        if (s == null) s = typedFrom.get(t)
        if (s == null) return (-1, "rewrite")
        val sid = prevIdOfTree(s)
        if (sid >= 0) return (sid, "rewrite")
        t = s
        steps += 1
      }
      (-1, "rewrite")
    }

    val bySpan = mutable.HashMap[Long, mutable.ArrayBuffer[Int]]()
    for (o <- prevReach if nodeSpan(o) >= 0) bySpan.getOrElseUpdate(nodeSpan(o), mutable.ArrayBuffer()) += o
    val synth = mutable.ArrayBuffer[Int]()
    for (n <- fresh) {
      val (o, h) = exactSource(n)
      if (o >= 0) { src(n) = o; how(n) = h; exact.set(n) }
      else {
        val sp = nodeSpan(n)
        bySpan.get(if (sp < 0) Long.MinValue else sp) match {
          case Some(cands) =>
            def rank(o: Int) = (if (live.get(o)) 0 else 2) + (if (nodeKind(o) == nodeKind(n)) 1 else 0)
            src(n) = cands.maxBy(o => (rank(o), o))
            how(n) = "rewrite"
          case None => synth += n
        }
      }
    }

    val pure = new java.util.BitSet
    for (n <- fresh if src(n) >= 0) {
      val o = src(n)
      val nk = nodeChildren(n); val ok = nodeChildren(o)
      if (nodeKind(n) == nodeKind(o) && nodeName(n) == nodeName(o) && nodeSym(n) == nodeSym(o)
          && nodeSpan(n) == nodeSpan(o) && nodeTypeText(n) == nodeTypeText(o) && nk.length == ok.length
          && nk.indices.forall(i => nk(i) == ok(i) || pure.get(nk(i)) && src(nk(i)) == ok(i)))
        pure.set(n)
    }

    val lines = mutable.ArrayBuffer[Obj]()
    def rec(h: String, conf: String, news: collection.Seq[Int], olds: collection.Seq[Int]): Unit = {
      deriveCount((h, conf)) = deriveCount.getOrElse((h, conf), 0) + news.length.max(olds.length)
      lines += obj("t" -> "derive", "phase" -> pid, "unit" -> u, "new" -> news.toList, "old" -> olds.toList,
        "how" -> h, "confidence" -> conf)
    }
    val usedOld = new java.util.BitSet
    val groups = mutable.LinkedHashMap[(Int, Boolean, String), mutable.ArrayBuffer[Int]]()
    for (n <- fresh if src(n) >= 0) {
      usedOld.set(src(n))
      val conf = if (exact.get(n)) "exact" else "heuristic"
      if (pure.get(n)) rec("copy", conf, List(n), List(src(n)))
      else groups.getOrElseUpdate((src(n), exact.get(n), how(n)), mutable.ArrayBuffer()) += n
    }
    for (((o, ex, h), ns) <- groups)
      rec(if (ns.length > 1) "split" else h, if (ex) "exact" else "heuristic", ns, List(o))
    if (synth.nonEmpty) rec("synth", "heuristic", synth, Nil)
    val elided = prevReach.filter(o => !live.get(o) && !usedOld.get(o))
    if (elided.nonEmpty) rec("elide", "heuristic", Nil, elided.toSeq)
    lines.toList
  }

  // ---- printing -------------------------------------------------------------------------------

  /** `-Xprint`'s printer, with each known tree's text between `ESC[1<id>m` and `ESC[2<id>m`. nsc's `print`
   *  replaces an initialized definition by one rebuilt from its symbol before calling `printTree`, so the
   *  markers go around `print` for the original tree, and `printTree` marks trees printed directly.
   */
  @nowarn("cat=deprecation")
  private final class MarkingPrinter(pw: PrintWriter) extends global.TreePrinter(pw) {
    private var current: Tree = null
    private def idOf(t: Tree): Int = { val i = nodeIds.get(t); if (i == null) -1 else i.intValue }
    override def print(args: Any*): Unit = args.foreach {
      case t: Tree =>
        val id = idOf(t)
        if (id < 0) super.print(t)
        else {
          super.print(s"\u001b[1${id}m")
          val saved = current
          current = t
          try super.print(t) finally current = saved
          super.print(s"\u001b[2${id}m")
        }
      case a => super.print(a)
    }
    override def printTree(t: Tree): Unit =
      if (t eq current) { current = null; super.printTree(t) }
      else {
        val id = idOf(t)
        if (id < 0) super.printTree(t)
        else {
          super.print(s"\u001b[1${id}m")
          val saved = current
          current = null
          try super.printTree(t) finally current = saved
          super.print(s"\u001b[2${id}m")
        }
      }
  }

  private def printMarked(root: Tree): String = {
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    val p = new MarkingPrinter(pw)
    p.print(root)
    pw.flush()
    sw.toString
  }

  /** `-P:replay-record:check`: every slice must equal the node's own `-Xprint` text modulo whitespace. */
  private def verify(phase: Phase, flat: Recorder.Flat): Unit = {
    def norm(s: String) = s.replaceAll("[()\\s]", "")
    var k = 0
    while (k < flat.offsets.length) {
      val id = flat.offsets(k)
      val slice = flat.text.substring(flat.offsets(k + 1), flat.offsets(k + 2))
      offsetChecks += 1
      val own = atEnd(phase)(safe(nodeTree(id).toString))
      if (norm(slice) != norm(own) && !norm(own).contains(norm(slice))) {
        offsetFailures += 1
        if (offsetFailures <= 5)
          System.err.println(s"[replay] offset mismatch node $id ${nodeKind(id)}:\n  slice=${slice.take(300)}\n  own  =${own.take(300)}")
      }
      k += 3
    }
  }

  // ---- minimal JSON ----------------------------------------------------------------------------

  private[nsc] def obj(fields: (String, Any)*): Obj = new Obj(fields)

  private def js(x: Any): String = if (x == null) "null" else x.toString

  private def emit(o: Obj): Unit = {
    val sb = new java.lang.StringBuilder(128)
    write(sb, o)
    sb.append('\n')
    out.write(sb.toString)
  }

  private[nsc] def jstr(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 2)
    str(sb, s)
    sb.toString
  }

  private def write(sb: java.lang.StringBuilder, x: Any): Unit = x match {
    case null => sb.append("null")
    case s: String => str(sb, s)
    case i: Int => sb.append(i)
    case i: Integer => sb.append(i.intValue)
    case b: Boolean => sb.append(b)
    case o: Obj =>
      sb.append('{')
      var first = true
      for ((k, v) <- o.fields) {
        if (!first) sb.append(',')
        first = false
        str(sb, k); sb.append(':'); write(sb, v)
      }
      sb.append('}')
    case xs: Iterable[_] =>
      sb.append('[')
      var first = true
      for (v <- xs) {
        if (!first) sb.append(',')
        first = false
        write(sb, v)
      }
      sb.append(']')
    case other => str(sb, other.toString)
  }

  private def str(sb: java.lang.StringBuilder, s: String): Unit = {
    sb.append('"')
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c => sb.append(c)
      }
      i += 1
    }
    sb.append('"')
  }
}

object Recorder {
  final class Obj(val fields: Seq[(String, Any)])
  final class Flat(val text: String, val offsets: Array[Int]) // id, start, end triples

  /** Strip the markers, returning the plain text and [id, start, end) triples in UTF-16 code units.
   *  (nsc's printer streams text with no layout pass, so unlike Scala 3's there is nothing to undo.)
   */
  def flatten(marked: String): Flat = {
    val sb = new java.lang.StringBuilder(marked.length)
    val offs = mutable.ArrayBuffer[Int]()
    val stack = mutable.Stack[(Int, Int)]()
    var i = 0
    val n = marked.length
    while (i < n) {
      val c = marked.charAt(i)
      if (c == '\u001b' && i + 2 < n && marked.charAt(i + 1) == '[') {
        var j = i + 2
        while (marked.charAt(j) != 'm') j += 1
        val kind = marked.charAt(i + 2)
        val id = marked.substring(i + 3, j).toInt
        if (kind == '1') stack.push((id, sb.length))
        else {
          val (id0, start) = stack.pop()
          assert(id0 == id, s"marker mismatch $id0 vs $id")
          offs += id; offs += start; offs += sb.length
        }
        i = j + 1
      } else {
        sb.append(c)
        i += 1
      }
    }
    assert(stack.isEmpty)
    new Flat(sb.toString, offs.toArray)
  }
}
