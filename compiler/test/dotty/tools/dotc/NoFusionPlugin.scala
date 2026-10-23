package dotty.tools.dotc

import core.Contexts.Context
import core.Phases.Phase
import plugins.ResearchPlugin

/** Gives every phase of the plan a group of its own, so that no miniphases are fused
 *  into a MegaPhase. Fusion is an optimization only, so this should not change the output.
 */
class NoFusionPlugin extends ResearchPlugin:
  val name = "noFusion"
  override val description = "run each miniphase in a traversal of its own"

  def init(options: List[String], plan: List[List[Phase]])(using Context): List[List[Phase]] =
    plan.flatMap(_.map(List(_)))

object NoFusionPlugin:
  /** `-Xplugin` option for a directory holding just the descriptor. The class is loaded
   *  through the compiler's class loader, which also loads the compiler's tests.
   */
  lazy val option: String =
    val dir = java.nio.file.Files.createTempDirectory("noFusionPlugin")
    java.nio.file.Files.writeString(dir.resolve("plugin.properties"), s"pluginClass=${classOf[NoFusionPlugin].getName}\n")
    dir.toFile.deleteOnExit()
    s"-Xplugin:$dir"
