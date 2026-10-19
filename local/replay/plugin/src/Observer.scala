// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.{tpd, untpd, Trees}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.SymDenotations.SymDenotation
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}
import dotty.tools.dotc.core.TyperState
import dotty.tools.dotc.typer.Implicits.{Candidate, SearchResult}
import dotty.tools.dotc.util.Spans.Span
import org.objectweb.asm.tree.{AbstractInsnNode, ClassNode, MethodNode}

/** The recorder's view of the hooks: the fork's `typer.TyperObserver`, copied here so the plugin
 *  compiles against a stock compiler too. `Install` (src-fork/ or src-agent/) adapts a hook
 *  delivery to it. Differences from the fork's trait: `copied` has no Context (stock
 *  `TreeCopier.finalize` has none), and `rejected` exists only with the agent.
 */
trait Observer:
  def phaseBegin(phase: Phase)(using Context): Unit = ()
  def phaseEnd(phase: Phase, units: List[CompilationUnit])(using Context): Unit = ()
  def typedEnter(tree: untpd.Tree, pt: Type)(using Context): Unit = ()
  def typedExit(tree: untpd.Tree, pt: Type, result: tpd.Tree | Null)(using Context): Unit = ()
  def adaptEnter(tree: tpd.Tree, pt: Type)(using Context): Unit = ()
  def adaptExit(tree: tpd.Tree, pt: Type, result: tpd.Tree | Null)(using Context): Unit = ()
  def completeEnter(denot: SymDenotation)(using Context): Unit = ()
  def completeExit(denot: SymDenotation, ok: Boolean)(using Context): Unit = ()
  def implicitSearchEnter(pt: Type, argument: tpd.Tree, span: Span)(using Context): Unit = ()
  def implicitSearchExit(pt: Type, argument: tpd.Tree, span: Span, result: SearchResult | Null)(using Context): Unit = ()
  def candidateEnter(ref: TermRef, span: Span)(using Context): Unit = ()
  def candidateExit(ref: TermRef, span: Span, result: SearchResult | Null)(using Context): Unit = ()
  def report(dia: Diagnostic, reporter: Reporter)(using Context): Unit = ()
  def copied(tree: Trees.Tree[?], copy: Trees.Tree[?]): Unit = ()
  /** An implicit reference dropped before `tryImplicit`; `reason` is the pruning site. */
  def rejected(ref: TermRef, reason: String)(using Context): Unit = ()
  /** One ranking (`ImplicitSearch.searchImplicit(eligible, contextual)`) and its `rank` steps. */
  def scopeEnter(eligible: List[Candidate], contextual: Boolean)(using Context): Unit = ()
  def scopeExit(eligible: List[Candidate], contextual: Boolean, result: SearchResult | Null)(using Context): Unit = ()
  def rankStep(pending: List[Candidate], found: SearchResult)(using Context): Unit = ()
  /** `Typer.tryEither`; `tentativeState` is its fresh TyperState, `commit` any TyperState commit. */
  def tentativeEnter()(using Context): Unit = ()
  def tentativeExit(threw: Boolean)(using Context): Unit = ()
  def tentativeState(ts: TyperState): Unit = ()
  def commit(ts: TyperState): Unit = ()
  /** A report buffered by a TyperState's StoreReporter (not seen by `report`). */
  def bufferedReport(dia: Diagnostic, reporter: Reporter)(using Context): Unit = ()

  // JVM backend: same signatures as the fork's TyperObserver
  def classNodes(typeDef: tpd.TypeDef, classNode: ClassNode, mirror: ClassNode | Null)(using Context): Unit = ()
  def genMethod(dd: tpd.DefDef, method: MethodNode)(using Context): Unit = ()
  def genLoadEnter(tree: tpd.Tree, method: MethodNode)(using Context): Unit = ()
  def genLoadExit(tree: tpd.Tree, method: MethodNode)(using Context): Unit = ()
  def optimizerPass(owner: String, method: MethodNode, pass: String): Unit = ()
  def inlined(owner: String, method: MethodNode, calleeOwner: String, callee: MethodNode,
      insnMap: Map[AbstractInsnNode, AbstractInsnNode]): Unit = ()
  def classWritten(classNode: ClassNode): Unit = ()
