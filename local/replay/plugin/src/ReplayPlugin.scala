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
 *   - `selfattach=<replay-agent.jar>[;<agent opts>]`  agent build only: attach the hook agent from here
 *
 *  The recorder is installed here (`Install`: fork or agent delivery), in `init`, which runs once per Run before
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
    // Every miniphase and every Record phase takes a phase id. Before 3.8 there are only 127 ids
    // (Periods.PhaseWidth = 7): too few for a Record phase per miniphase, or even per fused group.
    // Then unfuse MegaPhases from the front of the plan while the ids last, and if a Record phase per group
    // still does not fit, record after fewer groups (see `recordAfter`). (An inline val, so this is the
    // limit of the compiler the plugin was compiled against.)
    val maxIds = dotty.tools.dotc.core.Periods.MaxPossiblePhaseId - 1 // the terminal phase takes one more
    def ids(gs: List[List[Phase]]) = gs.map(_.length).sum + (if record then gs.length else 0)
    val unfused = if unfuse then plan.flatMap(split) else plan
    val groups =
      if ids(unfused) <= maxIds then unfused
      else
        var spare = maxIds - ids(plan)
        var n = 0
        val gs = plan.flatMap { g =>
          val s = split(g)
          val more = ids(s) - ids(List(g))
          if s.length > 1 && more <= spare then { spare -= more; n += 1; s } else List(g)
        }
        dotty.tools.dotc.report.warning(
          s"replay-record: unfusing every MegaPhase needs ${ids(unfused)} phase ids, this compiler has $maxIds; " +
          s"unfused the first $n of ${plan.count(_.length > 1)} MegaPhases")
        gs
    // The groups followed by a Record phase. A Record phase after a group records the trees after it and
    // after every group since the previous Record phase.
    val recordAfter: Set[Int] =
      val all = groups.indices.toSet
      val budget = maxIds - groups.map(_.length).sum
      if !record || all.size <= budget then all
      else
        // Keep: the last group (closes the recorder), MegaPhases, the single phases most likely to change
        // trees in this order, then the others from the front, while the budget lasts.
        val preferred = List("typer", "inlining", "posttyper", "staging", "splicing", "pickleQuotes", "postInlining",
          "erasure", "genBCode")
        def rank(i: Int) =
          val g = groups(i)
          if i == groups.length - 1 then 0
          else if g.length > 1 then 1
          else preferred.indexOf(g.head.phaseName) match
            case -1 => 100 + i // then the rest, from the front
            case k => 2 + k
        val kept = groups.indices.sortBy(rank).take(budget).toSet
        dotty.tools.dotc.report.warning(
          s"replay-record: ${groups.length} groups but phase ids for only $budget Record phases; recording after " +
          groups.indices.filter(kept).map(i => groups(i).last.phaseName).mkString(", "))
        kept
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
        Install.install(rec, options)
        val n = groups.length
        var segment = List.empty[Phase] // the phases since the last Record phase
        groups.zipWithIndex.flatMap: (g, i) =>
          segment = segment ::: g
          if !recordAfter(i) then List(g)
          else
            val seg = segment
            segment = Nil
            List(g, List(RecordPhase(rec, seg, i == n - 1)))
    // -Vphases prints `new Compiler().phases`, ignoring plugins, so print the plan we hand back.
    if options.contains("showplan") then
      for g <- newPlan do
        System.err.println(if g.length == 1 then g.head.phaseName else g.map(_.phaseName).mkString("{ ", ", ", " }"))
    newPlan

/** Runs after `group` (the phases since the previous RecordPhase); records each unit's tree as seen by the following phase. The recorder
 *  suspends event recording while a RecordPhase runs (it sees its PhaseBegin/End).
 */
class RecordPhase(rec: Recorder, group: List[Phase], isLast: Boolean) extends CompatPhase:
  val phaseName = "record:" + group.last.phaseName
  override def description = "replay: record trees after " + group.map(_.phaseName).mkString(",")
  override def isCheckable = false
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

  def run(using Context): Unit = // public: Phase.run is public before 3.8
    val live = flatPhases
    rec.recordUnit(rec.groupOf(group.filter(g => live.exists(_ eq g)).last), ctx.compilationUnit)
end RecordPhase

/** The phases of this run with MegaPhases expanded, as `base.phases` (private[dotc]) has them. */
def flatPhases(using Context): List[Phase] =
  ctx.base.allPhases.toList.flatMap:
    case m: MegaPhase => m.miniPhases.toList
    case p => List(p)
