// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.{tpd, untpd, Trees}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.SymDenotations.SymDenotation
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}
import dotty.tools.dotc.typer.Implicits.SearchResult
import dotty.tools.dotc.typer.TyperObserver
import dotty.tools.dotc.util.Spans.Span
import org.objectweb.asm.tree.{AbstractInsnNode, ClassNode, MethodNode}

/** Hook delivery by the fork: `ctx.base.observer`. */
object Install:
  def install(o: Observer, options: List[String])(using Context): Unit = ctx.base.observer = ForkShim(o)
  def uninstall(o: Observer)(using Context): Unit = ctx.base.observer match
    case s: ForkShim if s.o eq o => ctx.base.observer = null
    case _ =>

final class ForkShim(val o: Observer) extends TyperObserver:
  override def phaseBegin(phase: Phase)(using Context) = o.phaseBegin(phase)
  override def phaseEnd(phase: Phase, units: List[CompilationUnit])(using Context) = o.phaseEnd(phase, units)
  override def typedEnter(tree: untpd.Tree, pt: Type)(using Context) = o.typedEnter(tree, pt)
  override def typedExit(tree: untpd.Tree, pt: Type, result: tpd.Tree | Null)(using Context) = o.typedExit(tree, pt, result)
  override def adaptEnter(tree: tpd.Tree, pt: Type)(using Context) = o.adaptEnter(tree, pt)
  override def adaptExit(tree: tpd.Tree, pt: Type, result: tpd.Tree | Null)(using Context) = o.adaptExit(tree, pt, result)
  override def completeEnter(denot: SymDenotation)(using Context) = o.completeEnter(denot)
  override def completeExit(denot: SymDenotation, ok: Boolean)(using Context) = o.completeExit(denot, ok)
  override def implicitSearchEnter(pt: Type, argument: tpd.Tree, span: Span)(using Context) = o.implicitSearchEnter(pt, argument, span)
  override def implicitSearchExit(pt: Type, argument: tpd.Tree, span: Span, result: SearchResult | Null)(using Context) =
    o.implicitSearchExit(pt, argument, span, result)
  override def candidateEnter(ref: TermRef, span: Span)(using Context) = o.candidateEnter(ref, span)
  override def candidateExit(ref: TermRef, span: Span, result: SearchResult | Null)(using Context) = o.candidateExit(ref, span, result)
  override def report(dia: Diagnostic, reporter: Reporter)(using Context) = o.report(dia, reporter)
  override def copied(tree: Trees.Tree[?], copy: Trees.Tree[?])(using Context) = o.copied(tree, copy)
  override def classNodes(typeDef: tpd.TypeDef, classNode: ClassNode, mirror: ClassNode | Null)(using Context) =
    o.classNodes(typeDef, classNode, mirror)
  override def genMethod(dd: tpd.DefDef, method: MethodNode)(using Context) = o.genMethod(dd, method)
  override def genLoadEnter(tree: tpd.Tree, method: MethodNode)(using Context) = o.genLoadEnter(tree, method)
  override def genLoadExit(tree: tpd.Tree, method: MethodNode)(using Context) = o.genLoadExit(tree, method)
  override def optimizerPass(owner: String, method: MethodNode, pass: String) = o.optimizerPass(owner, method, pass)
  override def inlined(owner: String, method: MethodNode, calleeOwner: String, callee: MethodNode,
      insnMap: Map[AbstractInsnNode, AbstractInsnNode]) = o.inlined(owner, method, calleeOwner, callee, insnMap)
  override def classWritten(classNode: ClassNode) = o.classWritten(classNode)
