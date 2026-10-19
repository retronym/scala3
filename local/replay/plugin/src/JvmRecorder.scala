// JVM bytecode versions for the replay recorder: schema v1 `ir:"jvm"` (see ../SCHEMA-v1.md).
// LLM-assisted (Claude), see LLM_POLICY.md. Prototype; lives in local/, not for upstream.
package replay

import java.util.IdentityHashMap
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.objectweb.asm.{ClassVisitor, FieldVisitor, Handle, Label, MethodVisitor, Opcodes}
import org.objectweb.asm.tree.*
import org.objectweb.asm.util.{Textifier, TraceClassVisitor, TraceFieldVisitor, TraceMethodVisitor}

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*

// ---- text: ASM Textifier output with per-node offsets ------------------------------------------

/** Records, for each instruction, the index in the Textifier's text list where its text starts.
 *  `MethodNode.accept` makes exactly one of these calls per `AbstractInsnNode`, in list order.
 */
private final class MarkingMethodVisitor(val p: Textifier) extends MethodVisitor(Opcodes.ASM9, TraceMethodVisitor(p)):
  val marks = mutable.ArrayBuffer[Int]()
  var endMark = -1
  private def m(): Unit = marks += p.getText.size
  private def end(): Unit = if endMark < 0 then endMark = p.getText.size
  override def visitFrame(tpe: Int, nLocal: Int, local: Array[AnyRef], nStack: Int, stack: Array[AnyRef]): Unit =
    m(); super.visitFrame(tpe, nLocal, local, nStack, stack)
  override def visitInsn(op: Int): Unit = { m(); super.visitInsn(op) }
  override def visitIntInsn(op: Int, operand: Int): Unit = { m(); super.visitIntInsn(op, operand) }
  override def visitVarInsn(op: Int, v: Int): Unit = { m(); super.visitVarInsn(op, v) }
  override def visitTypeInsn(op: Int, tpe: String): Unit = { m(); super.visitTypeInsn(op, tpe) }
  override def visitFieldInsn(op: Int, owner: String, name: String, desc: String): Unit =
    m(); super.visitFieldInsn(op, owner, name, desc)
  override def visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean): Unit =
    m(); super.visitMethodInsn(op, owner, name, desc, itf)
  override def visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, args: Object*): Unit =
    m(); super.visitInvokeDynamicInsn(name, desc, bsm, args*)
  override def visitJumpInsn(op: Int, label: Label): Unit = { m(); super.visitJumpInsn(op, label) }
  override def visitLabel(label: Label): Unit = { m(); super.visitLabel(label) }
  override def visitLdcInsn(v: Object): Unit = { m(); super.visitLdcInsn(v) }
  override def visitIincInsn(v: Int, inc: Int): Unit = { m(); super.visitIincInsn(v, inc) }
  override def visitTableSwitchInsn(min: Int, max: Int, dflt: Label, labels: Label*): Unit =
    m(); super.visitTableSwitchInsn(min, max, dflt, labels*)
  override def visitLookupSwitchInsn(dflt: Label, keys: Array[Int], labels: Array[Label]): Unit =
    m(); super.visitLookupSwitchInsn(dflt, keys, labels)
  override def visitMultiANewArrayInsn(desc: String, dims: Int): Unit = { m(); super.visitMultiANewArrayInsn(desc, dims) }
  override def visitLineNumber(line: Int, start: Label): Unit = { m(); super.visitLineNumber(line, start) }
  override def visitLocalVariable(name: String, desc: String, sig: String, start: Label, end0: Label, index: Int): Unit =
    end(); super.visitLocalVariable(name, desc, sig, start, end0, index)
  override def visitMaxs(maxStack: Int, maxLocals: Int): Unit = { end(); super.visitMaxs(maxStack, maxLocals) }
  override def visitEnd(): Unit = { end(); super.visitEnd() }

/** Records where each field's and method's text starts in the class Textifier's text list. */
private final class MarkingClassVisitor(p: Textifier) extends ClassVisitor(Opcodes.ASM9, TraceClassVisitor(null, p, null)):
  val fields = mutable.ArrayBuffer[(Int, Int)]()                       // [start, end) list indices
  val methods = mutable.ArrayBuffer[(Int, Int, MarkingMethodVisitor)]() // header start, body list index
  override def visitField(access: Int, name: String, desc: String, sig: String, value: Object): FieldVisitor =
    val s = p.getText.size
    val fp = p.visitField(access, name, desc, sig, value)
    fields += ((s, p.getText.size))
    new TraceFieldVisitor(null, fp)
  override def visitMethod(access: Int, name: String, desc: String, sig: String, exceptions: Array[String]): MethodVisitor =
    val s = p.getText.size
    val mp = p.visitMethod(access, name, desc, sig, exceptions).asInstanceOf[Textifier]
    val mv = MarkingMethodVisitor(mp)
    methods += ((s, p.getText.size - 1, mv))
    mv

/** A method body's text; `insnOff(i)` is where instruction i's text starts, `insnOff(n)` where the
 *  instructions end (local variable table and maxs follow).
 */
final class MethodText(val text: String, val insnOff: Array[Int]):
  def insns: Int = insnOff.length - 1

/** A class's text, split around the method bodies: `parts(k)` precedes body k (it ends with method
 *  k's header, which starts at `hdrStart(k)` within it); `parts.last` follows the last body.
 */
final class ClassText(val parts: Array[String], val hdrStart: Array[Int], val fieldOff: Array[(Int, Int)],
    val bodies: Array[MethodText])

object JvmText:
  private def append(sb: java.lang.StringBuilder, x: Any): Unit = x match
    case l: java.util.List[?] => l.forEach(append(sb, _))
    case s => sb.append(s.toString)

  private def methodText(list: java.util.List[?], mv: MarkingMethodVisitor, nInsns: Int): MethodText =
    val sb = new java.lang.StringBuilder
    val off = new Array[Int](list.size + 1)
    var i = 0
    while i < list.size do
      off(i) = sb.length
      append(sb, list.get(i))
      i += 1
    off(list.size) = sb.length
    val n = mv.marks.length
    assert(n == nInsns, s"marked $n of $nInsns instructions")
    val insnOff = new Array[Int](n + 1)
    for k <- 0 until n do insnOff(k) = off(mv.marks(k))
    insnOff(n) = off(if mv.endMark < 0 then list.size else mv.endMark)
    MethodText(sb.toString, insnOff)

  def method(mn: MethodNode): MethodText =
    val p = new Textifier
    val mv = MarkingMethodVisitor(p)
    mn.accept(mv)
    methodText(p.getText, mv, mn.instructions.size)

  def cls(cn: ClassNode): ClassText =
    val p = new Textifier
    val cv = MarkingClassVisitor(p)
    cn.accept(cv)
    val l = p.getText
    val ms = cv.methods
    assert(ms.length == cn.methods.size)
    val parts = mutable.ArrayBuffer[String]()
    val hdr = mutable.ArrayBuffer[Int]()
    val bodies = mutable.ArrayBuffer[MethodText]()
    val off0 = new Array[Int](l.size + 1) // offsets within parts(0), for fields
    val sb = new java.lang.StringBuilder
    var k = 0
    for i <- 0 until l.size do
      if k < ms.length && i == ms(k)._1 then hdr += sb.length
      if k < ms.length && i == ms(k)._2 then
        parts += sb.toString
        sb.setLength(0)
        bodies += methodText(l.get(i).asInstanceOf[java.util.List[?]], ms(k)._3, cn.methods.get(k).instructions.size)
        k += 1
      else
        if k == 0 then off0(i) = sb.length
        append(sb, l.get(i))
    if k == 0 then off0(l.size) = sb.length
    parts += sb.toString
    ClassText(parts.toArray, hdr.toArray, cv.fields.map((s, e) => (off0(s), off0(e))).toArray, bodies.toArray)

// ---- recording ---------------------------------------------------------------------------------

/** Ordering key of a JVM version: genBCode, the global optimiser, local optimiser (round, pass),
 *  the final local passes, and the ClassWriter-bound class.
 */
final case class JKey(major: Int, minor: Int, name: String)
object JKey:
  val Gen = JKey(0, 0, "genBCode")
  val Inline = JKey(1, 0, "jvm:inliner")
  val Classfile = JKey(1 << 20, 0, "jvm:classfile")
  val roundPasses = Vector("nullness", "dce", "boxUnbox", "copyProp", "staleStores", "redundantCasts",
    "pushPop", "storeLoadPairs", "staleHandlers", "simplifyJumps")
  def local(round: Int, pass: String): JKey =
    roundPasses.indexOf(pass) match
      case -1 => JKey(1 << 19, pass.hashCode & 0xffff, s"jvm:$pass") // after the rounds (localVariables, lineNumbers)
      case i => JKey(1 + round, i, s"jvm:r$round:$pass")
  given Ordering[JKey] = Ordering.by(k => (k.major, if k.major == (1 << 19) then finalOrder(k.name) else k.minor))
  private def finalOrder(n: String) = n match
    case "jvm:localVariables" => 0
    case "jvm:lineNumbers" => 1
    case _ => 2

/** Where a range of instructions came from. */
sealed trait Origin
final case class Codegen(trees: mutable.ArrayBuffer[tpd.Tree]) extends Origin
/** Copied by the inliner from `callee` (`range`: the callee range copied, null for the whole body). */
final case class Inlined(calleeOwner: String, callee: MethodNode, range: RangeDef | Null) extends Origin

/** An instruction range, by the identity of its instructions (they survive optimiser rewrites). */
final class RangeDef(val members: Array[AbstractInsnNode], val origin: Origin)

/** A range in one method state: instruction indices [s, e). */
final class RState(val range: RangeDef, val s: Int, val e: Int):
  var id = -1

final class MState(val key: JKey, val text: MethodText, val ranges: Array[RState], val prev: MState | Null):
  var id = -1

final class InlineRec(val calleeOwner: String, val callee: MethodNode, val map: Map[AbstractInsnNode, AbstractInsnNode])

final class JMethod(val mn: MethodNode):
  var dd: tpd.DefDef | Null = null
  val raw = mutable.ArrayBuffer[(tpd.Tree, AbstractInsnNode, AbstractInsnNode)]() // genLoad ranges, first..last
  val states = mutable.ArrayBuffer[MState]()
  def cur: MState = states.last
  var round = 0
  val inlines = mutable.ArrayBuffer[InlineRec]()
  val real0 = new java.util.BitSet // instructions with an opcode, at genBCode
  var emitted = -1 // index of the state the last emitted class version used

final class JClass(val cn: ClassNode, val unit: Int, val typeDef: tpd.TypeDef, val mirrorOf: JClass | Null):
  var ct0: ClassText = null.asInstanceOf[ClassText]
  var ctFinal: ClassText | Null = null
  var methods: Array[JMethod] = Array()
  var fieldIds: Array[Int] = Array()
  var classId = -1

/** Collects backend observations during genBCode and writes them, after the tree versions, when the
 *  recorder closes. Requires `-Ybackend-parallelism:1`: the optimiser hooks then run on the
 *  compiler thread.
 */
final class JvmRecorder(rec: Recorder):
  private val classes = mutable.ArrayBuffer[JClass]()
  private val classOf = new IdentityHashMap[ClassNode, JClass]
  private val methodOf = new IdentityHashMap[MethodNode, JMethod]
  private val stack = mutable.ArrayBuffer[(tpd.Tree, AbstractInsnNode | Null)]()
  private var globalOpt = false     // the inliner / closure optimiser runs before the local optimiser
  private var globalPending = false // ... and has not been snapshotted yet
  // stats
  var unknownTrees, methodsTotal, methodsWithAncestor, insnsTotal, insnsCovered, rangesTotal, rangesWithAncestor,
    textChars, statesTotal, inlineRecs, classfileChanged = 0

  private def jmethod(mn: MethodNode): JMethod =
    var jm = methodOf.get(mn)
    if jm == null then
      jm = JMethod(mn)
      methodOf.put(mn, jm)
    jm

  def genMethod(dd: tpd.DefDef, mn: MethodNode): Unit = jmethod(mn).dd = dd

  def genLoadEnter(tree: tpd.Tree, mn: MethodNode): Unit =
    stack += ((tree, mn.instructions.getLast))

  def genLoadExit(tree: tpd.Tree, mn: MethodNode): Unit =
    val (t, prevLast) = stack.remove(stack.length - 1)
    assert(t eq tree)
    val start = if prevLast == null then mn.instructions.getFirst else prevLast.getNext
    val end = mn.instructions.getLast
    if start != null && end != null && (prevLast ne end) then jmethod(mn).raw += ((tree, start, end))

  def classNodes(typeDef: tpd.TypeDef, cn: ClassNode, mirror: ClassNode | Null)(using Context): Unit =
    globalOpt = ctx.settings.optInlineEnabled || ctx.settings.optClosureInvocations
    globalPending = globalOpt
    val u = rec.unitIdOf(ctx.compilationUnit.source)
    val main = addClass(cn, u, typeDef, null)
    if mirror != null then addClass(mirror, u, typeDef, main)

  private def addClass(cn: ClassNode, u: Int, typeDef: tpd.TypeDef, mirrorOf: JClass | Null): JClass =
    val c = JClass(cn, u, typeDef, mirrorOf)
    classes += c
    classOf.put(cn, c)
    c.ct0 = JvmText.cls(cn)
    c.methods = cn.methods.asScala.toArray.map(jmethod)
    for (jm, k) <- c.methods.zipWithIndex do
      val insns = jm.mn.instructions.toArray
      val idx = new IdentityHashMap[AbstractInsnNode, Integer]
      for (n, i) <- insns.zipWithIndex do
        idx.put(n, i)
        if n.getOpcode >= 0 then jm.real0.set(i)
      val byExtent = mutable.LinkedHashMap[(Int, Int), RangeDef]()
      // innermost first (genLoad exits first); sort outer-first so the viewer sees pre-order ids
      for (tree, first, last) <- jm.raw do
        val s = idx.get(first); val e = idx.get(last)
        if s != null && e != null && s.intValue <= e.intValue then
          val key = (s.intValue, e.intValue + 1)
          byExtent.getOrElseUpdate(key, RangeDef(insns.slice(key._1, key._2), Codegen(mutable.ArrayBuffer())))
            .origin.asInstanceOf[Codegen].trees += tree
      jm.raw.clear()
      val ranges = byExtent.toArray.sortBy((k, _) => (k._1, -k._2)).map((k, r) => RState(r, k._1, k._2))
      jm.states += MState(JKey.Gen, c.ct0.bodies(k), ranges, null)
    c

  def inlined(owner: String, mn: MethodNode, calleeOwner: String, callee: MethodNode,
      map: Map[AbstractInsnNode, AbstractInsnNode]): Unit =
    val jm = methodOf.get(mn)
    if jm != null then
      jm.inlines += InlineRec(calleeOwner, callee, map)
      inlineRecs += 1

  /** The first backend hook after `CodeGen.finish` ran the global optimiser over all classes. */
  private def snapshotGlobal(): Unit =
    globalPending = false
    for c <- classes; jm <- c.methods do
      val extras = mutable.ArrayBuffer[RangeDef]()
      for r <- jm.inlines do
        extras += RangeDef(r.map.valuesIterator.toArray, Inlined(r.calleeOwner, r.callee, null))
        val callee = methodOf.get(r.callee)
        if callee != null then
          for rs <- callee.states.head.ranges do
            val clones = rs.range.members.flatMap(r.map.get)
            if clones.nonEmpty then extras += RangeDef(clones, Inlined(r.calleeOwner, r.callee, rs.range))
      maybeState(jm, JKey.Inline, JvmText.method(jm.mn), extras)

  def optimizerPass(owner: String, mn: MethodNode, pass: String): Unit =
    if globalPending then snapshotGlobal()
    val jm = methodOf.get(mn)
    if jm != null then
      if pass == "beforeMethodOpt" then jm.round += 1
      else maybeState(jm, JKey.local(jm.round, pass), JvmText.method(mn), Nil)

  def classWritten(cn: ClassNode): Unit =
    if globalPending then snapshotGlobal()
    val c = classOf.get(cn)
    if c != null then
      val ct = JvmText.cls(cn)
      if ct.bodies.length == c.methods.length && c.methods.indices.forall(k => cn.methods.get(k) eq c.methods(k).mn) then
        for k <- c.methods.indices do maybeState(c.methods(k), JKey.Classfile, ct.bodies(k), Nil)
        if !(ct.parts sameElements c.ct0.parts) || c.methods.exists(_.cur.key == JKey.Classfile) then
          c.ctFinal = ct
          classfileChanged += 1

  /** A new state of `jm` at `key` if its text changed (or the inliner added ranges): ranges follow
   *  their surviving instructions; a range none of whose instructions survive is dropped (elided).
   */
  private def maybeState(jm: JMethod, key: JKey, mt: MethodText, extras: collection.Seq[RangeDef]): Unit =
    if mt.text != jm.cur.text.text || extras.nonEmpty then
      val idx = new IdentityHashMap[AbstractInsnNode, Integer]
      var i = 0
      val it = jm.mn.instructions.iterator
      while it.hasNext do { idx.put(it.next(), i); i += 1 }
      def place(r: RangeDef): RState | Null =
        var lo = Int.MaxValue; var hi = -1
        for n <- r.members do
          val j = idx.get(n)
          if j != null then { lo = lo.min(j.intValue); hi = hi.max(j.intValue) }
        if hi < 0 then null else RState(r, lo, hi + 1)
      val placed = (jm.cur.ranges.iterator.map(_.range) ++ extras).flatMap(r => Option(place(r))).toArray
      jm.states += MState(key, mt, placed.sortBy(r => (r.s, -r.e)), jm.cur)
      statesTotal += 1

  // ---- emission -------------------------------------------------------------------------------

  /** Writes all JVM records: per key (genBCode first), per class, the node records, the version and
   *  its derive records. Called by `Recorder.close` after the last tree version.
   */
  def emit(genPhase: Int, seq: Int, firstFreePhaseId: Int)(using Context): Unit =
    if classes.isEmpty then return
    val keys = (classes.iterator.flatMap(_.methods.iterator.flatMap(_.states.iterator.map(_.key)))
      ++ (if classes.exists(_.ctFinal != null) then Iterator(JKey.Classfile) else Iterator.empty)).toSet.toList.sorted
    var nextPhase = firstFreePhaseId
    val resolver = TreeResolver()
    for key <- keys do
      val pid =
        if key == JKey.Gen then genPhase
        else
          val p = nextPhase
          nextPhase += 1
          rec.emitObj("t" -> "phase", "id" -> p, "name" -> key.name, "ir" -> "jvm", "mini" -> false, "group" -> p,
            "megaOf" -> null, "denotTransformer" -> false)
          p
      for c <- classes do emitClass(c, key, pid, seq, resolver)

  private def emitClass(c: JClass, key: JKey, pid: Int, seq: Int, resolver: TreeResolver)(using Context): Unit =
    val gen = key == JKey.Gen
    val changed = c.methods.filter(jm => jm.emitted + 1 < jm.states.length && jm.states(jm.emitted + 1).key == key)
    val ct = if key == JKey.Classfile && c.ctFinal != null then c.ctFinal.nn else c.ct0
    if !gen && changed.isEmpty && !(key == JKey.Classfile && c.ctFinal != null) then return
    val u = c.unit
    val derives = mutable.ArrayBuffer[(String, String, Seq[Int], Seq[Int], (String, Any) | Null)]()
    var newCount = 0
    def node(fields: (String, Any)*): Int =
      newCount += 1
      rec.newExtNode(fields*)

    if gen then
      c.fieldIds = c.cn.fields.asScala.toArray.map(f =>
        node("kind" -> "FieldNode", "unit" -> u, "name" -> f.name, "desc" -> f.desc, "sym" -> resolver.fieldSym(c, f.name)))
    for jm <- changed do
      val st = jm.states(jm.emitted + 1)
      jm.emitted += 1
      st.id = node("kind" -> "MethodNode", "unit" -> u, "name" -> jm.mn.name, "desc" -> jm.mn.desc,
        "insns" -> st.text.insns, "sym" -> resolver.methodSym(c, jm))
      for r <- st.ranges do
        r.id = node("kind" -> "InsnRange", "unit" -> u, "method" -> st.id, "range" -> List(r.s, r.e))
      val prev = st.prev
      if prev != null then
        derives += (("rewrite", "exact", List(st.id), List(prev.id), null))
        val prevOf = prev.ranges.iterator.map(r => r.range -> r).toMap
        for r <- st.ranges do
          prevOf.get(r.range) match
            case Some(o) =>
              val same = st.text.text.substring(st.text.insnOff(r.s), st.text.insnOff(r.e)) ==
                prev.text.text.substring(prev.text.insnOff(o.s), prev.text.insnOff(o.e))
              derives += ((if same then "copy" else "rewrite", "exact", List(r.id), List(o.id), null))
            case None => r.range.origin match
              case Inlined(owner, callee, cr) =>
                val calleeM = methodOf.get(callee)
                val old: List[Int] =
                  if calleeM == null then Nil
                  else if cr == null then List(calleeM.states.head.id).filter(_ >= 0)
                  else calleeM.states.head.ranges.find(_.range eq cr).map(_.id).toList.filter(_ >= 0)
                derives += (("inlined-from", "exact", List(r.id), old, "callee" -> s"$owner.${callee.name}${callee.desc}"))
              case _ =>
        val kept = st.ranges.iterator.map(_.range).toSet
        val gone = prev.ranges.filter(r => !kept(r.range)).map(_.id)
        if gone.nonEmpty then derives += (("elide", "exact", Nil, gone.toList, null))
    // the class node: children are the fields and the methods' current states
    val methodIds = c.methods.map(m => m.states(m.emitted).id)
    val prevClassId = c.classId
    c.classId = node("kind" -> "ClassNode", "unit" -> u, "name" -> c.cn.name, "children" -> (c.fieldIds.toList ++ methodIds),
      "sym" -> resolver.symId(c.typeDef.symbol), "mirror" -> (c.mirrorOf != null))
    if gen then codegenDerives(c, resolver, derives)
    else derives += (("rewrite", "exact", List(c.classId), List(prevClassId), null))

    // text and offsets
    val sb = new java.lang.StringBuilder
    val offs = mutable.ArrayBuffer[List[Int]]()
    for ((s, e), id) <- ct.fieldOff.zip(c.fieldIds) do offs += List(id, s, e)
    for k <- c.methods.indices do
      sb.append(ct.parts(k))
      val hdr = sb.length - ct.parts(k).length + ct.hdrStart(k)
      val st = c.methods(k).states(c.methods(k).emitted)
      val base = sb.length
      sb.append(st.text.text)
      offs += List(st.id, hdr, sb.length)
      for r <- st.ranges do offs += List(r.id, base + st.text.insnOff(r.s), base + st.text.insnOff(r.e))
    sb.append(ct.parts.last)
    offs += List(c.classId, 0, sb.length)
    textChars += sb.length
    val sorted = offs.sortBy(t => (t(1), -t(2), t(0)))
    val nodes = 1 + c.fieldIds.length + c.methods.map(m => 1 + m.states(m.emitted).ranges.length).sum
    rec.emitObj("t" -> "version", "ir" -> "jvm", "phase" -> pid, "unit" -> u, "seq" -> seq, "class" -> c.cn.name,
      "root" -> c.classId, "text" -> sb.toString, "offsets" -> sorted, "nodes" -> nodes, "new" -> newCount)
    for (how, conf, news, olds, extra) <- derives do
      val fields = mutable.ListBuffer[(String, Any)]("t" -> "derive", "ir" -> "jvm", "phase" -> pid, "unit" -> u,
        "new" -> news.toList, "old" -> olds.toList, "how" -> how, "confidence" -> conf)
      if extra != null then fields += extra.nn
      rec.emitObj(fields.toList*)
      rec.countDerive(how, conf, news.length.max(olds.length))

  /** genBCode lineage: tree node -> ClassNode / FieldNode / MethodNode / InsnRange. */
  private def codegenDerives(c: JClass, resolver: TreeResolver,
      out: mutable.ArrayBuffer[(String, String, Seq[Int], Seq[Int], (String, Any) | Null)])(using Context): Unit =
    val u = c.unit
    def add(news: Seq[Int], anc: (Int, Boolean)): Unit =
      out += (("codegen", if anc._2 then "exact" else "heuristic", news, List(anc._1), null))
    val td = resolver.resolve(c.typeDef, u)
    if td._1 >= 0 then add(List(c.classId), td)
    for (f, id) <- c.cn.fields.asScala.zip(c.fieldIds) do
      val a = resolver.field(c, f.name)
      if a._1 >= 0 then add(List(id), a)
      else if f.name == "MODULE$" && td._1 >= 0 then add(List(id), td)
    for jm <- c.methods do
      val st = jm.states.head
      methodsTotal += 1
      val ma = resolver.method(c, jm)
      if ma._1 >= 0 then { methodsWithAncestor += 1; add(List(st.id), ma) }
      // ranges, grouped by the tree node they come from
      val covered = new java.util.BitSet
      val byAnc = mutable.LinkedHashMap[(Int, Boolean), mutable.ArrayBuffer[Int]]()
      for r <- st.ranges do
        rangesTotal += 1
        var any = false
        for t <- r.range.origin.asInstanceOf[Codegen].trees do
          val a = resolver.resolve(t, u)
          if a._1 >= 0 then
            any = true
            val ids = byAnc.getOrElseUpdate(a, mutable.ArrayBuffer())
            if !ids.contains(r.id) then ids += r.id
          else unknownTrees += 1
        if any then
          rangesWithAncestor += 1
          covered.set(r.s, r.e)
      for (a, ids) <- byAnc do add(ids.toList, a)
      // real instructions (not labels, line numbers, frames) of the genBCode state
      insnsTotal += jm.real0.cardinality
      covered.and(jm.real0)
      insnsCovered += covered.cardinality

  def stats: List[(String, Any)] = List(
    "classes" -> classes.length, "methods" -> methodsTotal, "methodsWithAncestor" -> methodsWithAncestor,
    "insns" -> insnsTotal, "insnsCovered" -> insnsCovered, "ranges" -> rangesTotal,
    "rangesWithAncestor" -> rangesWithAncestor, "unknownTrees" -> unknownTrees, "textChars" -> textChars,
    "optStates" -> statesTotal, "inlined" -> inlineRecs, "classfileChanged" -> classfileChanged)

  /** Finds the tree node (in the unit's last tree version) a backend tree comes from. */
  private final class TreeResolver(using Context):
    private val perUnit = mutable.HashMap[Int, (java.util.BitSet, mutable.HashMap[Int, Int], mutable.HashMap[(Long, String), Int])]()
    private def index(u: Int) = perUnit.getOrElseUpdate(u, {
      val (live, reach) = rec.lastTreeVersion(u)
      val bySym = mutable.HashMap[Int, Int]()
      val bySpan = mutable.HashMap[(Long, String), Int]()
      for id <- reach do
        val k = rec.nodeKindOf(id)
        if (k == "DefDef" || k == "ValDef" || k == "TypeDef") && rec.nodeSymOf(id) >= 0 then bySym(rec.nodeSymOf(id)) = id
        val sp = rec.nodeSpanOf(id)
        if sp >= 0 then bySpan((sp, k)) = bySpan.getOrElse((sp, k), -1).max(id) // outermost: largest post-order id
      (live, bySym, bySpan)
    })
    def symId(sym: Symbol): Any = { val i = rec.symIdOf(sym); if i < 0 then null else i }

    /** (node id, exact) or (-1, _): the arena node itself, via the copier hook, by symbol, or by span. */
    def resolve(t0: tpd.Tree, u: Int): (Int, Boolean) =
      val (live, bySym, bySpan) = index(u)
      var t: Tree[?] | Null = t0
      var steps = 0
      while t != null && steps < 100 do
        val id = rec.treeNodeId(t.nn)
        if id >= 0 && live.get(id) then return (id, true)
        t = rec.copySource(t.nn)
        steps += 1
      t0 match
        case md: tpd.MemberDef if md.symbol.exists =>
          bySym.get(rec.symIdOf(md.symbol)) match
            case Some(id) => return (id, true)
            case None =>
        case _ =>
      val sp = if t0.span.exists then (t0.span.start.toLong << 32) | t0.span.end.toLong else -1L
      if sp >= 0 then bySpan.get((sp, t0.productPrefix)) match
        case Some(id) => return (id, false)
        case None =>
      (-1, false)

    def method(c: JClass, jm: JMethod): (Int, Boolean) =
      def sameSig(m: JMethod) = m.mn.name == jm.mn.name && m.mn.desc == jm.mn.desc
      forwardee(c, jm) match
        case Some((c2, m2)) => method(c2, m2) // a static forwarder: the module method's ancestor
        case None if jm.dd == null => inheritedForwardee(c, jm)
        case None =>
          val r = resolve(jm.dd.nn, c.unit)
          if r._1 >= 0 && !(jm.mn.name == "<clinit>" && !r._2) then r
          else if jm.mn.name == "<clinit>" then (resolve(c.typeDef, c.unit)._1, false) // MODULE$ init and moved constructor statements
          else
            // statified trait methods (`$init$`) and trait static forwarders (`m$`) get fresh symbols: match by name
            memberDefs(c).collectFirst {
              case d: tpd.DefDef if { val n = d.symbol.denot.javaSimpleName; n == jm.mn.name || n + "$" == jm.mn.name
                  || jm.mn.name == "$init$" && d.symbol.isConstructor } => d
            }.map(d => (resolve(d, c.unit)._1, false)).getOrElse((-1, false))

    /** A forwarder to a method the module inherits: its DefDef, by symbol, in any unit (exact if the
     *  name is not overloaded).
     */
    private def inheritedForwardee(c: JClass, jm: JMethod): (Int, Boolean) =
      moduleOf(c) match
        case null => (-1, false)
        case module =>
          val alts = module.nn.typeDef.symbol.info.member(dotty.tools.dotc.core.Names.termName(jm.mn.name)).alternatives
          val found = alts.iterator.flatMap(d => perUnitAll.flatMap(u => index(u)._2.get(rec.symIdOf(d.symbol)))).toList
          found match
            case id :: _ => (id, alts.length == 1)
            case Nil => (-1, false)

    private def perUnitAll: Seq[Int] = classes.iterator.map(_.unit).toSeq.distinct

    private def moduleOf(c: JClass): JClass | Null =
      if c.mirrorOf != null then c.mirrorOf
      else
        val mc = c.typeDef.symbol.companionModule.moduleClass
        classes.find(c2 => c2.mirrorOf == null && (c2 ne c) && c2.typeDef.symbol == mc).orNull

    /** For a forwarder (in a mirror class, or a companion class, emitted without genDefDef), the
     *  module class method it forwards to.
     */
    private def forwardee(c: JClass, jm: JMethod): Option[(JClass, JMethod)] =
      if jm.dd != null then None
      else
        val module = moduleOf(c)
        if module == null then None
        else module.nn.methods.find(m => m.mn.name == jm.mn.name && m.mn.desc == jm.mn.desc).map(m => (module.nn, m))

    def methodSym(c: JClass, jm: JMethod): Any =
      val dd = forwardee(c, jm) match
        case Some((_, m)) => m.dd
        case None => jm.dd
      if dd == null then null else symId(dd.nn.symbol)

    private def memberDefs(c: JClass): List[tpd.Tree] = c.typeDef.rhs match
      case t: tpd.Template => t.constr :: t.body
      case _ => Nil

    def field(c: JClass, name: String): (Int, Boolean) =
      memberDefs(c).collectFirst { case v: tpd.ValDef if v.symbol.denot.javaSimpleName == name => v } match
        case Some(v) => resolve(v, c.unit)
        case None => (-1, false)
    def fieldSym(c: JClass, name: String): Any =
      memberDefs(c).collectFirst { case v: tpd.ValDef if v.symbol.denot.javaSimpleName == name => symId(v.symbol) }.orNull
end JvmRecorder
