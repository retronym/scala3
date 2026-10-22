package dotty.tools
package dotc
package interactive

import java.net.URI
import scala.collection.*

import dotty.tools.dotc.sbt.interfaces.ProgressCallback

import ast.{Trees, tpd, untpd}
import config.*
import core.*, core.Decorators.*
import Contexts.*, Names.*, NameOps.*, Symbols.*, SymDenotations.*, Trees.*, Types.*
import reporting.*
import util.*
import Spans.{Span, NoSpan}

private class InteractiveContextBase(sourcePackage: CachedLogicalPackage) extends ContextBase {

  override protected def newPlatform(using Context): Platform = {
      if (settings.scalajs.value) new SJSPlatform(sourcePackage.value)
      else new JavaPlatform(sourcePackage.value)
  }

}

// Assumes the context does not change; only use if this is true!
class CachedLogicalPackage(extractor: Context ?=> Option[LogicalPackage]) {
  private var cached: Option[LogicalPackage] | Null = null
  def value(using Context): Option[LogicalPackage] =
    initialize(cached, cached = _, extractor)
}

object CachedLogicalPackage:
  val none: CachedLogicalPackage = CachedLogicalPackage(_ ?=> None)

/** A Driver subclass designed to be used from IDEs */
class InteractiveDriver(
    val settings: List[String],
    logicalRootPackage: CachedLogicalPackage
) extends Driver {
  import tpd.*

  override protected def initCtx: Context =
    new InteractiveContextBase(logicalRootPackage).initialCtx

  override def sourcesRequired: Boolean = false

  /** Cancellation of the current request. `myCancelled` is set by `cancel()`
   *  (which `Run.Progress` calls when it notices a thread interrupt) and stays
   *  set until the next run starts. `myCancelChecker` is the client's own
   *  cancellation test, installed for the duration of a request by
   *  `withCancelChecker`. `isCancelled` does not consume the interrupt flag
   *  itself: `Run.Progress.checkCancellation` already does, and it would hide
   *  the interrupt from `cancel()`.
   */
  @volatile private var myCancelled = false
  @volatile private var myCancelChecker: () => Boolean = InteractiveDriver.neverCancelled

  private val myProgressCallback: ProgressCallback = new ProgressCallback:
    override def isCancelled(): Boolean = myCancelled || myCancelChecker()
    override def cancel(): Unit = myCancelled = true

  /** Run `body` with `check` as the cancellation test polled by the typer
   *  (see `Run.checkCancelledTyping`). A run started inside `body` is aborted
   *  with `CompilationUnit.CancelledCompilation` once `check` returns true.
   */
  def withCancelChecker[T](check: () => Boolean)(body: => T): T =
    val previous = myCancelChecker
    myCancelChecker = check
    try body
    finally myCancelChecker = previous

  private val myInitCtx: Context = {
    val rootCtx = initCtx.fresh.addMode(Mode.ReadPositions).addMode(Mode.Interactive)
    rootCtx.setSetting(rootCtx.settings.YretainTrees, true)
    rootCtx.setSetting(rootCtx.settings.XcookComments, true)
    rootCtx.setSetting(rootCtx.settings.XreadComments, true)
    val ctx = setup(settings.toArray, rootCtx) match
      case Some((_, ctx)) => ctx
      case None => rootCtx
    ctx.initialize()(using ctx)
    ctx
  }

  private var myCtx: Context = myInitCtx
  def currentCtx: Context = myCtx

  private val compiler: Compiler = new InteractiveCompiler

  private val myOpenedFiles = new mutable.LinkedHashMap[URI, SourceFile].withDefaultValue(NoSource)
  def openedFiles: Map[URI, SourceFile] = myOpenedFiles

  private val myOpenedTrees = new mutable.LinkedHashMap[URI, List[SourceTree]].withDefaultValue(Nil)
  def openedTrees: Map[URI, List[SourceTree]] = myOpenedTrees

  private val myCompilationUnits = new mutable.LinkedHashMap[URI, CompilationUnit]
  def compilationUnits: Map[URI, CompilationUnit] = myCompilationUnits

  initialize()

  def run(uri: URI, sourceCode: String): List[Diagnostic] = run(uri, SourceFile.virtual(uri, sourceCode))

  def run(uri: URI, source: SourceFile): List[Diagnostic] = run(uri, source, NoSpan)

  /** The state of a targeted run (see `runUpTo`): the run, the unit with
   *  the partially typed tree, the statements the run skipped (with the
   *  context to type them in), and the diagnostics so far.
   */
  private class Session(val uri: URI, val source: SourceFile, val run: Run, val reporter: StoreReporter,
      val unit: CompilationUnit):
    var skipped: List[Run.SkippedStat] = Nil
    var diagnostics: List[Diagnostic] = Nil
    /** Set when the typing of a skipped statement was aborted and the statement
     *  could not be replaced: typing consumes the attachments of untyped trees
     *  (`SymOfTree`, `TypedAhead`, `ExpandedTree`), so it can not be typed a
     *  second time, and the next request starts a fresh run.
     */
    var poisoned: Boolean = false
    def skippedAt(target: Span): Option[Run.SkippedStat] = skipped.find(_.stat.span.contains(target))

  private var mySession: Session | Null = null

  /** The number of statements of the outermost package typed by the targeted
   *  run of `uri` (an object counts as its module val and class), or -1 if
   *  `uri` is not the subject of a targeted run. The typed statements are
   *  those that contained the targets of the requests so far, not a prefix.
   */
  def typedPrefixLength(uri: URI): Int = mySession match
    case s: Session if s.uri == uri =>
      // through a package clause that wraps the whole file
      def count(t: Tree): Int = t match
        case PackageDef(_, (inner: PackageDef) :: Nil) => count(inner)
        case PackageDef(_, stats) => stats.length
        case _ => 0
      count(s.unit.tpdTree)
    case _ => -1

  /** Is the targeted run of `uri` tainted: did an exception of unknown effect
   *  unwind through a completion (see `Run.isTainted`)? The next request on
   *  it starts a fresh run.
   */
  def isTainted(uri: URI): Boolean = mySession match
    case s: Session if s.uri == uri => s.run.isTainted
    case _ => false

  /** The spans of the statements the targeted run of `uri` has not typed. */
  def untypedSpans(uri: URI): List[Span] = mySession match
    case s: Session if s.uri == uri => s.skipped.map(_.stat.span)
    case _ => Nil

  /** Has the targeted run of `uri` typed all of its statements? */
  def isFullyTyped(uri: URI): Boolean = mySession match
    case s: Session if s.uri == uri => s.skipped.isEmpty
    case _ => false

  /** The id of the run that typed `uri`, or -1. */
  def runIdOf(uri: URI): Int = mySession match
    case s: Session if s.uri == uri => s.run.runContext.runId
    case _ => -1

  /** Type `source` only along the path to `target`: of the statements of
   *  the outermost package and of each template on the path, only the one
   *  containing the target, as scalac's `typedStats` does for its target
   *  position. Or resume the previous targeted run of `uri` if it was on the
   *  same content: a target within what is typed is served without typing
   *  anything, a target within a skipped statement types that statement in
   *  the same run and context, so that the denotations and completers of
   *  everything else stay valid. Any other content starts a fresh targeted
   *  run. The compilation unit of `uri` then holds the typed part of the
   *  tree, without the skipped statements; providers that need the whole
   *  tree use `run` instead.
   */
  def runUpTo(uri: URI, source: SourceFile, target: Span): List[Diagnostic] =
    mySession match
      case s: Session if s.uri == uri && s.source.textContent() == source.textContent() =>
        // A run that had a completion unwound by an exception of unknown
        // effect (not a cycle, not a cancellation) holds state nothing should
        // be served or resumed from: the session is tainted, and the request
        // starts a fresh run, which is cheap for a targeted run.
        // A poisoned session holds a statement that is neither typed nor
        // skipped (its typing was aborted and it could not be replaced): a
        // request anywhere starts a fresh run, since a request in that
        // statement's range would otherwise be served from the tree without
        // it (the hover found the package).
        if s.run.isTainted || s.poisoned then run(uri, source, target)
        else s.skippedAt(target) match
          case None => s.diagnostics
          case Some(sk) =>
            // A statement skipped inside a block left a stub in the typed
            // `Block`, and a typed `Block` is not spliced: resuming into it
            // would type the statement and leave the stub in place (a hover
            // on it found the block, a completion in it found nothing).
            if sk.inBlock then run(uri, source, target)
            else resume(s, sk, target)
      case _ =>
        run(uri, source, target)

  /** Type the skipped statement `sk` in the run and context it was skipped
   *  in, with `target` as the target for the statements nested in it, and
   *  splice the result into the unit's typed tree.
   */
  private def resume(s: Session, sk: Run.SkippedStat, target: Span): List[Diagnostic] =
    myCancelled = false
    s.skipped = s.skipped.filterNot(_ eq sk)
    s.run.skippedStats.clear()
    s.run.typingTarget = target
    try
      val ctx0 = sk.ctx
      s.run.targetedStatsOwner = sk.owner
      val (stats, _) = ctx0.typer.typedStats(sk.stat :: Nil, sk.owner)(using ctx0)
      s.skipped = s.skipped ++ s.run.skippedStats.toList
      splice(s, sk.owner, stats)(using ctx0)
      s.diagnostics
    catch case ex: CompilationUnit.CancelledCompilation =>
      // The statement being typed can not be typed a second time: typing
      // consumes the attachments of the untyped trees (`SymOfTree`,
      // `TypedAhead`, `ExpandedTree`) and the nested typers of the methods.
      // Whatever was typed before the abort stays typed; the statements
      // skipped inside the aborted one are its own and go with it.
      s.reporter.removeBufferedMessages(using sk.ctx)
      if !replacePoisoned(s, sk) then s.poisoned = true
      throw ex
    finally
      s.run.typingTarget = NoSpan
      s.run.targetedStatsOwner = NoSymbol
      s.run.skippedStats.clear()

  /** Does `owner` define `name` in this file: the companion of a statement
   *  to replace. A companion is not replaced alone: the synthetic members a
   *  case class gives its object (`apply`, `unapply`) were merged into the
   *  object's tree by the class's expansion, and a fresh parse of the object
   *  alone lacks them (`SourceTree(imp, source)` failed with "object
   *  SourceTree does not take parameters" after such a replacement).
   */
  private def hasCompanionHere(owner: Symbol, name: Name)(using Context): Boolean =
    val cls = if owner.isClass then owner else owner.owner
    cls.isClass && {
      val sym = cls.asClass.classDenot.info.decls.lookup(name)
      sym.exists && mySession != null && { val f = sym.associatedFile; f != null && f.path == mySession.nn.unit.source.file.nn.path }
    }

  /** Replace the skipped statement `sk`, whose typing was aborted, by a
   *  fresh parse of its source range, indexed afresh in the same run and
   *  context: its symbols are unlinked from the owner's scope and re-created,
   *  and it is skipped again. References to the old symbols typed earlier
   *  keep them. Only definitions are replaced (not the synthetic
   *  `<file>$package` object of top-level definitions, nor a nested package
   *  clause, nor a statement that is an expression).
   */
  private def replacePoisoned(s: Session, sk: Run.SkippedStat): Boolean =
    given Context = sk.ctx
    val head = sk.stat
    val replaceable = head match
      case mdef: untpd.ModuleDef => !mdef.rawMods.is(Flags.Synthetic) && !hasCompanionHere(sk.owner, mdef.name.toTypeName)
      case tdef: untpd.TypeDef => !hasCompanionHere(sk.owner, tdef.name.toTermName)
      case _: untpd.DefDef | _: untpd.ValDef => true
      case _ => false
    if !replaceable then return false
    val parsed = new parsing.Parsers.Parser(s.source).parse()
    val fresh = new untpd.UntypedTreeAccumulator[Option[untpd.Tree]]:
      def apply(x: Option[untpd.Tree], tree: untpd.Tree)(using Context): Option[untpd.Tree] =
        if x.isDefined then x
        else if tree.span == head.span && tree.getClass == head.getClass then Some(tree)
        else if tree.span.contains(head.span) then foldOver(x, tree)
        else x
    fresh(None, parsed) match
      case Some(stat) =>
        val cls = (if sk.owner.isClass then sk.owner else sk.owner.owner).asClass.classDenot
        val file = s.unit.source.file
        for sym <- cls.info.decls.toList do
          if sym.span.exists && stat.span.contains(sym.span) && sym.associatedFile == file then
            cls.delete(sym)
        ctx.typer.index(stat :: Nil)
        s.skipped = Run.SkippedStat(stat, sk.ctx, sk.owner) :: s.skipped
        true
      case None =>
        false

  /** Complete every symbol of the skipped statements of `s`, in the run
   *  that created their completers: the statement's own symbols, then the
   *  members of a class after the class (whose completion indexes its
   *  template), nested ones likewise. The typed statements completed their
   *  symbols as they were typed, and typing consumed their `ExpandedTree`
   *  attachments, so the untyped tree no longer leads to them; the skipped
   *  statements keep theirs. Local definitions inside bodies are not indexed
   *  and have no completer to run.
   */
  private def completeSymbolsOf(s: Session): Unit =
    given Context = myCtx.fresh.setCompilationUnit(s.unit) // the session's run context, with root imports
    val namer = ctx.typer
    // An `ExpandedTree` can lead to a thicket that contains the tree itself
    // (`cpy` returns the same tree when nothing changed): visit each tree once.
    val seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[untpd.Tree, java.lang.Boolean])
    def walk(tree: untpd.Tree): Unit = if seen.add(tree) then tree match
      case tree: untpd.DefTree if tree.hasAttachment(namer.ExpandedTree) =>
        tree.getAttachment(namer.ExpandedTree).foreach(walk)
        walkOwn(tree)
      case tree: untpd.DefTree => walkOwn(tree)
      case untpd.Thicket(trees) => trees.foreach(walk)
      case untpd.PackageDef(_, stats) => stats.foreach(walk)
      case _ =>
    def walkOwn(tree: untpd.DefTree): Unit = tree match
      case tree: untpd.TypeDef =>
        tree.getAttachment(namer.SymOfTree).foreach { sym =>
          sym.ensureCompleted()
          tree.rhs match
            case impl: untpd.Template => impl.body.foreach(walk)
            case _ =>
        }
      case _ =>
        tree.getAttachment(namer.SymOfTree).foreach(_.ensureCompleted())
    s.skipped.foreach(sk => walk(sk.stat))
    s.diagnostics = s.diagnostics ++ s.reporter.removeBufferedMessages

  /** Insert the typed `stats` into the statements of their owner in the
   *  unit's typed tree (the package for a package class, the template for
   *  a local dummy), in source order, and update what depends on the tree.
   */
  private def splice(s: Session, owner: Symbol, stats: List[Tree])(using Context): Unit =
    def insert(into: List[Tree]): List[Tree] =
      val start = stats.headOption.map(_.span.start).getOrElse(-1)
      val (before, after) = into.span(t => !t.span.exists || t.span.start <= start)
      before ++ stats ++ after
    val mapper = new tpd.TreeMap:
      override def transform(tree: Tree)(using Context): Tree = tree match
        // a skipped block definition's stub has an untyped type tree: not entered
        case t if !t.hasType => t
        case t: PackageDef if owner.is(Flags.PackageClass) && t.symbol.moduleClass == owner =>
          tpd.cpy.PackageDef(t)(t.pid, insert(t.stats))
        case t: Template if t.symbol == owner =>
          tpd.cpy.Template(t)(t.constr, t.parents, t.derived, t.self, insert(t.body))
        case _ => super.transform(tree)
    val t = mapper.transform(s.unit.tpdTree)
    s.unit.tpdTree = t
    // What `SetRootTree` would have done had the statements been typed in the phase
    inContext(ctx.fresh.setCompilationUnit(s.unit)):
      new transform.SetRootTree().run
    stats.foreach(cleanup(_, errorOutCompleters = false))
    myOpenedTrees(s.uri) = topLevelTrees(t, s.source)
    s.diagnostics = s.diagnostics ++ s.reporter.removeBufferedMessages

  private def run(uri: URI, source: SourceFile, target: Span): List[Diagnostic] = {
    import typer.ImportInfo.*

    val previousCtx = myCtx
    val previousSource = myOpenedFiles.get(uri)
    // A targeted run leaves the symbols of the statements it skipped with
    // completers of its own run, which refuse to complete in a later run
    // (`Namer.Completer.complete` gives `UnspecifiedErrorType`): a run on
    // another file that refers to them would find them "not found". Before
    // leaving a file, complete every symbol the Namer indexed in it, in its
    // own run: the signatures, not the bodies. A whole-file run completed
    // them all as it typed; a run on the same file re-enters them.
    mySession match
      case s: Session if s.uri != uri => completeSymbolsOf(s)
      case _ =>
    // Any new run resets the caches of the context base: a previous targeted
    // run can no longer be resumed.
    mySession = null
    var startedRun: Run | Null = null
    try {
      val reporter =
        new StoreReporter(null) with UniqueMessagePositions with HideNonSensicalMessages

      myCancelled = false
      val run = compiler.newRun(using myInitCtx.fresh.setReporter(reporter).setProgressCallback(myProgressCallback))
      startedRun = run
      myCtx = run.runContext.withRootImports

      given Context = myCtx

      myOpenedFiles(uri) = source

      run.typingTarget = target
      run.skippedStats.clear()
      try run.compileSources(List(source))
      finally run.typingTarget = NoSpan
      // A cancellation noticed before the typer skips the units instead of
      // unwinding (see `Run.didEnterUnit`); discard that run too.
      if myProgressCallback.isCancelled() then throw CompilationUnit.CancelledCompilation()
      run.printSummary()
      val ctxRun = ctx.run.nn
      val unit = if ctxRun.units.nonEmpty then ctxRun.units.head else ctxRun.suspendedUnits.head
      val t = unit.tpdTree
      val skipped = run.skippedStats.toList
      run.skippedStats.clear()
      val partial = target.exists && skipped.nonEmpty
      cleanup(t, errorOutCompleters = !partial)
      myOpenedTrees(uri) = topLevelTrees(t, source)
      myCompilationUnits(uri) = unit
      myCtx = myCtx.fresh.setPhase(myInitCtx.base.typerPhase)

      val diagnostics = reporter.removeBufferedMessages
      if target.exists then
        val s = Session(uri, source, run, reporter, unit)
        s.diagnostics = diagnostics
        s.skipped = skipped
        mySession = s
      diagnostics
    } catch {
      case ex: FatalError  =>
        myCtx = previousCtx
        close(uri)
        Nil
      case ex: CompilationUnit.CancelledCompilation =>
        // The aborted run's trees and units are discarded: the previous run's
        // stay current, and the caller decides what to answer. Its context
        // stays current, though: the run brought the denotations it touched
        // forward to its own run id, and a query under the previous run's
        // context would find them "invalid in run" (the soak suite did).
        startedRun match
          case r: Run => myCtx = r.runContext.withRootImports.fresh.setPhase(myInitCtx.base.typerPhase)
          case null => myCtx = previousCtx
        previousSource match
          case Some(s) => myOpenedFiles(uri) = s
          case None => myOpenedFiles.remove(uri)
        throw ex
    }
  }

  def close(uri: URI): Unit = {
    myOpenedFiles.remove(uri)
    myOpenedTrees.remove(uri)
    myCompilationUnits.remove(uri)
  }

  private def topLevelTrees(topTree: Tree, source: SourceFile): List[SourceTree] = {
    val trees = new mutable.ListBuffer[SourceTree]

    def addTrees(tree: Tree): Unit = tree match {
      case PackageDef(_, stats) =>
        stats.foreach(addTrees)
      case imp: Import =>
        trees += SourceTree(imp, source)
      case tree: TypeDef =>
        trees += SourceTree(tree, source)
      case _ =>
    }
    addTrees(topTree)

    trees.toList
  }

  /** Remove attachments and error out completers. The goal is to avoid
   *  having a completer hanging in a typed tree which can capture the context
   *  of a previous run. Note that typed trees can have untyped or partially
   *  typed children if the source contains errors.
   */
  private def cleanup(tree: tpd.Tree, errorOutCompleters: Boolean = true)(using Context): Unit = {
    val seen = mutable.Set.empty[tpd.Tree]
    def cleanupTree(tree: tpd.Tree): Unit = {
      seen += tree
      tree.foreachSubTree { t =>
        if (t.symbol.exists && t.hasType) {
          // Not for the prefix of a targeted run: its run stays current, and
          // the symbols of the untyped rest are still to be completed in it.
          if (errorOutCompleters && !t.symbol.isCompleted) t.symbol.info = UnspecifiedErrorType
          t.symbol.annotations.foreach { annot =>
            /* In some cases annotations are used on themself (possibly larger cycles).
            *  This is the case with the java.lang.annotation.Target annotation, would end
            *  in an infinite loop while cleaning. The `seen` is added to ensure that those
            *  trees are not cleand twice.
            *  TODO: Find a less expensive way to check for those cycles.
            */
            if (annot.isEvaluated && !seen(annot.tree))
              cleanupTree(annot.tree)
          }
        }
        t.removeAllAttachments()
      }
    }
    cleanupTree(tree)
  }

  /**
   * Initialize this driver and compiler.
   *
   * This is necessary because an `InteractiveDriver` can be put to work without having
   * compiled anything (for instance, resolving a symbol coming from a different compiler in
   * this compiler). In those cases, an un-initialized compiler may crash (for instance if
   * late-compilation is needed).
   */
  private def initialize(): Unit = {
    val run = compiler.newRun(using myInitCtx.fresh)
    myCtx = run.runContext
    run.compileUnits(Nil, myCtx)
  }
}

object InteractiveDriver:
  private val neverCancelled: () => Boolean = () => false
