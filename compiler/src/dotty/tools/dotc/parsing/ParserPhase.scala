package dotty.tools.dotc.parsing

import dotty.tools.dotc.config.Config
import dotty.tools.dotc.core.Contexts.{ Context, ctx }
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.typer.ImportInfo.withRootImports
import dotty.tools.dotc.{ CompilationUnit, ast, report }
import dotty.tools.dotc.util.{ NoSourcePosition, SourcePosition }
import dotty.tools.dotc.util.Stats.record
import dotty.tools.unsupported

class Parser extends Phase {

  override def phaseName: String = Parser.name
  override def description: String = Parser.description

  // We run TreeChecker only after type checking
  override def isCheckable: Boolean = false

  /** The position of the first XML literal encountered while parsing,
   *  NoSourcePosition if there were no XML literals.
   */
  private[dotc] var firstXmlPos: SourcePosition = NoSourcePosition

  def parse(using Context): Boolean = monitor("parser") {
    val unit = ctx.compilationUnit
    unit.untpdTree =
      if (unit.isJava) new JavaParsers.JavaParser(unit.source).parse()
      else {
        val p = new Parsers.Parser(unit.source)
        //  p.in.debugTokenStream = true
        val tree = p.parse()
        ctx.compilationUnit.comments = p.in.comments
        if p.firstXmlPos.exists then synchronized {
          if !firstXmlPos.exists then firstXmlPos = p.firstXmlPos
        }
        tree
      }
    if (Config.checkPositions)
      unit.untpdTree.checkPos(nonOverlapping = !unit.isJava && !ctx.reporter.hasErrors)
  }


  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] = {
    val unitContexts =
      for unit <- units yield
        report.inform(s"parsing ${unit.source}")
        ctx.fresh.setCompilationUnit(unit).withRootImports

    val unitContexts0 =
      if ctx.base.independent && unitContexts.lengthCompare(1) > 0 then parseInParallel(unitContexts)
      else
        for
          unitContext <- unitContexts
          if parse(using unitContext)
        yield unitContext

    record("parsedTrees", ast.Trees.ntrees)

    unitContexts0.map(_.compilationUnit)
  }

  /** With independent readers (`-Yinteractive-readers`, several files in one
   *  run: `InteractiveDriver.openAll`), the files are parsed in parallel: a
   *  parse reads only its own source, and what it shares (the name table, the
   *  run's reporter) is synchronised on a concurrent base. The first failure
   *  is rethrown here, after every parse has ended.
   */
  private def parseInParallel(unitContexts: List[Context]): List[Context] =
    import java.util.concurrent.{Callable, Executors}
    val threads = math.min(Runtime.getRuntime.availableProcessors, unitContexts.length)
    val pool = Executors.newFixedThreadPool(threads, r => { val t = new Thread(r, "dotc-parse"); t.setDaemon(true); t }).nn
    try
      val futures = unitContexts.map(c => pool.submit((() => parse(using c)): Callable[Boolean]).nn)
      val parsed = futures.map(f =>
        try f.get().nn.booleanValue
        catch case ex: java.util.concurrent.ExecutionException => throw ex.getCause.nn)
      unitContexts.zip(parsed).collect { case (c, true) => c }
    finally pool.shutdown()

  protected def run(using Context): Unit = unsupported("run")
}

object Parser{
  val name: String = "parser"
  val description: String = "scan and parse sources"
}
