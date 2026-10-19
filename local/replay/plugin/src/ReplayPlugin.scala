// Replayable compiler: research plugin that records a dotc run as schema v1 JSONL (../SCHEMA-v1.md).
// LLM-assisted (Claude), see LLM_POLICY.md. Prototype; lives in local/, not for upstream.
package replay

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.plugins.ResearchPlugin
import dotty.tools.dotc.transform.MegaPhase
import dotty.tools.dotc.CompilationUnit

/** Options (`-P:replay-record:<opt>`, repeatable):
 *   - `out=<file>`  JSONL output path (default `replay.jsonl`)
 *   - `fused`       keep the standard MegaPhase grouping (default: unfuse every MiniPhase,
 *                   except ranges named by `keepFused=a..b`, default `mixin..capturedVars`)
 *   - `norecord`    only rewrite the plan: no Record phases, no observer
 *   - `nosyms`, `notext`, `noderive`, `noevents`, `notypetext`, `nojvm`  switch off parts of the output
 *   - `check`       verify offsets against each node's own `show`
 *   - `showplan`    print the resulting phase plan to stderr
 *
 *  The recorder is installed as `ctx.base.observer` here, in `init`, which runs once per Run before
 *  any phase, so typer events from the first phase on share the arena with the Record phases.
 */
class ReplayPlugin extends ResearchPlugin:
  val name = "replay-record"
  val description = "unfuse MegaPhases and record trees after every phase, plus typer events, as v1 JSONL"

  def init(options: List[String], plan: List[List[Phase]])(using Context): List[List[Phase]] =
    val unfuse = !options.contains("fused")
    val record = !options.contains("norecord")
    val groupName: Map[Phase, String] =
      plan.filter(_.length > 1).flatMap(g => g.map(_ -> g.map(_.phaseName).mkString("MegaPhase{", ", ", "}"))).toMap
    // Ranges of miniphases that stay fused even when unfusing (fusion-order bugs, see Track A's REPORT.md).
    val keep: List[(String, String)] =
      val named = options.collect { case s if s.startsWith("keepFused=") => s.stripPrefix("keepFused=") }
      (if named.isEmpty then List("mixin..capturedVars") else named).filter(_ != "none").map { r =>
        val Array(a, b) = r.split("\\.\\.", 2): @unchecked
        (a, b)
      }
    def split(g: List[Phase]): List[List[Phase]] =
      val names = g.map(_.phaseName)
      keep.collectFirst { case (a, b) if names.contains(a) && names.contains(b) => (names.indexOf(a), names.indexOf(b)) } match
        case Some((i, j)) if i < j => g.take(i).map(List(_)) ::: List(g.slice(i, j + 1)) ::: split(g.drop(j + 1))
        case _ => g.map(List(_))
    val groups = if unfuse then plan.flatMap(split) else plan
    val newPlan =
      if !record then groups
      else
        // Experiment: consume N identity hashes, to test that the stream does not depend on the JVM's hash sequence
        val perturb = System.getenv("REPLAY_PERTURB_IDHASH")
        if perturb != null then for _ <- 0 until perturb.toInt do System.identityHashCode(new Object)
        val out = options.collectFirst { case s if s.startsWith("out=") => s.stripPrefix("out=") }.getOrElse("replay.jsonl")
        // the backend hooks without a Context (optimiser, class writer) must run on this thread
        val jvmOpts =
          if ctx.settings.YbackendParallelism.value == 1 then Nil
          else
            dotty.tools.dotc.report.warning("replay-record: JVM recording needs -Ybackend-parallelism:1, skipping it")
            List("nojvm")
        val rec = Recorder(out, groupName, options ++ jvmOpts)
        ctx.base.observer = rec
        val n = groups.length
        groups.zipWithIndex.flatMap((g, i) => List(g, List(RecordPhase(rec, g, i == n - 1))))
    // -Vphases prints `new Compiler().phases`, ignoring plugins, so print the plan we hand back.
    if options.contains("showplan") then
      for g <- newPlan do
        System.err.println(if g.length == 1 then g.head.phaseName else g.map(_.phaseName).mkString("{ ", ", ", " }"))
    newPlan

/** Runs after `group`; records each unit's tree as seen by the following phase. The recorder
 *  suspends event recording while a RecordPhase runs (it sees its PhaseBegin/End).
 */
class RecordPhase(rec: Recorder, group: List[Phase], isLast: Boolean) extends Phase:
  val phaseName = "record:" + group.last.phaseName
  override def description = "replay: record trees after " + group.map(_.phaseName).mkString(",")
  override def isCheckable = false
  override def skipIfJava(using Context) = false
  // `isRunnable` is left at the default (stop after errors), matching -Xprint.

  /** The group may have been dropped by fusePhases (disabled / -Yskip / -Ystop-before). */
  private def groupIsLive(using Context): Boolean =
    val ps = flatPhases
    group.exists(g => ps.exists(_ eq g))

  override def runOn(units: List[CompilationUnit])(using runCtx: Context): List[CompilationUnit] =
    if !groupIsLive then units
    else
      val res = super.runOn(units)
      if isLast then rec.close()
      res

  protected def run(using Context): Unit =
    val live = flatPhases
    rec.recordUnit(rec.groupOf(group.filter(g => live.exists(_ eq g)).head), ctx.compilationUnit)
end RecordPhase

/** The phases of this run with MegaPhases expanded, as `base.phases` (private[dotc]) has them. */
def flatPhases(using Context): List[Phase] =
  ctx.base.allPhases.toList.flatMap:
    case m: MegaPhase => m.miniPhases.toList
    case p => List(p)
