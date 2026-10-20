// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.nsc

import scala.tools.asm.tree._

final class JvmRecorder(val rec: Recorder) {
  val global: rec.global.type = rec.global
  import global._
  def classNode(cd: ClassDef, cn: ClassNode): Unit = ()
  def mirrorNode(sym: Symbol, cn: ClassNode): Unit = ()
  def genMethod(dd: DefDef, mn: MethodNode): Unit = ()
  def genLoadEnter(tree: Tree, mn: MethodNode): Unit = ()
  def genLoadExit(tree: Tree, mn: MethodNode): Unit = ()
  def optimizerPass(owner: String, mn: MethodNode, pass: String): Unit = ()
  def inlined(callsite: AnyRef, map: Map[AbstractInsnNode, AbstractInsnNode]): Unit = ()
  def classWritten(cn: ClassNode): Unit = ()
  def emit(genPhase: Int, seq: Int, firstFreePhaseId: Int): Unit = ()
  def stats: List[(String, Any)] = Nil
}
