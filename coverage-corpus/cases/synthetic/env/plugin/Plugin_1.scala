//> using dep @compiler
// Stage 1: a standard compiler plugin, loaded by stage 2 with -Xplugin.
import dotty.tools.dotc.*
import core.*
import Contexts.*
import Symbols.*
import plugins.*
import ast.tpd
import transform.MegaPhase.MiniPhase

class CountPlugin extends StandardPlugin:
  val name = "count"
  override val description = "counts applications"
  override val optionsHelp = Some("-P:count:verbose  log counts")

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    List(CountPhase(options.contains("verbose")))

class CountPhase(verbose: Boolean) extends PluginPhase:
  import tpd.*
  val phaseName = "count"
  override val runsAfter = Set(transform.Pickler.name)
  override val runsBefore = Set(transform.Staging.name)
  private var applies = 0

  override def transformApply(tree: Apply)(using Context): Tree =
    applies += 1
    tree

  override def transformDefDef(tree: DefDef)(using Context): Tree =
    if tree.symbol.hasAnnotation(requiredClass("scala.deprecated")) then
      report.warning("deprecated definition", tree.srcPos)
    tree

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    val res = super.runOn(units)
    if verbose then report.echo(s"$applies applications")
    res
