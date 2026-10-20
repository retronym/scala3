// Replayable compiler for nsc: a scalac 2.13 plugin that records a run as schema v1 JSONL (../../SCHEMA-v1.md).
// LLM-assisted (Claude), see LLM_POLICY.md. Prototype; lives in local/, not for upstream.
package replay.nsc

import scala.reflect.internal.util.Position
import scala.tools.nsc.Global
import scala.reflect.internal.Phase
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

/** Options (`-P:replay-record:<opt>`, repeatable):
 *   - `out=<file>`  JSONL output path (default `replay.jsonl`)
 *   - `nosyms`, `notext`, `noderive`, `noevents`, `nojvm`  switch off parts of the output
 *   - `check`       verify offsets against each node's own `-Xprint` text
 *   - `off`         load the plugin but record nothing (for byte-identity comparisons)
 *
 *  The plugin adds no phases: phase boundaries and every other hook come from the nsc agent
 *  (`-javaagent:replay-nsc-agent.jar`), see ../DESIGN.md. Without the agent `init` fails.
 */
class ReplayPlugin(val global: Global) extends Plugin {
  val name = "replay-record"
  val description = "record trees after every phase, typer events and bytecode, as v1 JSONL (needs the replay nsc agent)"
  val components: List[PluginComponent] = Nil

  override def init(options: List[String], error: String => Unit): Boolean = {
    if (options.contains("off")) return true
    val out = options.collectFirst { case s if s.startsWith("out=") => s.stripPrefix("out=") }.getOrElse("replay.jsonl")
    val jvmOpts =
      if (global.settings.YaddBackendThreads.value == 1) Nil
      else { global.reporter.warning(scala.reflect.internal.util.NoPosition, "replay-record: JVM recording needs -Ybackend-parallelism:1, skipping it"); List("nojvm") }
    val table =
      try replay.nsc.api.Hooks.verify(classOf[Global].getClassLoader)
      catch { case ex: Exception => error(s"replay-record: ${ex.getMessage}"); return false }
    if (sys.props.contains("replay.agent.table"))
      java.nio.file.Files.write(java.nio.file.Paths.get(sys.props("replay.agent.table")), table.getBytes("UTF-8"))
    val rec = new Recorder(global, out, options ++ jvmOpts)
    replay.nsc.api.Hooks.observer = new Bridge(rec)
    true
  }
}

object ReplayPlugin {
  def uninstall(rec: Recorder): Unit = replay.nsc.api.Hooks.observer match {
    case b: Bridge if b.rec eq rec => replay.nsc.api.Hooks.observer = null
    case _ =>
  }
}

/** The Object-typed bootstrap interface, cast back to the recorder's compiler types. */
final class Bridge(val rec: Recorder) extends replay.nsc.api.Observer {
  private val g: rec.global.type = rec.global
  import g._

  def phaseBegin(phase: Object): Unit = rec.phaseBegin(phase.asInstanceOf[Phase])
  def phaseEnd(phase: Object): Unit = rec.phaseEnd(phase.asInstanceOf[Phase])
  def runEnd(): Unit = rec.runEnd()

  def typedEnter(typer: Object, tree: Object, mode: Int, pt: Object): Unit =
    rec.typedEnter(typer.asInstanceOf[analyzer.Typer], tree.asInstanceOf[Tree], mode, pt.asInstanceOf[Type])
  def typedExit(typer: Object, tree: Object, mode: Int, pt: Object, res: Object): Unit =
    rec.typedExit(typer.asInstanceOf[analyzer.Typer], tree.asInstanceOf[Tree], mode, pt.asInstanceOf[Type], res.asInstanceOf[Tree])
  def adaptEnter(typer: Object, tree: Object, mode: Int, pt: Object): Unit =
    rec.adaptEnter(typer.asInstanceOf[analyzer.Typer], tree.asInstanceOf[Tree], mode, pt.asInstanceOf[Type])
  def adaptExit(typer: Object, tree: Object, mode: Int, pt: Object, res: Object): Unit =
    rec.adaptExit(typer.asInstanceOf[analyzer.Typer], tree.asInstanceOf[Tree], mode, pt.asInstanceOf[Type], res.asInstanceOf[Tree])

  def completeEnter(completer: Object, sym: Object): Unit = rec.completeEnter(sym.asInstanceOf[Symbol])
  def completeExit(completer: Object, sym: Object, ok: Boolean): Unit = rec.completeExit(sym.asInstanceOf[Symbol], ok)

  def implicitSearchEnter(search: Object): Unit = rec.implicitSearchEnter(search.asInstanceOf[analyzer.ImplicitSearch])
  def implicitSearchExit(search: Object, res: Object): Unit =
    rec.implicitSearchExit(search.asInstanceOf[analyzer.ImplicitSearch], res.asInstanceOf[analyzer.SearchResult])
  def candidateEnter(search: Object, info: Object): Unit =
    rec.candidateEnter(search.asInstanceOf[analyzer.ImplicitSearch], info.asInstanceOf[analyzer.ImplicitInfo])
  def candidateExit(search: Object, info: Object, res: Object): Unit =
    rec.candidateExit(search.asInstanceOf[analyzer.ImplicitSearch], info.asInstanceOf[analyzer.ImplicitInfo], res.asInstanceOf[analyzer.SearchResult])

  def report(reporter: Object, pos: Object, msg: String, severity: Object): Unit =
    rec.report(reporter, pos.asInstanceOf[Position], msg, severity.asInstanceOf[scala.reflect.internal.Reporter.Severity].id)
  def bufferedReport(err: Object, context: Object): Unit = {
    val e = err.asInstanceOf[analyzer.AbsTypeError]
    rec.bufferedReport(e.errPos, e.errMsg)
  }
  def copied(from: Object, copy: Object): Unit = rec.copied(from.asInstanceOf[Tree], copy.asInstanceOf[Tree])

  private val jvm: JvmRecorder = rec.jvm
  def classNode(cd: Object, cn: Object): Unit =
    if (jvm != null) jvm.classNode(cd.asInstanceOf[jvm.global.ClassDef], cn.asInstanceOf[scala.tools.asm.tree.ClassNode])
  def mirrorNode(sym: Object, cn: Object): Unit =
    if (jvm != null) jvm.mirrorNode(sym.asInstanceOf[jvm.global.Symbol], cn.asInstanceOf[scala.tools.asm.tree.ClassNode])
  def genMethod(dd: Object, mn: Object): Unit =
    if (jvm != null) jvm.genMethod(dd.asInstanceOf[jvm.global.DefDef], mn.asInstanceOf[scala.tools.asm.tree.MethodNode])
  def genLoadEnter(tree: Object, mn: Object): Unit =
    if (jvm != null) jvm.genLoadEnter(tree.asInstanceOf[jvm.global.Tree], mn.asInstanceOf[scala.tools.asm.tree.MethodNode])
  def genLoadExit(tree: Object, mn: Object): Unit =
    if (jvm != null) jvm.genLoadExit(tree.asInstanceOf[jvm.global.Tree], mn.asInstanceOf[scala.tools.asm.tree.MethodNode])
  def optimizerPass(owner: String, mn: Object, pass: String): Unit =
    if (jvm != null) jvm.optimizerPass(owner, mn.asInstanceOf[scala.tools.asm.tree.MethodNode], pass)
  def inlined(callsite: Object, map: Object): Unit =
    if (jvm != null) jvm.inlined(callsite, map.asInstanceOf[Map[scala.tools.asm.tree.AbstractInsnNode, scala.tools.asm.tree.AbstractInsnNode]])
  def classWritten(cn: Object): Unit =
    if (jvm != null) jvm.classWritten(cn.asInstanceOf[scala.tools.asm.tree.ClassNode])
}
