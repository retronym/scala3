package dotty.tools
package dotc
package typer

import core.*
import Run.SubPhase
import Phases.*
import Contexts.*
import Symbols.*
import Names.*
import ast.untpd
import ImportInfo.withRootImports
import parsing.{Parser => ParserPhase}
import config.Printers.typr
import inlines.PrepareInlineable
import util.Stats.*
import dotty.tools.dotc.config.Feature
import dotty.tools.dotc.config.SourceVersion

/**
 *
 * @param addRootImports Set to false in the REPL. Calling [[ImportInfo.withRootImports]] on the [[Context]]
 *                       for each [[CompilationUnit]] causes dotty.tools.repl.ScriptedTests to fail.
 */
class TyperPhase(addRootImports: Boolean = true) extends Phase {

  override def phaseName: String = TyperPhase.name

  override def description: String = TyperPhase.description

  override def isTyper: Boolean = true


  override def allowsImplicitSearch: Boolean = true

  // Run regardless of parsing errors
  override def isRunnable(implicit ctx: Context): Boolean = true

  def enterSyms(using Context)(using subphase: SubPhase): Boolean = monitor(subphase.name) {
    val unit = ctx.compilationUnit
    ctx.typer.index(unit.untpdTree)
    typr.println("entered: " + unit.source)
  }

  def typeCheck(using Context)(using subphase: SubPhase): Boolean = monitor(subphase.name) {
    val unit = ctx.compilationUnit
    try
      if !unit.suspended then ctx.profiler.onUnit(ctx.phase, unit):
        unit.tpdTree = ctx.typer.typedExpr(unit.untpdTree)
        typr.println("typed: " + unit.source)
        record("retained untyped trees", unit.untpdTree.treeSize)
        record("retained typed trees after typer", unit.tpdTree.treeSize)
        ctx.run.nn.suppressions.reportSuspendedMessages(unit.source)
    catch case _: CompilationUnit.SuspendException => ()
  }

  def javaCheck(using Context)(using subphase: SubPhase): Boolean = monitor(subphase.name) {
    val unit = ctx.compilationUnit
    if unit.isJava then
      JavaChecks.check(unit.tpdTree)
  }

  /** With parallel readers (`-Yinteractive-parallel`, several files in one targeted run:
   *  `InteractiveDriver.openAll`), the files are indexed in parallel, each into a slice of its own for what it
   *  defines in a package (`Namer.IndexSlice`); the slices are then merged into the packages, serially and in file
   *  order. One difference from indexing serially: a top-level name defined in two of the files is reported at
   *  the merge, and the later definition left out rather than renamed.
   */
  private def indexInParallel(unitContexts: List[Context])(using Context)(using SubPhase): List[Context] =
    val lock = new Object
    val sliced = unitContexts.map(c => c.fresh.setProperty(Namer.IndexSliceKey, Namer.IndexSlice(c, lock)))
    val indexed = inParallel("dotc-index", byPackageInTurn(sliced))(enterSyms)
    ctx.typer.mergeIndexSlices(sliced.map(_.property(Namer.IndexSliceKey).get))
    // the files' own contexts go on to the typechecking, without the slices
    unitContexts.zip(sliced).collect { case (c, s) if indexed.exists(_ eq s) => c }

  /** The files taken one package at a time in turn (by the last name of the innermost package clause: a guess is
   *  enough). A file's package clause loads its package from the classpath (`PackageLoader`), and the other files
   *  in that package wait for the load: started on different packages, the threads run the loads side by side
   *  rather than queue on each in turn (on the compiler's own sources, 100 ms of indexing in parallel becomes 45).
   */
  private def byPackageInTurn(unitContexts: List[Context]): List[Context] =
    def packageOf(tree: untpd.Tree): Name = tree match
      case untpd.PackageDef(_, (nested: untpd.PackageDef) :: _) => packageOf(nested)
      case untpd.PackageDef(pid, _) => pid.name
      case _ => EmptyTermName
    val groups = unitContexts.groupBy(c => packageOf(c.compilationUnit.untpdTree)).values.toList
    groups.flatMap(_.zipWithIndex).sortBy(_._2).map(_._1)

  protected def discardAfterTyper(unit: CompilationUnit)(using Context): Boolean =
    (unit.isJava && !ctx.settings.XjavaTasty.value) || unit.suspended

  override val subPhases: List[SubPhase] = List(
    SubPhase("indexing"), SubPhase("typechecking"), SubPhase("checkingJava"))

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    val List(Indexing @ _, Typechecking @ _, CheckingJava @ _) = subPhases: @unchecked
    val unitContexts =
      for unit <- units yield
        val newCtx0 = ctx.fresh.setPhase(this.start).setCompilationUnit(unit)
        val newCtx = PrepareInlineable.initContext(newCtx0)
        report.inform(s"typing ${unit.source}")
        if (addRootImports)
          newCtx.withRootImports
        else
          newCtx

    val unitContexts0 = runSubPhase(Indexing) {
      if ctx.base.parallel && ctx.run.nn.isTargeted && unitContexts.lengthCompare(1) > 0 then
        indexInParallel(unitContexts)
      else
        for
          unitContext <- unitContexts
          if enterSyms(using unitContext)
        yield unitContext
    }

    ctx.base.parserPhase match {
      case p: ParserPhase =>
        if p.firstXmlPos.exists && !defn.ScalaXmlPackageClass.exists && Feature.sourceVersion == SourceVersion.future then
          report.error(
            """To support XML literals, your project must depend on scala-xml.
              |See https://github.com/scala/scala-xml for more information.""".stripMargin,
            p.firstXmlPos)
      case _ =>
    }

    val unitContexts1 = runSubPhase(Typechecking) {
      for
        unitContext <- unitContexts0
        if typeCheck(using unitContext)
      yield unitContext
    }

    record("total trees after typer", ast.Trees.ntrees)

    val unitContexts2 = runSubPhase(CheckingJava) {
      for
        unitContext <- unitContexts1
        if javaCheck(using unitContext) // after typechecking to avoid cycles
      yield unitContext
    }
    val newUnits = unitContexts2.map(_.compilationUnit).filterNot(discardAfterTyper)
    ctx.run.nn.checkSuspendedUnits(newUnits)
    newUnits

  protected def run(using Context): Unit = unsupported("run")
}

object TyperPhase {
  val name: String = "typer"
  val description: String = "type the trees"
}

@deprecated(message = "FrontEnd has been split into TyperPhase and Parser. Refer to one or the other.")
object FrontEnd {
  // For backwards compatibility: some plugins refer to FrontEnd so that they can schedule themselves after it.
  val name: String = TyperPhase.name
}
