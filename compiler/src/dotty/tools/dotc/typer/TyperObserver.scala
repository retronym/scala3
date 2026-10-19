package dotty.tools
package dotc
package typer

import ast.{tpd, untpd, Trees}
import core.*, Contexts.*, Types.*, Phases.Phase, SymDenotations.SymDenotation
import reporting.{Diagnostic, Reporter}
import util.Spans.Span
import Implicits.SearchResult
import org.objectweb.asm.tree.{AbstractInsnNode, ClassNode, MethodNode}

/** Observes typing, completion, implicit search, diagnostics, tree copying and the phase loop,
 *  for tools that record a compilation (e.g. a replay recorder supplied by a plugin).
 *
 *  Installed on `ctx.base.observer`, which is null unless a tool sets it. Every hook site
 *  tests for null first, so an absent observer costs a field read and a branch.
 *
 *  `*Enter`/`*Exit` pairs nest properly; `result` is null if the observed operation threw.
 *  An observer must not force completions, type trees or render messages inside a hook,
 *  or it changes what it observes; defer that work to `phaseEnd`.
 */
trait TyperObserver:
  /** `phase` is the phase (or fused MegaPhase) the Run is about to execute. */
  def phaseBegin(phase: Phase)(using Context): Unit = ()
  def phaseEnd(phase: Phase, units: List[CompilationUnit])(using Context): Unit = ()

  /** `Typer.typed`, covering `typedUnadapted` and `adapt`. Also fires for the retyping done by
   *  typers in later phases (Erasure, Inlining).
   */
  def typedEnter(tree: untpd.Tree, pt: Type)(using Context): Unit = ()
  def typedExit(tree: untpd.Tree, pt: Type, result: tpd.Tree | Null)(using Context): Unit = ()

  def adaptEnter(tree: tpd.Tree, pt: Type)(using Context): Unit = ()
  def adaptExit(tree: tpd.Tree, pt: Type, result: tpd.Tree | Null)(using Context): Unit = ()

  /** `Namer.Completer.complete`; nests inside whatever forced the completion. */
  def completeEnter(denot: SymDenotation)(using Context): Unit = ()
  def completeExit(denot: SymDenotation, ok: Boolean)(using Context): Unit = ()

  /** `Implicits.inferImplicit`, and each candidate it tries. */
  def implicitSearchEnter(pt: Type, argument: tpd.Tree, span: Span)(using Context): Unit = ()
  def implicitSearchExit(pt: Type, argument: tpd.Tree, span: Span, result: SearchResult | Null)(using Context): Unit = ()
  def candidateEnter(ref: TermRef, span: Span)(using Context): Unit = ()
  def candidateExit(ref: TermRef, span: Span, result: SearchResult | Null)(using Context): Unit = ()

  /** `Reporter.report`, before suppression and buffering. */
  def report(dia: Diagnostic, reporter: Reporter)(using Context): Unit = ()

  /** A `TreeCopier` produced `copy` from `tree`. */
  def copied(tree: Trees.Tree[?], copy: Trees.Tree[?])(using Context): Unit = ()

  // JVM backend (GenBCode). The hooks without a Context run where the backend serializes classes,
  // which is a worker thread unless `-Ybackend-parallelism:1`.

  /** `CodeGen` generated `classNode` (and `mirror`, a module's static forwarder class) for `typeDef`,
   *  before any optimisation.
   */
  def classNodes(typeDef: tpd.TypeDef, classNode: ClassNode, mirror: ClassNode | Null)(using Context): Unit = ()
  /** `BCodeSkelBuilder.genDefDef` started emitting `method` for `dd`. */
  def genMethod(dd: tpd.DefDef, method: MethodNode)(using Context): Unit = ()
  /** `genLoadTo` / `genStat` emitting the code for `tree` into `method`. */
  def genLoadEnter(tree: tpd.Tree, method: MethodNode)(using Context): Unit = ()
  def genLoadExit(tree: tpd.Tree, method: MethodNode)(using Context): Unit = ()
  /** An optimiser pass named `pass` ran on `method` of class `owner` (it may not have changed it). */
  def optimizerPass(owner: String, method: MethodNode, pass: String): Unit = ()
  /** The inliner copied the body of `callee` into `method`; `insnMap` maps callee instructions to their copies. */
  def inlined(owner: String, method: MethodNode, calleeOwner: String, callee: MethodNode,
      insnMap: Map[AbstractInsnNode, AbstractInsnNode]): Unit = ()
  /** `classNode` is final and about to be serialized by a `ClassWriter`. */
  def classWritten(classNode: ClassNode): Unit = ()

object TyperObserver:

  // Hook helpers: with no observer, a field read and a branch (plus a `try`, which is free).

  inline def typed(tree: untpd.Tree, pt: Type)(inline body: tpd.Tree)(using Context): tpd.Tree =
    val obs = ctx.base.observer
    if obs != null then obs.typedEnter(tree, pt)
    var res: tpd.Tree | Null = null
    try
      res = body
      res
    finally if obs != null then obs.typedExit(tree, pt, res)

  inline def adapt(tree: tpd.Tree, pt: Type)(inline body: tpd.Tree)(using Context): tpd.Tree =
    val obs = ctx.base.observer
    if obs != null then obs.adaptEnter(tree, pt)
    var res: tpd.Tree | Null = null
    try
      res = body
      res
    finally if obs != null then obs.adaptExit(tree, pt, res)

  inline def complete(denot: SymDenotation)(inline body: Unit)(using Context): Unit =
    val obs = ctx.base.observer
    if obs != null then obs.completeEnter(denot)
    var ok = false
    try
      body
      ok = true
    finally if obs != null then obs.completeExit(denot, ok)

  inline def implicitSearch(pt: Type, argument: tpd.Tree, span: Span)(inline body: SearchResult)(using Context): SearchResult =
    val obs = ctx.base.observer
    if obs != null then obs.implicitSearchEnter(pt, argument, span)
    var res: SearchResult | Null = null
    try
      res = body
      res
    finally if obs != null then obs.implicitSearchExit(pt, argument, span, res)

  inline def candidate(ref: TermRef, span: Span)(inline body: SearchResult)(using Context): SearchResult =
    val obs = ctx.base.observer
    if obs != null then obs.candidateEnter(ref, span)
    var res: SearchResult | Null = null
    try
      res = body
      res
    finally if obs != null then obs.candidateExit(ref, span, res)

  /** No `try`: if code generation throws, the compilation is over anyway. */
  inline def genLoad(tree: tpd.Tree, method: MethodNode)(inline body: Unit)(using Context): Unit =
    val obs = ctx.base.observer
    if obs != null then obs.genLoadEnter(tree, method)
    body
    if obs != null then obs.genLoadExit(tree, method)
end TyperObserver
