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

/** Hook delivery by the bytecode agent (../agent): the observer goes into the bootstrap-loaded
 *  `replay.agent.api.Hooks.observer`, which the inlined advice reads.
 *
 *  `-P:replay-record:selfattach=<replay-agent.jar>[;<agent opts>]` attaches the agent from here
 *  (Byte Buddy's `ByteBuddyAgent.install`) instead of `-javaagent`. Nothing in this object names
 *  `replay.agent.api` before the attach has appended the API jar to the bootstrap path.
 */
object Install:
  def install(o: Observer, options: List[String])(using Context): Unit =
    options.collectFirst { case s if s.startsWith("selfattach=") => s.stripPrefix("selfattach=") }.foreach(selfAttach)
    AgentInstall.install(o)

  def uninstall(o: Observer)(using Context): Unit = AgentInstall.uninstall(o)

  private def selfAttach(spec: String): Unit =
    val (jar, opts) = spec.split(";", 2) match
      case Array(j, o) => (j, o)
      case Array(j) => (j, "")
    val t0 = System.nanoTime()
    val inst = net.bytebuddy.agent.ByteBuddyAgent.install()
    val dir = java.io.File(jar).getAbsoluteFile.getParentFile
    inst.appendToBootstrapClassLoaderSearch(java.util.jar.JarFile(java.io.File(dir, "replay-agent-api.jar")))
    inst.appendToSystemClassLoaderSearch(java.util.jar.JarFile(java.io.File(dir, "byte-buddy.jar")))
    inst.appendToSystemClassLoaderSearch(java.util.jar.JarFile(jar))
    Class.forName("replay.agent.ReplayAgent", true, ClassLoader.getSystemClassLoader)
      .getMethod("agentmain", classOf[String], classOf[java.lang.instrument.Instrumentation])
      .invoke(null, opts, inst)
    System.err.println(f"[replay] self-attached agent in ${(System.nanoTime() - t0) / 1e6}%.0f ms")

/** Separate object, so `replay.agent.api` is resolved only after a self-attach. */
object AgentInstall:
  def install(o: Observer)(using Context): Unit =
    val table = replay.agent.api.Hooks.verify(classOf[Context].getClassLoader)
    if sys.props.contains("replay.agent.table") then
      java.nio.file.Files.writeString(java.nio.file.Path.of(sys.props("replay.agent.table")), table)
    replay.agent.api.Hooks.observer = AgentBridge(o)

  def uninstall(o: Observer): Unit = replay.agent.api.Hooks.observer match
    case b: AgentBridge if b.o eq o => replay.agent.api.Hooks.observer = null
    case _ =>

/** The Object-typed bootstrap interface, cast back to compiler types. */
final class AgentBridge(val o: Observer) extends replay.agent.api.Observer:
  private inline def c(ctx: Object): Context = ctx.asInstanceOf[Context]
  def phaseBegin(phase: Object, ctx: Object) = o.phaseBegin(phase.asInstanceOf[Phase])(using c(ctx))
  def phaseEnd(phase: Object, units: Object, ctx: Object) =
    o.phaseEnd(phase.asInstanceOf[Phase], units.asInstanceOf[List[CompilationUnit]])(using c(ctx))
  def typedEnter(tree: Object, pt: Object, ctx: Object) = o.typedEnter(tree.asInstanceOf[untpd.Tree], pt.asInstanceOf[Type])(using c(ctx))
  def typedExit(tree: Object, pt: Object, res: Object, ctx: Object) =
    o.typedExit(tree.asInstanceOf[untpd.Tree], pt.asInstanceOf[Type], res.asInstanceOf[tpd.Tree | Null])(using c(ctx))
  def adaptEnter(tree: Object, pt: Object, ctx: Object) = o.adaptEnter(tree.asInstanceOf[tpd.Tree], pt.asInstanceOf[Type])(using c(ctx))
  def adaptExit(tree: Object, pt: Object, res: Object, ctx: Object) =
    o.adaptExit(tree.asInstanceOf[tpd.Tree], pt.asInstanceOf[Type], res.asInstanceOf[tpd.Tree | Null])(using c(ctx))
  def completeEnter(denot: Object, ctx: Object) = o.completeEnter(denot.asInstanceOf[SymDenotation])(using c(ctx))
  def completeExit(denot: Object, ok: Boolean, ctx: Object) = o.completeExit(denot.asInstanceOf[SymDenotation], ok)(using c(ctx))
  def implicitSearchEnter(pt: Object, arg: Object, span: Long, ctx: Object) =
    o.implicitSearchEnter(pt.asInstanceOf[Type], arg.asInstanceOf[tpd.Tree], Span(span))(using c(ctx))
  def implicitSearchExit(pt: Object, arg: Object, span: Long, res: Object, ctx: Object) =
    o.implicitSearchExit(pt.asInstanceOf[Type], arg.asInstanceOf[tpd.Tree], Span(span), res.asInstanceOf[SearchResult | Null])(using c(ctx))
  def candidateEnter(cand: Object, span: Long, ctx: Object) = o.candidateEnter(cand.asInstanceOf[Candidate].ref, Span(span))(using c(ctx))
  def candidateExit(cand: Object, span: Long, res: Object, ctx: Object) =
    o.candidateExit(cand.asInstanceOf[Candidate].ref, Span(span), res.asInstanceOf[SearchResult | Null])(using c(ctx))
  def report(dia: Object, reporter: Object, ctx: Object) = o.report(dia.asInstanceOf[Diagnostic], reporter.asInstanceOf[Reporter])(using c(ctx))
  def copied(tree: Object, copy: Object) = o.copied(tree.asInstanceOf[Trees.Tree[?]], copy.asInstanceOf[Trees.Tree[?]])
  def rejected(ref: Object, reason: String, ctx: Object) =
    val r = ref match
      case cand: Candidate => cand.ref
      case r: TermRef => r
    o.rejected(r, reason)(using c(ctx))
  def scopeEnter(eligible: Object, contextual: Boolean, ctx: Object) =
    o.scopeEnter(eligible.asInstanceOf[List[Candidate]], contextual)(using c(ctx))
  def scopeExit(eligible: Object, contextual: Boolean, res: Object, ctx: Object) =
    o.scopeExit(eligible.asInstanceOf[List[Candidate]], contextual, res.asInstanceOf[SearchResult | Null])(using c(ctx))
  def rankStep(pending: Object, found: Object, ctx: Object) =
    o.rankStep(pending.asInstanceOf[List[Candidate]], found.asInstanceOf[SearchResult])(using c(ctx))
  def tentativeEnter(ctx: Object) = o.tentativeEnter()(using c(ctx))
  def tentativeExit(threw: Boolean, ctx: Object) = o.tentativeExit(threw)(using c(ctx))
  def tentativeContext(fresh: Object) = o.tentativeState(fresh.asInstanceOf[Context].typerState)
  def typerStateCommit(ts: Object) = o.commit(ts.asInstanceOf[TyperState])
  def bufferedReport(dia: Object, reporter: Object, ctx: Object) =
    o.bufferedReport(dia.asInstanceOf[Diagnostic], reporter.asInstanceOf[Reporter])(using c(ctx))

  // JVM backend
  def classNodes(typeDef: Object, nodes: Object, ctx: Object) =
    nodes.asInstanceOf[List[(ClassNode, ?)]] match
      case Nil =>
      case main :: rest => o.classNodes(typeDef.asInstanceOf[tpd.TypeDef], main._1, rest.headOption.map(_._1).orNull)(using c(ctx))
  def genMethod(dd: Object, method: Object, ctx: Object) =
    o.genMethod(dd.asInstanceOf[tpd.DefDef], method.asInstanceOf[MethodNode])(using c(ctx))
  def genLoadEnter(tree: Object, method: Object, ctx: Object) =
    o.genLoadEnter(tree.asInstanceOf[tpd.Tree], method.asInstanceOf[MethodNode])(using c(ctx))
  def genLoadExit(tree: Object, method: Object, ctx: Object) =
    o.genLoadExit(tree.asInstanceOf[tpd.Tree], method.asInstanceOf[MethodNode])(using c(ctx))
  def optimizerPass(owner: String, method: Object, pass: String) = o.optimizerPass(owner, method.asInstanceOf[MethodNode], pass)
  def inlined(method: Object, callsite: Object, insnMap: Object) =
    val (owner, calleeOwner, callee) = Compat.callsite(callsite)
    o.inlined(owner, method.asInstanceOf[MethodNode], calleeOwner, callee, insnMap.asInstanceOf[Map[AbstractInsnNode, AbstractInsnNode]])
  def classWritten(classNode: Object) = o.classWritten(classNode.asInstanceOf[ClassNode])
