package dotty.tools.dotc
package plugins

import core.Contexts.{Context, ContextBase}
import core.Phases.Phase

/** A research plugin that gives every phase of the plan a group of its own, so that no
 *  miniphases are fused into a MegaPhase. Fusion is an optimization: the compilation result
 *  must not change, up to the choice of fresh names and the order of synthetic members. The
 *  fused/unfused cross-check (`FusionCrossCheck`) asks for the same output byte for byte, a
 *  stronger property that is simpler to test.
 *
 *  Research plugins run only on snapshot and nightly builds of the compiler (all test builds).
 *  Tests install it in the context base (`UnfusedPhases.contextBase`, `UnfusedPhases.driver`)
 *  rather than through `-Xplugin`, so that no plugin jar is needed.
 */
class UnfusedPhases extends ResearchPlugin:
  val name: String = "unfusedPhases"
  override val description: String = "run each miniphase in a traversal of its own"
  def init(options: List[String], plan: List[List[Phase]])(using Context): List[List[Phase]] =
    plan.flatMap(_.map(List(_)))

object UnfusedPhases:
  /** A context base whose plugins are this one only. */
  def contextBase: ContextBase = new ContextBase:
    override protected def loadRoughPluginsList(using Context): List[Plugin] = List(UnfusedPhases())

  /** A driver whose runs are unfused. */
  def driver: Driver = new Driver:
    override protected def initCtx: Context = contextBase.initialCtx
