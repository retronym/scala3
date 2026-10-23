import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.plugins.ResearchPlugin

/** Every phase in a group of its own: no miniphases are fused into a MegaPhase. */
class UnfusedPhasesPlugin extends ResearchPlugin:
  val name: String = "unfusedPhases"
  override val description: String = "run each miniphase in a traversal of its own"
  def init(options: List[String], plan: List[List[Phase]])(using Context): List[List[Phase]] =
    plan.flatMap(_.map(List(_)))
