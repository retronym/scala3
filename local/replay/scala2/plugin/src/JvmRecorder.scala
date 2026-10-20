// JVM bytecode versions for the nsc recorder: schema v1 `ir:"jvm"` (see ../../SCHEMA-v1.md).
// LLM-assisted (Claude), see LLM_POLICY.md. Prototype; lives in local/, not for upstream.
// A Scala 2 port of ../../plugin/src/JvmRecorder.scala: nsc's GenBCode is what dotc's backend was ported from.
package replay.nsc

import java.util.IdentityHashMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.tree._

/** Collects backend observations during the `jvm` phase and writes them, after the tree versions, when the
 *  recorder closes. Requires `-Ybackend-parallelism:1` (nsc's default): the post-processor hooks then run
 *  on the compiler thread.
 */
final class JvmRecorder(val rec: Recorder) {
  val global: rec.global.type = rec.global
  import global._

  /** Where a range of instructions came from. */
  sealed trait Origin
  final case class Codegen(trees: mutable.ArrayBuffer[Tree]) extends Origin
  /** Copied by the inliner from `callee` (`range`: the callee range copied, null for the whole body). */
  final case class Inlined(calleeOwner: String, callee: MethodNode, range: RangeDef) extends Origin

  /** An instruction range, by the identity of its instructions (they survive optimiser rewrites). */
  final class RangeDef(val members: Array[AbstractInsnNode], val origin: Origin)
  final class RState(val range: RangeDef, val s: Int, val e: Int) { var id = -1 }
  final class MState(val key: JKey, val text: MethodText, val ranges: Array[RState], val prev: MState) { var id = -1 }
  final class InlineRec(val calleeOwner: String, val callee: MethodNode, val map: Map[AbstractInsnNode, AbstractInsnNode])

  final class JMethod(val mn: MethodNode) {
    var dd: DefDef = null
    val raw = mutable.ArrayBuffer[(Tree, AbstractInsnNode, AbstractInsnNode)]()
    val states = mutable.ArrayBuffer[MState]()
    def cur: MState = states.last
    var round = 0
    val inlines = mutable.ArrayBuffer[InlineRec]()
    val real0 = new java.util.BitSet
    var emitted = -1
  }

  final class JClass(val cn: ClassNode, val unit: Int, val classDef: ClassDef, val mirrorOf: JClass) {
    var ct0: ClassText = null
    var ctFinal: ClassText = null
    var methods: Array[JMethod] = Array()
    var fieldIds: Array[Int] = Array()
    var classId = -1
  }

  private val classes = mutable.ArrayBuffer[JClass]()
  private val classOf = new IdentityHashMap[ClassNode, JClass]
  private val methodOf = new IdentityHashMap[MethodNode, JMethod]
  private val stack = mutable.ArrayBuffer[(Tree, AbstractInsnNode)]()
  private var globalPending = false
  var nanos = 0L
  private def timed[T](op: => T): T = {
    val t0 = System.nanoTime
    try op finally nanos += System.nanoTime - t0
  }
  var unknownTrees, methodsTotal, methodsWithAncestor, insnsTotal, insnsCovered, rangesTotal, rangesWithAncestor,
    textChars, statesTotal, inlineRecs, classfileChanged, passCalls = 0

  private def globalOptimizer: Boolean = settings.optInlinerEnabled || settings.optClosureInvocations

  private def jmethod(mn: MethodNode): JMethod = {
    var jm = methodOf.get(mn)
    if (jm == null) {
      jm = new JMethod(mn)
      methodOf.put(mn, jm)
    }
    jm
  }

  def genMethod(dd: DefDef, mn: MethodNode): Unit = jmethod(mn).dd = dd

  def genLoadEnter(tree: Tree, mn: MethodNode): Unit = stack += ((tree, mn.instructions.getLast))

  def genLoadExit(tree: Tree, mn: MethodNode): Unit = if (stack.nonEmpty) {
    val (t, prevLast) = stack.remove(stack.length - 1)
    if ((t eq tree) && mn != null) {
      val start = if (prevLast == null) mn.instructions.getFirst else prevLast.getNext
      val end = mn.instructions.getLast
      if (start != null && end != null && (prevLast ne end)) jmethod(mn).raw += ((tree, start, end))
    }
  }

  def classNode(cd: ClassDef, cn: ClassNode): Unit = timed {
    globalPending = globalOptimizer
    addClass(cn, rec.unitIdOf(cd.pos.source), cd, null)
  }

  def mirrorNode(moduleClass: Symbol, cn: ClassNode): Unit = timed {
    classes.reverseIterator.find(c => c.mirrorOf == null && (c.classDef.symbol eq moduleClass)) match {
      case Some(main) => addClass(cn, main.unit, main.classDef, main)
      case None =>
    }
  }

  private def addClass(cn: ClassNode, u: Int, cd: ClassDef, mirrorOf: JClass): JClass = {
    val c = new JClass(cn, u, cd, mirrorOf)
    classes += c
    classOf.put(cn, c)
    c.ct0 = JvmText.cls(cn)
    c.methods = cn.methods.asScala.toArray.map(jmethod)
    for ((jm, k) <- c.methods.zipWithIndex) {
      val insns = jm.mn.instructions.toArray
      val idx = new IdentityHashMap[AbstractInsnNode, Integer]
      for ((n, i) <- insns.zipWithIndex) {
        idx.put(n, i)
        if (n.getOpcode >= 0) jm.real0.set(i)
      }
      val byExtent = mutable.LinkedHashMap[(Int, Int), RangeDef]()
      for ((tree, first, last) <- jm.raw) {
        val s = idx.get(first); val e = idx.get(last)
        if (s != null && e != null && s.intValue <= e.intValue) {
          val key = (s.intValue, e.intValue + 1)
          byExtent.getOrElseUpdate(key, new RangeDef(insns.slice(key._1, key._2), Codegen(mutable.ArrayBuffer())))
            .origin.asInstanceOf[Codegen].trees += tree
        }
      }
      jm.raw.clear()
      val ranges = byExtent.toArray.sortBy { case (k, _) => (k._1, -k._2) }.map { case (k, r) => new RState(r, k._1, k._2) }
      jm.states += new MState(JKey.Gen, c.ct0.bodies(k), ranges, null)
    }
    c
  }

  /** `Inliner.inlineCallsite(callsite)` returned `map` (callee instruction -> its copy in the callsite method). */
  def inlined(callsite: AnyRef, map: Map[AbstractInsnNode, AbstractInsnNode]): Unit = {
    val cs = callsite.asInstanceOf[scala.tools.nsc.backend.jvm.opt.CallGraph#Callsite]
    val jm = methodOf.get(cs.callsiteMethod)
    if (jm != null) cs.callee match {
      case Right(c) =>
        jm.inlines += new InlineRec(c.calleeDeclarationClass.internalName, c.callee, map)
        inlineRecs += 1
      case _ =>
    }
  }

  /** The first backend hook after the global optimiser (inliner, closure optimiser) ran over all classes. */
  private def snapshotGlobal(): Unit = {
    globalPending = false
    for (c <- classes; jm <- c.methods) {
      val extras = mutable.ArrayBuffer[RangeDef]()
      for (r <- jm.inlines) {
        extras += new RangeDef(r.map.valuesIterator.toArray, Inlined(r.calleeOwner, r.callee, null))
        val callee = methodOf.get(r.callee)
        if (callee != null)
          for (rs <- callee.states.head.ranges) {
            val clones = rs.range.members.flatMap(r.map.get)
            if (clones.nonEmpty) extras += new RangeDef(clones, Inlined(r.calleeOwner, r.callee, rs.range))
          }
      }
      maybeState(jm, JKey.Inline, JvmText.method(jm.mn), extras)
    }
  }

  /** The pass method the agent hooks -> the name `traceIfChanged` would print. */
  private val passName = Map(
    "nullnessOptimizations" -> "nullness", "removeUnreachableCodeImpl" -> "dce", "boxUnboxElimination" -> "boxUnbox",
    "copyPropagation" -> "copyProp", "eliminateStaleStoresAndRewriteSomeIntrinsics" -> "staleStores",
    "eliminateRedundantCastsAndRewriteSomeIntrinsics" -> "redundantCasts", "eliminatePushPop" -> "pushPop",
    "eliminateStoreLoad" -> "storeLoadPairs", "removeEmptyExceptionHandlers" -> "staleHandlers",
    "simplifyJumps" -> "simplifyJumps", "compactLocalVariables" -> "localVariables",
    "removeUnusedLocalVariableNodes" -> "localVariables", "removeEmptyLineNumbers" -> "lineNumbers")

  def optimizerPass(owner: String, mn: MethodNode, method: String): Unit = timed {
    passCalls += 1
    val pass = passName.getOrElse(method, method)
    if (globalPending) snapshotGlobal()
    val jm = methodOf.get(mn)
    if (jm != null) {
      if (pass == "beforeMethodOpt") jm.round += 1
      else maybeState(jm, JKey.local(jm.round, pass), JvmText.method(mn), Nil)
    }
  }

  def classWritten(cn: ClassNode): Unit = timed {
    if (globalPending) snapshotGlobal()
    val c = classOf.get(cn)
    if (c != null) {
      val ct = JvmText.cls(cn)
      if (ct.bodies.length == c.methods.length && c.methods.indices.forall(k => cn.methods.get(k) eq c.methods(k).mn)) {
        for (k <- c.methods.indices) maybeState(c.methods(k), JKey.Classfile, ct.bodies(k), Nil)
        if (!(ct.parts sameElements c.ct0.parts) || c.methods.exists(_.cur.key == JKey.Classfile)) {
          c.ctFinal = ct
          classfileChanged += 1
        }
      }
    }
  }

  private def maybeState(jm: JMethod, key: JKey, mt: MethodText, extras: collection.Seq[RangeDef]): Unit =
    if (mt.text != jm.cur.text.text || extras.nonEmpty) {
      val idx = new IdentityHashMap[AbstractInsnNode, Integer]
      var i = 0
      val it = jm.mn.instructions.iterator
      while (it.hasNext) { idx.put(it.next(), i); i += 1 }
      def place(r: RangeDef): Option[RState] = {
        var lo = Int.MaxValue; var hi = -1
        for (n <- r.members) {
          val j = idx.get(n)
          if (j != null) { lo = lo.min(j.intValue); hi = hi.max(j.intValue) }
        }
        if (hi < 0) None else Some(new RState(r, lo, hi + 1))
      }
      val placed = (jm.cur.ranges.iterator.map(_.range) ++ extras).flatMap(place).toArray
      jm.states += new MState(key, mt, placed.sortBy(r => (r.s, -r.e)), jm.cur)
      statesTotal += 1
    }

  // ---- emission -------------------------------------------------------------------------------

  def emit(genPhase: Int, seq: Int, firstFreePhaseId: Int): Unit = timed {
    if (classes.nonEmpty) emit0(genPhase, seq, firstFreePhaseId)
  }

  private def emit0(genPhase: Int, seq: Int, firstFreePhaseId: Int): Unit = {
    val keys = (classes.iterator.flatMap(_.methods.iterator.flatMap(_.states.iterator.map(_.key)))
      ++ (if (classes.exists(_.ctFinal != null)) Iterator(JKey.Classfile) else Iterator.empty)).toSet.toList.sorted
    var nextPhase = firstFreePhaseId
    val resolver = new TreeResolver
    for (key <- keys) {
      val pid =
        if (key == JKey.Gen) genPhase
        else {
          val p = nextPhase
          nextPhase += 1
          rec.emitObj("t" -> "phase", "id" -> p, "name" -> key.name, "ir" -> "jvm", "mini" -> false, "group" -> p,
            "megaOf" -> null, "denotTransformer" -> false)
          p
        }
      for (c <- classes) emitClass(c, key, pid, seq, resolver)
    }
  }

  private type Der = (String, String, Seq[Int], Seq[Int], (String, Any))

  private def emitClass(c: JClass, key: JKey, pid: Int, seq: Int, resolver: TreeResolver): Unit = {
    val gen = key == JKey.Gen
    val changed = c.methods.filter(jm => jm.emitted + 1 < jm.states.length && jm.states(jm.emitted + 1).key == key)
    val ct = if (key == JKey.Classfile && c.ctFinal != null) c.ctFinal else c.ct0
    if (!gen && changed.isEmpty && !(key == JKey.Classfile && c.ctFinal != null)) return
    val u = c.unit
    val derives = mutable.ArrayBuffer[Der]()
    var newCount = 0
    def node(fields: (String, Any)*): Int = {
      newCount += 1
      rec.newExtNode(fields: _*)
    }

    if (gen)
      c.fieldIds = c.cn.fields.asScala.toArray.map(f =>
        node("kind" -> "FieldNode", "unit" -> u, "name" -> f.name, "desc" -> f.desc, "sym" -> resolver.fieldSym(c, f.name)))
    for (jm <- changed) {
      val st = jm.states(jm.emitted + 1)
      jm.emitted += 1
      st.id = node("kind" -> "MethodNode", "unit" -> u, "name" -> jm.mn.name, "desc" -> jm.mn.desc,
        "insns" -> st.text.insns, "sym" -> resolver.methodSym(c, jm))
      for (r <- st.ranges)
        r.id = node("kind" -> "InsnRange", "unit" -> u, "method" -> st.id, "range" -> List(r.s, r.e))
      val prev = st.prev
      if (prev != null) {
        derives += (("rewrite", "exact", List(st.id), List(prev.id), null))
        val prevOf = prev.ranges.iterator.map(r => r.range -> r).toMap
        for (r <- st.ranges)
          prevOf.get(r.range) match {
            case Some(o) =>
              val same = st.text.text.substring(st.text.insnOff(r.s), st.text.insnOff(r.e)) ==
                prev.text.text.substring(prev.text.insnOff(o.s), prev.text.insnOff(o.e))
              derives += ((if (same) "copy" else "rewrite", "exact", List(r.id), List(o.id), null))
            case None => r.range.origin match {
              case Inlined(owner, callee, cr) =>
                val calleeM = methodOf.get(callee)
                val old: List[Int] =
                  if (calleeM == null) Nil
                  else if (cr == null) List(calleeM.states.head.id).filter(_ >= 0)
                  else calleeM.states.head.ranges.find(_.range eq cr).map(_.id).toList.filter(_ >= 0)
                derives += (("inlined-from", "exact", List(r.id), old, "callee" -> s"$owner.${callee.name}${callee.desc}"))
              case _ =>
            }
          }
        val kept = st.ranges.iterator.map(_.range).toSet
        val gone = prev.ranges.filter(r => !kept(r.range)).map(_.id)
        if (gone.nonEmpty) derives += (("elide", "exact", Nil, gone.toList, null))
      }
    }
    val methodIds = c.methods.map(m => m.states(m.emitted).id)
    val prevClassId = c.classId
    c.classId = node("kind" -> "ClassNode", "unit" -> u, "name" -> c.cn.name, "children" -> (c.fieldIds.toList ++ methodIds),
      "sym" -> resolver.symId(c.classDef.symbol), "mirror" -> (c.mirrorOf != null))
    if (gen) codegenDerives(c, resolver, derives)
    else derives += (("rewrite", "exact", List(c.classId), List(prevClassId), null))

    val sb = new java.lang.StringBuilder
    val offs = mutable.ArrayBuffer[List[Int]]()
    def skipNl(t: String, i: Int) = if (i < t.length && t.charAt(i) == '\n') i + 1 else i
    for (((s, e), id) <- ct.fieldOff.zip(c.fieldIds)) offs += List(id, skipNl(ct.parts(0), s), e)
    for (k <- c.methods.indices) {
      sb.append(ct.parts(k))
      val hdr = skipNl(ct.parts(k), ct.hdrStart(k)) + sb.length - ct.parts(k).length
      val st = c.methods(k).states(c.methods(k).emitted)
      val base = sb.length
      sb.append(st.text.text)
      offs += List(st.id, hdr, sb.length)
      for (r <- st.ranges) offs += List(r.id, base + st.text.insnOff(r.s), base + st.text.insnOff(r.e))
    }
    sb.append(ct.parts.last)
    offs += List(c.classId, 0, sb.length)
    textChars += sb.length
    val sorted = offs.sortBy(t => (t(1), -t(2), t(0)))
    val nodes = 1 + c.fieldIds.length + c.methods.map(m => 1 + m.states(m.emitted).ranges.length).sum
    rec.emitObj("t" -> "version", "ir" -> "jvm", "phase" -> pid, "unit" -> u, "seq" -> seq, "class" -> c.cn.name,
      "root" -> c.classId, "text" -> sb.toString, "offsets" -> sorted, "nodes" -> nodes, "new" -> newCount)
    for ((how, conf, news, olds, extra) <- derives) {
      val fields = mutable.ListBuffer[(String, Any)]("t" -> "derive", "ir" -> "jvm", "phase" -> pid, "unit" -> u,
        "new" -> news.toList, "old" -> olds.toList, "how" -> how, "confidence" -> conf)
      if (extra != null) fields += extra
      rec.emitObj(fields.toList: _*)
      rec.countDerive(how, conf, news.length.max(olds.length))
    }
  }

  /** genBCode lineage: tree node -> ClassNode / FieldNode / MethodNode / InsnRange. */
  private def codegenDerives(c: JClass, resolver: TreeResolver, out: mutable.ArrayBuffer[Der]): Unit = {
    val u = c.unit
    def add(news: Seq[Int], anc: (Int, Boolean)): Unit =
      out += (("codegen", if (anc._2) "exact" else "heuristic", news, List(anc._1), null))
    val td = resolver.resolve(c.classDef, u)
    if (td._1 >= 0) add(List(c.classId), td)
    for ((f, id) <- c.cn.fields.asScala.zip(c.fieldIds)) {
      val a = resolver.field(c, f.name)
      if (a._1 >= 0) add(List(id), a)
      else if (f.name == "MODULE$" && td._1 >= 0) add(List(id), td)
    }
    for (jm <- c.methods) {
      val st = jm.states.head
      methodsTotal += 1
      val ma = resolver.method(c, jm)
      if (ma._1 >= 0) { methodsWithAncestor += 1; add(List(st.id), ma) }
      val covered = new java.util.BitSet
      val byAnc = mutable.LinkedHashMap[(Int, Boolean), mutable.ArrayBuffer[Int]]()
      for (r <- st.ranges) {
        rangesTotal += 1
        var any = false
        for (t <- r.range.origin.asInstanceOf[Codegen].trees) {
          val a = resolver.resolve(t, u)
          if (a._1 >= 0) {
            any = true
            val ids = byAnc.getOrElseUpdate(a, mutable.ArrayBuffer())
            if (!ids.contains(r.id)) ids += r.id
          } else unknownTrees += 1
        }
        if (any) {
          rangesWithAncestor += 1
          covered.set(r.s, r.e)
        }
      }
      for ((a, ids) <- byAnc) add(ids.toList, a)
      insnsTotal += jm.real0.cardinality
      covered.and(jm.real0)
      insnsCovered += covered.cardinality
    }
  }

  def stats: List[(String, Any)] = List(
    "classes" -> classes.length, "methods" -> methodsTotal, "methodsWithAncestor" -> methodsWithAncestor,
    "insns" -> insnsTotal, "insnsCovered" -> insnsCovered, "ranges" -> rangesTotal,
    "rangesWithAncestor" -> rangesWithAncestor, "unknownTrees" -> unknownTrees, "textChars" -> textChars,
    "optStates" -> statesTotal, "inlined" -> inlineRecs, "classfileChanged" -> classfileChanged, "passCalls" -> passCalls)

  private def javaName(sym: Symbol): String = try sym.javaSimpleName.toString catch { case _: Throwable => sym.name.toString }

  /** Finds the tree node (in the unit's last tree version) a backend tree comes from. */
  private final class TreeResolver {
    private val perUnit = mutable.HashMap[Int, (java.util.BitSet, mutable.HashMap[Int, Int], mutable.HashMap[(Long, String), Int])]()
    private def index(u: Int) = perUnit.getOrElseUpdate(u, {
      val (live, reach) = rec.lastTreeVersion(u)
      val bySym = mutable.HashMap[Int, Int]()
      val bySpan = mutable.HashMap[(Long, String), Int]()
      for (id <- reach) {
        val k = rec.nodeKindOf(id)
        if ((k == "DefDef" || k == "ValDef" || k == "ClassDef") && rec.nodeSymOf(id) >= 0) bySym(rec.nodeSymOf(id)) = id
        val sp = rec.nodeSpanOf(id)
        if (sp >= 0) bySpan((sp, k)) = bySpan.getOrElse((sp, k), -1).max(id)
      }
      (live, bySym, bySpan)
    })
    def symId(sym: Symbol): Any = { val i = rec.symIdOf(sym); if (i < 0) null else i }

    /** (node id, exact) or (-1, _): the arena node itself (or an earlier snapshot of the same tree), via the
     *  copier hook, by symbol, or by span. */
    def resolve(t0: Tree, u: Int): (Int, Boolean) = {
      val (live, bySym, bySpan) = index(u)
      var t: Tree = t0
      var steps = 0
      while (t != null && steps < 100) {
        var id = rec.treeNodeId(t)
        while (id >= 0 && !live.get(id)) id = rec.nodePrevOf(id)
        if (id >= 0) return (id, true)
        t = rec.copySource(t)
        steps += 1
      }
      t0 match {
        case md: MemberDef if md.symbol != null && (md.symbol ne NoSymbol) =>
          bySym.get(rec.symIdOf(md.symbol)) match {
            case Some(id) => return (id, true)
            case None =>
          }
        case _ =>
      }
      val sp = rec.spanOf(t0.pos)
      if (sp >= 0) bySpan.get((sp, t0.productPrefix)) match {
        case Some(id) => return (id, false)
        case None =>
      }
      (-1, false)
    }

    def method(c: JClass, jm: JMethod): (Int, Boolean) =
      forwardee(c, jm) match {
        case Some((c2, m2)) => method(c2, m2)
        case None if jm.dd == null => inheritedForwardee(c, jm)
        case None =>
          val r = resolve(jm.dd, c.unit)
          if (r._1 >= 0 && !(jm.mn.name == "<clinit>" && !r._2)) r
          else if (jm.mn.name == "<clinit>") (resolve(c.classDef, c.unit)._1, false)
          else
            // trait static impl methods (`m$`) and `$init$` get fresh symbols: match by name
            memberDefs(c).collectFirst {
              case d: DefDef if { val n = javaName(d.symbol); n == jm.mn.name || n + "$" == jm.mn.name } => d
            }.map(d => (resolve(d, c.unit)._1, false)).getOrElse((-1, false))
      }

    private def inheritedForwardee(c: JClass, jm: JMethod): (Int, Boolean) =
      moduleOf(c) match {
        case null => (-1, false)
        case module =>
          val alts = try module.classDef.symbol.info.member(TermName(jm.mn.name)).alternatives catch { case _: Throwable => Nil }
          val found = alts.iterator.flatMap(d => perUnitAll.flatMap(u => index(u)._2.get(rec.symIdOf(d)))).toList
          found match {
            case id :: _ => (id, alts.length == 1)
            case Nil => (-1, false)
          }
      }

    private def perUnitAll: Seq[Int] = classes.iterator.map(_.unit).toSeq.distinct

    private def moduleOf(c: JClass): JClass =
      if (c.mirrorOf != null) c.mirrorOf
      else {
        val mc = try c.classDef.symbol.companionModule.moduleClass catch { case _: Throwable => NoSymbol }
        if (mc eq NoSymbol) null
        else classes.find(c2 => c2.mirrorOf == null && (c2 ne c) && (c2.classDef.symbol eq mc)).orNull
      }

    private def forwardee(c: JClass, jm: JMethod): Option[(JClass, JMethod)] =
      if (jm.dd != null) None
      else {
        val module = moduleOf(c)
        if (module == null) None
        else module.methods.find(m => m.mn.name == jm.mn.name && m.mn.desc == jm.mn.desc).map(m => (module, m))
      }

    def methodSym(c: JClass, jm: JMethod): Any = {
      val dd = forwardee(c, jm) match {
        case Some((_, m)) => m.dd
        case None => jm.dd
      }
      if (dd == null) null else symId(dd.symbol)
    }

    private def memberDefs(c: JClass): List[Tree] = c.classDef.impl.body

    def field(c: JClass, name: String): (Int, Boolean) =
      memberDefs(c).collectFirst { case v: ValDef if javaName(v.symbol) == name => v } match {
        case Some(v) => resolve(v, c.unit)
        case None => (-1, false)
      }
    def fieldSym(c: JClass, name: String): Any =
      memberDefs(c).collectFirst { case v: ValDef if javaName(v.symbol) == name => symId(v.symbol) }.orNull
  }
}
