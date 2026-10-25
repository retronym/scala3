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
import dotty.tools.io.AbstractFile
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

  private val myProgressCallback: ProgressCallback = new ProgressCallback:
    override def isCancelled(): Boolean = Thread.interrupted()

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

  def run(uri: URI, source: SourceFile): List[Diagnostic] =
    mySession match
      case s: Session if s.isOn(uri, source) && s.isResumable && s.skipped.nonEmpty =>
        // A whole-file run on the content of the session completes the session
        // instead of replacing it, so the requests after it are served from the
        // run that holds the whole file typed.
        s.complete()
      case _ => run(uri, source, NoSpan)

  /** The state of a targeted run (see `runUpTo`): the run, the unit with
   *  the partially typed tree, the statements the run skipped (with the
   *  context to type them in), and the diagnostics so far. Its operations
   *  mutate only the session, its unit and its run.
   */
  private class Session(val uri: URI, val source: SourceFile, val run: Run, val reporter: StoreReporter,
      val unit: CompilationUnit):
    var skipped: List[Run.SkippedStat] = Nil
    var diagnostics: List[Diagnostic] = Nil
    /** Set when a skipped statement has no typer state left to be typed in
     *  (see `liveContext`): the next request starts a fresh run.
     */
    var poisoned: Boolean = false

    def skippedAt(target: Span): Option[Run.SkippedStat] = skipped.find(_.stat.span.contains(target))

    /** Is this the session of `uri` on the content of `source`? */
    def isOn(uri: URI, source: SourceFile): Boolean =
      this.uri == uri && this.source.textContent() == source.textContent()

    /** A poisoned session holds a statement that is neither typed nor
     *  skipped: the request starts a fresh run.
     */
    def isResumable: Boolean = !poisoned

    /** Move the statements the run skipped since into the session's. */
    private def collectSkipped(): Unit =
      skipped = skipped ++ run.skippedStats.toList
      run.skippedStats.clear()

    /** Is `sym` defined in this session's file, by the identity of its
     *  `associatedFile` (a virtual file has no equality of its own)?
     */
    private def definesHere(sym: Symbol)(using Context): Boolean = sym.associatedFile == unit.source.file

    /** Is `file` the file of this session, by path? */
    def isFileOf(file: AbstractFile | Null): Boolean = file != null && file.path == unit.source.file.nn.path

    /** Complete the session: every statement still skipped is typed whole, in
     *  source order, in its recorded context, and all are spliced in one pass;
     *  the run then holds the whole file typed, and the requests after it are
     *  served from it.
     */
    def complete(): List[Diagnostic] =
      while skipped.nonEmpty && !poisoned do
        val batch = skipped.sortBy(_.stat.span.start)
        skipped = Nil
        run.clearTargeting() // no target: each statement typed whole
        val typed = List.newBuilder[(Run.SkippedStat, List[Tree])]
        try
          for sk <- batch do
            assertOwnSource(sk)
            liveContext(sk.ctx) match
              case None =>
                // no typer state left to type it in: it stays skipped, and the
                // next request starts fresh
                skipped = sk :: skipped
                poisoned = true
              case Some(ctx0) =>
                // in its own typer state, committed once typed (see `resumeIn`)
                val ctx1 = ctx0.fresh.setNewTyperState()
                val stats = ctx1.typer.typedStats(sk.stat :: Nil, sk.owner)(using ctx1)._1
                ctx1.typerState.commit()(using ctx0)
                typed += ((sk, stats))
        finally
          val entries = typed.result()
          if entries.nonEmpty then spliceAll(entries)(using entries.head._1.ctx)
          collectSkipped()
      diagnostics

    /** A skipped statement is a statement of the session's own source: one
     *  from an inlined macro's source would splice at the wrong offsets.
     */
    private def assertOwnSource(sk: Run.SkippedStat): Unit =
      assert(sk.ctx.source eq unit.source, s"skipped statement at ${sk.stat.span} is from ${sk.ctx.source}, not the session's ${unit.source}")

    /** Type the skipped statement `sk` in the run and context it was skipped
     *  in, with `target` as the target for the statements nested in it, and
     *  splice the result into the unit's typed tree.
     */
    def resume(sk: Run.SkippedStat, target: Span): List[Diagnostic] =
      liveContext(sk.ctx) match
        case None => InteractiveDriver.this.run(uri, source, target) // no typer state left to resume in
        case Some(ctx0) => resumeIn(sk, ctx0, target)

    /** The recorded context of a skipped statement, in the nearest typer
     *  state that can still take constraints. A statement skipped inside an
     *  argument is recorded under the nested state that `tryEither` made for
     *  the application, which is committed into its parent when the
     *  application succeeds; typing under it would commit into a committed
     *  state. Its parent holds everything it held, and what the application
     *  instantiated afterwards. An explore state (not committable) is
     *  climbed over the same way. `None` if no ancestor is live.
     */
    private def liveContext(ctx0: Context): Option[Context] =
      def live(ts: TyperState | Null): TyperState | Null =
        if ts == null then null
        else if ts.isCommittable && !ts.hasBeenCommitted then ts
        else live(ts.previousState)
      val ts = ctx0.typerState
      if ts.isCommittable && !ts.hasBeenCommitted then Some(ctx0)
      else live(ts.previousState) match
        case null => None
        case ts1 => Some(ctx0.fresh.setTyperState(ts1))

    private def resumeIn(sk: Run.SkippedStat, ctx0: Context, target: Span): List[Diagnostic] =
      assertOwnSource(sk)
      skipped = skipped.filterNot(_ eq sk)
      run.clearTargeting()
      run.typingTarget = target
      try
        // typed in its own typer state, committed into the session's only once
        // the statement is typed
        val ctx1 = ctx0.fresh.setNewTyperState()
        run.targetNextStats(sk.owner, sk.inBlock)(using ctx1)
        // a resumed block statement continues the targeting of its block
        run.targetingTyperState = ctx1.typerState
        val (stats, _) = ctx1.typer.typedStats(sk.stat :: Nil, sk.owner)(using ctx1)
        ctx1.typerState.commit()(using ctx0)
        collectSkipped()
        assert(!skipped.exists(_.stat.span.contains(target)), s"target $target is still skipped in $uri after the resume of ${sk.stat.span}")
        spliceAll(List((sk, stats)))(using ctx0)
        diagnostics
      finally run.clearTargeting()

    /** Complete every symbol of the skipped statements, in the run that
     *  created their completers: the statement's own symbols, then the
     *  members of a class after the class (whose completion indexes its
     *  template), nested ones likewise. The typed statements completed their
     *  symbols as they were typed, and typing consumed their `ExpandedTree`
     *  attachments, so the untyped tree no longer leads to them; the skipped
     *  statements keep theirs. Local definitions inside bodies are not indexed
     *  and have no completer to run.
     */
    def completeSymbols(): Unit =
      given Context = myCtx.fresh.setCompilationUnit(unit) // the session's run context, with root imports
      assert(ctx.runId == run.runContext.runId, s"the completers of $uri were created in run ${run.runContext.runId}, not in run ${ctx.runId}")
      try skipped.foreach(sk => ctx.typer.foreachIndexedDef(sk.stat)((_, sym) => sym.ensureCompleted()))
      finally diagnostics = diagnostics ++ reporter.removeBufferedMessages

    /** Insert the typed `stats` into the statements of their owner in the
     *  unit's typed tree (the package for a package class, the template for
     *  a local dummy), in source order, and update what depends on the tree.
     */
    private def spliceAll(entries: List[(Run.SkippedStat, List[Tree])])(using Context): Unit =
      val src = unit.source
      def isStubOf(sk: Run.SkippedStat)(t: Tree) = t.span == sk.stat.span && (t.source eq src)
      /** `stats` inserted into `into` in source order */
      def insert(into: List[Tree], stats: List[Tree]): List[Tree] =
        stats.foldLeft(into) { (acc, st) =>
          val (before, after) = acc.span(t => !t.span.exists || t.span.start <= st.span.start)
          before ++ (st :: after)
        }
      val ofOwner = entries.filter(!_._1.inBlock).groupBy(_._1.owner)
      val inBlocks = entries.filter(_._1.inBlock)
      val placed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Run.SkippedStat, java.lang.Boolean])
      val mapper = new tpd.TreeMap:
        override def transform(tree: Tree)(using Context): Tree = tree match
          // a skipped block definition's stub has an untyped type tree: not entered
          case t if !t.hasType => t
          case t: PackageDef if t.symbol.moduleClass.is(Flags.PackageClass) && ofOwner.contains(t.symbol.moduleClass) =>
            // and on into the classes and blocks it holds: their entries are theirs
            ofOwner(t.symbol.moduleClass).foreach(e => placed.add(e._1))
            super.transform(tpd.cpy.PackageDef(t)(t.pid, insert(t.stats, ofOwner(t.symbol.moduleClass).flatMap(_._2))))
          case t: Template if ofOwner.contains(t.symbol) =>
            ofOwner(t.symbol).foreach(e => placed.add(e._1))
            super.transform(tpd.cpy.Template(t)(t.constr, t.parents, t.derived, t.self, insert(t.body, ofOwner(t.symbol).flatMap(_._2))))
          // the innermost typed block containing a statement's span: its stubs
          // (a definition's) go, the typed statement is inserted in order (an
          // expression statement left no stub); a block nested in one of the
          // block's statements is reached by the traversal
          case b: Block if inBlocks.nonEmpty && (b.source eq src) =>
            // a definition's place is the block that holds its stub, whatever
            // else overlaps it there (the synthetic temporary of a pattern
            // definition spans the vals it binds); an expression statement
            // left no stub, and its place is the block that contains it
            // directly, not through one of the block's statements
            val here = inBlocks.filter { (sk, _) =>
              b.stats.exists(isStubOf(sk))
              || b.span.contains(sk.stat.span)
                 && !(b.stats :+ b.expr).exists(t => t.span.exists && t.span != sk.stat.span && t.span.contains(sk.stat.span)) }
            if here.isEmpty then super.transform(b)
            else
              val stubs = here.map(_._1)
              stubs.foreach(placed.add)
              super.transform(tpd.cpy.Block(b)(insert(b.stats.filterNot(t => stubs.exists(sk => isStubOf(sk)(t))), here.flatMap(_._2)), b.expr))
          case _ => super.transform(tree)
      val t = mapper.transform(unit.tpdTree)
      // every typed statement finds its place: its owner's package or template,
      // or the innermost typed block that contains it
      val dropped = entries.filterNot(e => placed.contains(e._1))
      assert(dropped.isEmpty, dropped.map { (sk, stats) =>
        s"${sk.stat.getClass.getSimpleName}${sk.stat.span} owner=${sk.owner.showLocated} inBlock=${sk.inBlock} typed=${stats.map(_.getClass.getSimpleName).mkString(",")} \"${unit.source.textContent.slice(sk.stat.span.start, sk.stat.span.start + 50).replace('\n', ' ')}\""
      }.mkString(s"spliced ${entries.size - dropped.size} of ${entries.size} typed statements; dropped: ", "; ", ""))
      unit.tpdTree = t
      // What `SetRootTree` would have done had the statements been typed in the phase
      inContext(ctx.fresh.setCompilationUnit(unit)):
        new transform.SetRootTree().run
      entries.foreach(_._2.foreach(cleanup(_, errorOutCompleters = false)))
      myOpenedTrees(uri) = topLevelTrees(t, source)
      diagnostics = diagnostics ++ reporter.removeBufferedMessages
  end Session

  private var mySession: Session | Null = null

  private def sessionOf(uri: URI): Session | Null = mySession match
    case s: Session if s.uri == uri => s
    case _ => null

  // ---- Introspection of the targeted session, for tests, the x-ray, the log
  // and `CachingDriver`; not used by the providers.

  /** The number of top-level trees (the statements of the outermost package;
   *  an object counts as its module val and class) the targeted run of `uri`
   *  has typed, or -1 if `uri` is not the subject of a targeted run. They are
   *  those that contained the targets of the requests so far, not a prefix.
   */
  def typedTopLevelCount(uri: URI): Int = sessionOf(uri) match
    case s: Session =>
      // through a package clause that wraps the whole file
      def count(t: Tree): Int = t match
        case PackageDef(_, (inner: PackageDef) :: Nil) => count(inner)
        case PackageDef(_, stats) => stats.length
        case _ => 0
      count(s.unit.tpdTree)
    case null => -1

  /** The spans of the statements the targeted run of `uri` has not typed. */
  def untypedSpans(uri: URI): List[Span] = sessionOf(uri) match
    case s: Session => s.skipped.map(_.stat.span)
    case null => Nil

  /** Has the targeted run of `uri` typed all of its statements? */
  def isFullyTyped(uri: URI): Boolean = sessionOf(uri) match
    case s: Session => s.skipped.isEmpty
    case null => false

  /** The id of the run that typed `uri`, or -1. */
  def runIdOf(uri: URI): Int = sessionOf(uri) match
    case s: Session => s.run.runContext.runId
    case null => -1

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
      case s: Session if s.isOn(uri, source) =>
        // Nothing is served or resumed from a poisoned session (a request in
        // a poisoned statement's range would be served from the tree without
        // it): the request starts a fresh run, which is cheap for a targeted
        // run.
        if !s.isResumable then run(uri, source, target)
        else s.skippedAt(target) match
          case None => s.diagnostics
          // A statement skipped inside a block left a stub in the typed
          // `Block`; the resume replaces the stub by the typed statement.
          case Some(sk) => s.resume(sk, target)
      case _ =>
        run(uri, source, target)

  private def run(uri: URI, source: SourceFile, target: Span): List[Diagnostic] = {
    import typer.ImportInfo.*

    val previousCtx = myCtx
    // A targeted run leaves the symbols of the statements it skipped with
    // completers of its own run, which refuse to complete in a later run
    // (`Namer.Completer.complete` gives `UnspecifiedErrorType`): a run on
    // another file that refers to them would find them "not found". Before
    // leaving a file, complete every symbol the Namer indexed in it, in its
    // own run: the signatures, not the bodies. A whole-file run completed
    // them all as it typed; a run on the same file re-enters them.
    mySession match
      case s: Session if s.uri != uri => s.completeSymbols()
      case _ =>
    // Any new run resets the caches of the context base: a previous targeted
    // run can no longer be resumed.
    mySession = null
    try {
      val reporter =
        new StoreReporter(null) with UniqueMessagePositions with HideNonSensicalMessages

      val run = compiler.newRun(using myInitCtx.fresh.setReporter(reporter).setProgressCallback(myProgressCallback))
      myCtx = run.runContext.withRootImports

      given Context = myCtx

      myOpenedFiles(uri) = source

      run.typingTarget = target
      run.isTargeted = target.exists
      val skipped =
        try
          run.compileSources(List(source))
          run.skippedStats.toList
        finally run.clearTargeting()
      run.printSummary()
      val ctxRun = ctx.run.nn
      val unit = if ctxRun.units.nonEmpty then ctxRun.units.head else ctxRun.suspendedUnits.head
      val t = unit.tpdTree
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

