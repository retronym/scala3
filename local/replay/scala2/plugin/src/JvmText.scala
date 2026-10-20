// JVM bytecode text for the nsc recorder: a Scala 2 copy of ../../plugin/src/JvmRecorder.scala's JvmText.
// LLM-assisted (Claude), see LLM_POLICY.md. Prototype; lives in local/, not for upstream.
package replay.nsc

import scala.collection.mutable
import scala.tools.asm.{ClassVisitor, FieldVisitor, Handle, Label, MethodVisitor, Opcodes}
import scala.tools.asm.tree._
import scala.tools.asm.util.{Textifier, TraceClassVisitor, TraceFieldVisitor, TraceMethodVisitor}

/** Records, for each instruction, the index in the Textifier's text list where its text starts.
 *  `MethodNode.accept` makes exactly one of these calls per `AbstractInsnNode`, in list order.
 */
private final class MarkingMethodVisitor(val p: Textifier) extends MethodVisitor(Opcodes.ASM9, new TraceMethodVisitor(p)) {
  val marks = mutable.ArrayBuffer[Int]()
  var endMark = -1
  private def m(): Unit = marks += p.getText.size
  private def end(): Unit = if (endMark < 0) endMark = p.getText.size
  override def visitFrame(tpe: Int, nLocal: Int, local: Array[AnyRef], nStack: Int, stack: Array[AnyRef]): Unit = {
    m(); super.visitFrame(tpe, nLocal, local, nStack, stack)
  }
  override def visitInsn(op: Int): Unit = { m(); super.visitInsn(op) }
  override def visitIntInsn(op: Int, operand: Int): Unit = { m(); super.visitIntInsn(op, operand) }
  override def visitVarInsn(op: Int, v: Int): Unit = { m(); super.visitVarInsn(op, v) }
  override def visitTypeInsn(op: Int, tpe: String): Unit = { m(); super.visitTypeInsn(op, tpe) }
  override def visitFieldInsn(op: Int, owner: String, name: String, desc: String): Unit = {
    m(); super.visitFieldInsn(op, owner, name, desc)
  }
  override def visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean): Unit = {
    m(); super.visitMethodInsn(op, owner, name, desc, itf)
  }
  override def visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, args: AnyRef*): Unit = {
    m(); super.visitInvokeDynamicInsn(name, desc, bsm, args: _*)
  }
  override def visitJumpInsn(op: Int, label: Label): Unit = { m(); super.visitJumpInsn(op, label) }
  override def visitLabel(label: Label): Unit = { m(); super.visitLabel(label) }
  override def visitLdcInsn(v: AnyRef): Unit = { m(); super.visitLdcInsn(v) }
  override def visitIincInsn(v: Int, inc: Int): Unit = { m(); super.visitIincInsn(v, inc) }
  override def visitTableSwitchInsn(min: Int, max: Int, dflt: Label, labels: Label*): Unit = {
    m(); super.visitTableSwitchInsn(min, max, dflt, labels: _*)
  }
  override def visitLookupSwitchInsn(dflt: Label, keys: Array[Int], labels: Array[Label]): Unit = {
    m(); super.visitLookupSwitchInsn(dflt, keys, labels)
  }
  override def visitMultiANewArrayInsn(desc: String, dims: Int): Unit = { m(); super.visitMultiANewArrayInsn(desc, dims) }
  override def visitLineNumber(line: Int, start: Label): Unit = { m(); super.visitLineNumber(line, start) }
  override def visitLocalVariable(name: String, desc: String, sig: String, start: Label, end0: Label, index: Int): Unit = {
    end(); super.visitLocalVariable(name, desc, sig, start, end0, index)
  }
  // MAXSTACK/MAXLOCALS are 0 until the optimiser or the ClassWriter computes them: leave them out of the text
  override def visitMaxs(maxStack: Int, maxLocals: Int): Unit = end()
  override def visitEnd(): Unit = { end(); super.visitEnd() }
}

/** Records where each field's and method's text starts in the class Textifier's text list. */
private final class MarkingClassVisitor(p: Textifier) extends ClassVisitor(Opcodes.ASM9, new TraceClassVisitor(null, p, null)) {
  val fields = mutable.ArrayBuffer[(Int, Int)]()
  val methods = mutable.ArrayBuffer[(Int, Int, MarkingMethodVisitor)]()
  override def visitField(access: Int, name: String, desc: String, sig: String, value: AnyRef): FieldVisitor = {
    val s = p.getText.size
    val fp = p.visitField(access, name, desc, sig, value)
    fields += ((s, p.getText.size))
    new TraceFieldVisitor(null, fp)
  }
  override def visitMethod(access: Int, name: String, desc: String, sig: String, exceptions: Array[String]): MethodVisitor = {
    val s = p.getText.size
    val mp = p.visitMethod(access, name, desc, sig, exceptions).asInstanceOf[Textifier]
    val mv = new MarkingMethodVisitor(mp)
    methods += ((s, p.getText.size - 1, mv))
    mv
  }
}

final class MethodText(val text: String, val insnOff: Array[Int]) {
  def insns: Int = insnOff.length - 1
}

final class ClassText(val parts: Array[String], val hdrStart: Array[Int], val fieldOff: Array[(Int, Int)],
    val bodies: Array[MethodText])

object JvmText {
  private def append(sb: java.lang.StringBuilder, x: Any): Unit = x match {
    case l: java.util.List[_] => l.forEach(e => append(sb, e))
    case s => sb.append(s.toString)
  }

  private def methodText(list: java.util.List[_], mv: MarkingMethodVisitor, nInsns: Int): MethodText = {
    val sb = new java.lang.StringBuilder
    val off = new Array[Int](list.size + 1)
    var i = 0
    while (i < list.size) {
      off(i) = sb.length
      append(sb, list.get(i))
      i += 1
    }
    off(list.size) = sb.length
    val n = mv.marks.length
    assert(n == nInsns, s"marked $n of $nInsns instructions")
    val insnOff = new Array[Int](n + 1)
    for (k <- 0 until n) insnOff(k) = off(mv.marks(k))
    insnOff(n) = off(if (mv.endMark < 0) list.size else mv.endMark)
    new MethodText(sb.toString, insnOff)
  }

  def method(mn: MethodNode): MethodText = {
    val p = new Textifier
    val mv = new MarkingMethodVisitor(p)
    mn.accept(mv)
    methodText(p.getText, mv, mn.instructions.size)
  }

  def cls(cn: ClassNode): ClassText = {
    val p = new Textifier
    val cv = new MarkingClassVisitor(p)
    cn.accept(cv)
    val l = p.getText
    val ms = cv.methods
    assert(ms.length == cn.methods.size)
    val parts = mutable.ArrayBuffer[String]()
    val hdr = mutable.ArrayBuffer[Int]()
    val bodies = mutable.ArrayBuffer[MethodText]()
    val off0 = new Array[Int](l.size + 1)
    val sb = new java.lang.StringBuilder
    var k = 0
    for (i <- 0 until l.size) {
      if (k < ms.length && i == ms(k)._1) hdr += sb.length
      if (k < ms.length && i == ms(k)._2) {
        parts += sb.toString
        sb.setLength(0)
        bodies += methodText(l.get(i).asInstanceOf[java.util.List[_]], ms(k)._3, cn.methods.get(k).instructions.size)
        k += 1
      } else {
        if (k == 0) off0(i) = sb.length
        append(sb, l.get(i))
      }
    }
    if (k == 0) off0(l.size) = sb.length
    parts += sb.toString
    new ClassText(parts.toArray, hdr.toArray, cv.fields.map { case (s, e) => (off0(s), off0(e)) }.toArray, bodies.toArray)
  }
}

/** Ordering key of a JVM version: genBCode, the global optimiser, local optimiser (round, pass),
 *  the final local passes, and the ClassWriter-bound class.
 */
final case class JKey(major: Int, minor: Int, name: String)
object JKey {
  val Gen = JKey(0, 0, "genBCode")
  val Inline = JKey(1, 0, "jvm:inliner")
  val Classfile = JKey(1 << 20, 0, "jvm:classfile")
  val roundPasses = Vector("nullness", "dce", "boxUnbox", "copyProp", "staleStores", "redundantCasts",
    "pushPop", "storeLoadPairs", "staleHandlers", "simplifyJumps")
  def local(round: Int, pass: String): JKey =
    roundPasses.indexOf(pass) match {
      case -1 => JKey(1 << 19, pass.hashCode & 0xffff, s"jvm:$pass")
      case i => JKey(1 + round, i, s"jvm:r$round:$pass")
    }
  implicit val ordering: Ordering[JKey] = Ordering.by(k => (k.major, if (k.major == (1 << 19)) finalOrder(k.name) else k.minor))
  private def finalOrder(n: String) = n match {
    case "jvm:localVariables" => 0
    case "jvm:lineNumbers" => 1
    case _ => 2
  }
}
