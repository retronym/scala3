package dotty.tools
package dotc

import core.*
import Contexts.*
import Periods.*
import Symbols.*
import Scopes.*
import Names.Name
import Denotations.Denotation
import typer.Typer
import typer.ImportInfo.withRootImports
import Decorators.*
import io.AbstractFile
import Phases.{unfusedPhases, Phase}

import sbt.interfaces.ProgressCallback

import util.*
import reporting.{Suppression, Action, Profile, ActiveProfile, MessageFilter, NoProfile, WConf}
import reporting.Diagnostic, Diagnostic.Warning
import rewrites.Rewrites
import profile.Profiler
import quoted.QuotesCache
import printing.XprintMode
import typer.ImplicitRunInfo
import config.Feature
import StdNames.nme
import Spans.Span

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets

import scala.collection.mutable, mutable.ListBuffer
import scala.annotation.internal.{sharable, scratch, cache, interned, guardedBy, publishedBy, benignRace}
import scala.io.Codec

import Run.Progress
import scala.compiletime.uninitialized
import dotty.tools.dotc.transform.MegaPhase
import dotty.tools.dotc.transform.Pickler
import dotty.tools.dotc.transform.Pickler.AsyncTastyHolder
import dotty.tools.dotc.util.chaining.*
import java.util.{Timer, TimerTask}

/** A compiler run. Exports various methods to compile source files */
class Run(comp: Compiler, ictx: Context)
extends ImplicitRunInfo, ConstraintRunInfo, cc.CaptureRunInfo {

  /** Default timeout to stop looking for further implicit suggestions, in ms.
   *  This is usually for the first import suggestion; subsequent suggestions
   *  may get smaller timeouts. @see ImportSuggestions.reduceTimeBudget
   */
  @scratch @benignRace("a time budget in ms for import suggestions: a lost update only changes how long a later suggestion searches, which the clock decides anyway")
  private var myImportSuggestionBudget: Int =
    Int.MinValue // sentinel value; means whatever is set in command line option

  def importSuggestionBudget =
    if myImportSuggestionBudget == Int.MinValue then ictx.settings.XimportSuggestionTimeout.value
    else myImportSuggestionBudget

  def importSuggestionBudget_=(x: Int) =
    myImportSuggestionBudget = x

  /** If this variable is set to `true`, some core typer operations will
   *  return immediately. Currently these early abort operations are
   *  `Typer.typed` and `Implicits.typedImplicit`.
   */
  @scratch @publishedBy("volatile") @volatile var isCancelled = false

  // `compiling`, `myUnits` and the other run-wide fields marked `@guardedBy("writer")` are set while the run compiles:
  // in the interactive driver that is a write action, so readers see them only after it, and none of them changes then.
  @scratch @guardedBy("writer") private var compiling = false

  @scratch @guardedBy("writer") private var myUnits: List[CompilationUnit] = Nil

  // `files` of `myUnits`, with the units it was made from: one immutable pair in one volatile field, so that a reader
  // that finds the units it asks for finds their files with them
  @cache @publishedBy("packed") @volatile private var myFilesOf: (List[CompilationUnit], Set[AbstractFile]) = (Nil, Set.empty)

  // Readers type bodies in parallel, and each warning they issue and each `@nowarn` they meet reaches these three
  // collections, so they are reached only holding the monitor of `suppressions` (`locked`: with concurrent readers
  // only, as the batch compiler is one thread). Nothing issues a message while holding it.
  object suppressions extends Sharing.Guard:
    // `@nowarn` annotations by source file, populated during typer
    @scratch private val bySource = guarded(mutable.LinkedHashMap.empty[SourceFile, ListBuffer[Suppression]])
    // source files whose `@nowarn` annotations are processed
    @scratch private val complete = guarded(mutable.Set.empty[SourceFile])
    // warnings issued before a source file's `@nowarn` annotations are processed, suspended so that `@nowarn` can filter them
    @scratch private val suspended = guarded(mutable.LinkedHashMap.empty[SourceFile, mutable.LinkedHashSet[Warning]])

    // When the REPL creates a new run (ReplDriver.compile), parsing is already done in the old context, with the
    // previous Run. Parser warnings were suspended in the old run and need to be copied over so they are not lost.
    // Same as scala/scala/commit/79ca1408c7.
    def initSuspendedMessages(oldRun: Run | Null) =
      // the REPL: one thread, this run is not yet shared and the old one is finished
      given Sharing.Monitor[this.type] = Sharing.unlocked("the REPL is one thread, and neither run is shared")
      suspended().clear()
      if oldRun != null then
        val old = oldRun
        suspended() ++= old.suppressions.suspendedOf(using Sharing.unlocked("the old run is finished"))

    private def suspendedOf(using Sharing.Monitor[this.type]) = suspended()

    def suppressionsComplete(source: SourceFile)(using Context) =
      source == NoSource || locked(complete()(source))

    def addSuspendedMessage(warning: Warning)(using Context) =
      locked(suspended().getOrElseUpdate(warning.pos.source, mutable.LinkedHashSet.empty) += warning)

    def nowarnAction(dia: Diagnostic)(using Context): Action.Warning.type | Action.Verbose.type | Action.Silent.type =
      locked:
        bySource().get(dia.pos.source) match
        case Some(suppressions) =>
          val matching = suppressions.iterator.filter(_.matches(dia))
          if matching.hasNext then
            val s = matching.next()
            for other <- matching do
              if !other.used then
                other.markSuperseded() // superseded unless marked used later
            s.markUsed()
            if s.verbose then Action.Verbose
            else Action.Silent
          else
            Action.Warning
        case none =>
          Action.Warning

    def registerNowarn(annotPos: SourcePosition, range: Span)(conf: String, pos: SrcPos)(using Context): Unit =
      var verbose = false
      val filters = conf match
        case "" =>
          List(MessageFilter.Any)
        case "none" =>
          List(MessageFilter.None)
        case "verbose" | "v" =>
          verbose = true
          List(MessageFilter.Any)
        case conf =>
          WConf.parseFilters(conf).left.map: parseErrors =>
            report.warning(s"Invalid message filter\n${parseErrors.mkString("\n")}", pos)
            List(MessageFilter.None)
          .merge
      val sup = Suppression(annotPos, filters, range.start, range.end, verbose)
      sup.provenance = ctx.reader.provenance
      addSuppression(sup)

    def addSuppression(sup: Suppression)(using Context): Unit =
      locked:
        val suppressions = bySource().getOrElseUpdate(sup.annotPos.source, ListBuffer.empty)
        if sup.start != sup.end then
          suppressions += sup

    /** In interactive mode, after an edit of `source`: each suppression of
     *  the file replaced by `f`'s result, or removed where it is null.
     */
    def editSuppressions(source: SourceFile)(f: Suppression => Suppression | Null)(using Context): Unit =
      locked:
        bySource().get(source).foreach { sups =>
          val kept = sups.flatMap(sup => f(sup) match
            case null => None
            case s: Suppression => Some(s))
          sups.clear()
          sups ++= kept
        }

    def reportSuspendedMessages(source: SourceFile)(using Context): Unit = {
      val messages = locked:
        // sort suppressions. they are not added in any particular order because of lazy type completion
        for (sups <- bySource().get(source))
          bySource()(source) = sups.sortBy(sup => 0 - sup.start)
        complete() += source
        suspended().remove(source)
      messages.foreach(_.foreach(ctx.reporter.issueIfNotSuppressed))
    }

    def runFinished()(using Context): Unit =
      val hasErrors = ctx.reporter.hasErrors
      // report suspended messages (in case the run finished before typer)
      locked(suspended().keysIterator.toList).foreach(reportSuspendedMessages)
      // report unused nowarns only if all all phases are done
      if !hasErrors && ctx.settings.WunusedHas.nowarn then
        val removed = locked:
          for
            source <- bySource().keysIterator.toList
            sups   <- bySource().remove(source)
          yield sups
        for
          sups <- removed
        do
          val suppressions = sups.reverse.toList
          for sup <- suppressions do
            if !sup.used
            && !suppressions.exists(s => s.ne(sup) && s.used && s.annotPos == sup.annotPos) // duplicate
            && sup.filters != List(MessageFilter.None) // invalid suppression, don't report as unused
            then
              val more = if sup.superseded then " but matches a diagnostic" else ""
              report.warning("@nowarn annotation does not suppress any warnings"+more, sup.annotPos)
  end suppressions

  /** The compilation units currently being compiled, this may return different
   *  results over time.
   */
  def units: List[CompilationUnit] = myUnits

  private def units_=(us: List[CompilationUnit]): Unit =
    myUnits = us

  // A unit is suspended when a macro interpretation or an inline expansion needs a definition in a unit that is not yet
  // compiled (`CompilationUnit.suspend`), from whichever reader hits it; the reader unwinds, the run goes on, and the
  // driver compiles the suspended units again in a new run once this one has finished (`Driver.finish`). The three
  // collections below are reached only with the evidence of the monitor of `suspension` (`Sharing.serialised`: with
  // concurrent readers only, as the batch compiler is one thread); the readers outside get a snapshot.
  object suspension extends Sharing.Guard:
    @scratch private val units = guarded(ListBuffer.empty[CompilationUnit])
    @scratch private val hints = guarded(mutable.HashMap.empty[CompilationUnit, (String, Boolean)])
    // Were any units suspended in the typer phase? if so then pipeline tasty can not complete.
    @scratch private val atTyper = guardedVar(false)

    /** Record `unit` as suspended, unless it is already: two readers may suspend it at once, and only the first
     *  records it. The flags of the unit are set under the same monitor. Returns normally; the caller unwinds. */
    def add(unit: CompilationUnit, hint: => String, isInliningPhase: Boolean)(using Context): Unit =
      locked:
        if !unit.suspended then
          unit.suspended = true
          units() += unit
          if ctx.settings.XprintSuspension.value then
            hints() += (unit -> (hint, isInliningPhase))
          if isInliningPhase then
            unit.suspendedAtInliningPhase = true
          else
            atTyper() = true

    def snapshotUnits(using Context): List[CompilationUnit] = locked(units().toList)
    def snapshotHints(using Context): List[(CompilationUnit, (String, Boolean))] = locked(hints().toList)
    def atTyperPhase(using Context): Boolean = locked(atTyper())
  end suspension

  /** The units suspended so far, in the order they were suspended. */
  def suspendedUnits(using Context): List[CompilationUnit] = suspension.snapshotUnits
  def suspendedHints(using Context): List[(CompilationUnit, (String, Boolean))] = suspension.snapshotHints

  /** Were any units suspended in the typer phase? if so then pipeline tasty can not complete. */
  def suspendedAtTyperPhase(using Context): Boolean = suspension.atTyperPhase

  def checkSuspendedUnits(newUnits: List[CompilationUnit])(using Context): Unit =
    val suspended = suspendedUnits
    if newUnits.isEmpty && suspended.nonEmpty && !ctx.reporter.errorsReported then
      val where =
        if suspended.size == 1 then i"in ${suspended.head}."
        else i"""among
                |
                |  ${suspended}%, %
                |"""
      val enableXprintSuspensionHint =
        if ctx.settings.XprintSuspension.value then ""
        else "\n\nCompile with -Xprint-suspension for information."
      report.error(em"""Cyclic macro dependencies $where
                    |Compilation stopped since no further progress can be made.
                    |
                    |To fix this, place macros in one set of files and their callers in another.$enableXprintSuspensionHint""")

  /** The files currently being compiled (active or suspended).
   *  This may return different results over time.
   *  These files do not have to be source files since it's possible to compile
   *  from TASTY.
   */
  def files: Set[AbstractFile] = {
    val cached = myFilesOf
    val us = myUnits
    if us eq cached._1 then cached._2
    else
      // `files` has no context of its own; the run's shares the base, which says whether readers are concurrent
      val fs = (us ++ suspendedUnits(using runContext)).map(_.source.file).collect{ case f: AbstractFile => f }.toSet
      myFilesOf = (us, fs)
      fs
  }

  /** The source files of all late entered symbols, as a set. A completion of a source-loaded class enters one, and
   *  completions of different roots run in parallel, so it is reached only with the evidence of its monitor.
   */
  private object lateFiles extends Sharing.Guard:
    @scratch private val files = guarded(mutable.Set[AbstractFile]())

    /** Is `file` newly entered? */
    def enter(file: AbstractFile)(using Context): Boolean = locked(files().add(file))

    def size(using Context): Int = locked(files().size)

  /** A cache for static references to packages and classes */
  @cache val staticRefs = ictx.base.sharing.newEqHashMap[Name, Denotation](initialCapacity = 1024)

  /** Actions that need to be performed at the end of the current compilation run */
  @scratch @guardedBy("writer") private val finalizeActions = ListBuffer.empty[() => Unit]

  @scratch @guardedBy("writer") private var _progress: Progress | Null = null // Set if progress reporting is enabled

  private inline def trackProgress(using Context)(inline op: Context ?=> Progress => Unit): Unit =
    foldProgress(())(op)

  private inline def foldProgress[T](using Context)(inline default: T)(inline op: Context ?=> Progress => T): T =
    val local = _progress
    if local != null then
      op(using ctx)(local)
    else
      default

  def didEnterUnit(unit: CompilationUnit)(using Context): Boolean =
    foldProgress(true /* should progress by default */)(_.tryEnterUnit(unit))

  def canProgress()(using Context): Boolean =
    foldProgress(true /* not cancelled by default */)(p => !p.checkCancellation())

  /** Set once by the driver when the run starts with a target: the run is a
   *  targeted session's, and stays so for its later resumes and completion
   *  (the reader's `typingTarget` itself is reset between them). The target,
   *  the skipped statements and the completion bookkeeping of a request are
   *  on its `Reader`.
   */
  @scratch @guardedBy("writer") var isTargeted: Boolean = false

  /** A run with a degraded completion whose state is unknown (an exception
   *  other than a cyclic reference or a cancellation unwound through it,
   *  `Reader.recordCompletionUnwound`) is not resumed: the next request on
   *  the same content starts fresh. A prevented cyclic reference does not
   *  taint: the symbols it unwound through are left recomputable (`Touched`
   *  cleared), the request's own answer degrades at its handlers, and the
   *  run's state is as before.
   */
  def isTainted: Boolean = myTainted
  @scratch @benignRace("only ever set to true, by whichever reader meets the unwound completion first")
  private var myTainted: Boolean = false
  def markTainted(): Unit = myTainted = true

  /** Records that some error was reported in a globally committable context.
   *  The error will not necessarily be emitted, since it could still be that
   *  the enclosing context will be aborted. The variable is used as a smoke test
   *  to turn off assertions that might be wrong if the program is erroneous. To
   *  just test for `ctx.reporter.errorsReported` is not always enough, since it
   *  could be that the context in which the assertion is tested is a completer context
   *  that's different from the context where the error was reported. See i13218.scala
   *  for a test. (Per run, not per reader: an interactive request may meet a
   *  tree an earlier request's error left inconsistent.)
   */
  @scratch @publishedBy("volatile") @volatile private[dotc] var errorsToBeReported = false

  def doAdvanceUnit()(using Context): Unit =
    trackProgress: progress =>
      progress.currentUnitCount += 1 // trace that we completed a unit in the current (sub)phase
      progress.refreshProgress()

  def doAdvanceLate()(using Context): Unit =
    trackProgress: progress =>
      progress.currentLateUnitCount += 1 // trace that we completed a late compilation
      progress.refreshProgress()

  private def doEnterPhase(currentPhase: Phase)(using Context): Unit =
    trackProgress: progress =>
      progress.enterPhase(currentPhase)

  /** interrupt the thread and set cancellation state */
  private def cancelInterrupted(): Unit =
    try
      trackProgress(_.cancel())
    finally
      Thread.currentThread().interrupt()

  private def doAdvancePhase(currentPhase: Phase, wasRan: Boolean)(using Context): Unit =
    trackProgress: progress =>
      progress.currentUnitCount = 0 // reset unit count in current (sub)phase
      progress.currentCompletedSubtraversalCount = 0 // reset subphase index to initial
      progress.seenPhaseCount += 1 // trace that we've seen a (sub)phase
      if wasRan then
        // add an extra traversal now that we completed a (sub)phase
        progress.completedTraversalCount += 1
      else
        // no subphases were ran, remove traversals from expected total
        progress.totalTraversals -= currentPhase.traversals

  private def tryAdvanceSubPhase()(using Context): Unit =
    trackProgress: progress =>
      if progress.canAdvanceSubPhase then
        progress.currentUnitCount = 0 // reset unit count in current (sub)phase
        progress.seenPhaseCount += 1 // trace that we've seen a (sub)phase
        progress.completedTraversalCount += 1 // add an extra traversal now that we completed a (sub)phase
        progress.currentCompletedSubtraversalCount += 1 // record that we've seen a subphase
        if !progress.isCancelled() then
          progress.tickSubphase()

  /** if true, then we are done writing pipelined TASTy files (i.e. finished in a previous run.) */
  @scratch @guardedBy("writer") private var myAsyncTastyWritten = false

  @scratch @guardedBy("writer") private var _asyncTasty: Option[AsyncTastyHolder] = None

  /** populated when this run needs to write pipeline TASTy files. */
  def asyncTasty: Option[AsyncTastyHolder] = _asyncTasty

  private def initializeAsyncTasty()(using Context): () => Unit =
    // should we provide a custom ExecutionContext?
    // currently it is just used to call the `apiPhaseCompleted` and `dependencyPhaseCompleted` callbacks in Zinc
    import scala.concurrent.ExecutionContext.Implicits.global
    val async = AsyncTastyHolder.init
    _asyncTasty = Some(async)
    () => async.cancel()

  /** Wait for async TASTy operations (including Zinc callbacks like
   *  `apiPhaseCompleted`/`dependencyPhaseCompleted`) to complete and relay any
   *  buffered reports. This must happen before we return to Zinc, which calls
   *  `getCycleResultOnce` immediately after. See scala/scala3#25774.
   */
  private def syncAsyncTasty()(using Context): Unit =
    for
      async <- _asyncTasty
      bufferedReporter <- async.sync()
      report <- bufferedReporter.resetReports()
    do
      import reporting.Diagnostic
      report match
        case Pickler.Report.Error(msg, pos) =>
          ctx.reporter.report(Diagnostic.Error(msg(ctx), pos))
        case Pickler.Report.Warning(msg, pos) =>
          ctx.reporter.report(Diagnostic.Warning(msg(ctx), pos))
        case Pickler.Report.Log(msg) =>
          ctx.reporter.report(Diagnostic.Info(msg, NoSourcePosition))

  /** Will be set to true if any of the compiled compilation units contains
   *  a pureFunctions language import.
   */
  @scratch @benignRace("only ever set to true: a reader that misses it sees what the batch compiler sees before the import")
  var pureFunsImportEncountered = false
  
  /** Will be set to true if any of the compiled compilation units contains
   *  an inlineTraits language import.
   */
  @scratch @benignRace("only ever set to true: a reader that misses it sees what the batch compiler sees before the import")
  var inlineTraitsImportEncountered = false

  /** Will be set to true if experimental.captureChecking is enabled
   *  or any of the compiled compilation units contains a captureChecking language import.
   */
  @scratch @benignRace("only ever set to true: a reader that misses it sees what the batch compiler sees before the import")
  var ccEnabledSomewhere = Feature.ccEnabledBySetting(using ictx)

  /** If -explain-cycles is set, readers trace cyclic reference dependencies (each on its own `Reader.cyclicReferenceTrace`) */
  @cache @guardedBy("writer") var traceCycles: Boolean = false

  @scratch @benignRace("only ever set to true: it stops a second enrichment of the same message")
  private var myEnrichedErrorMessage = false

  def compile(files: List[AbstractFile]): Unit =
    try compileSources(files.map(runContext.getSource(_)))
    catch case ex: Exception if !this.enrichedErrorMessage =>
      val files1 = if units.isEmpty then files else units.map(_.source)
      report.echo(this.enrichErrorMessage(s"exception occurred while compiling ${files1.map(_.path)}"))
      throw ex

  /** TODO: There's a fundamental design problem here: We assemble phases using `fusePhases`
   *  when we first build the compiler. But we modify them with -Yskip, -Ystop
   *  on each run. That modification needs to either transform the tree structure,
   *  or we need to assemble phases on each run, and take -Yskip, -Ystop into
   *  account. I think the latter would be preferable.
   */
  def compileSources(sources: List[SourceFile]): Unit =
    if (sources forall (_.exists)) {
      units = sources.map(CompilationUnit(_))
      compileUnits()
    }


  def compileUnits(us: List[CompilationUnit]): Unit = {
    units = us
    compileUnits()
  }

  def compileUnits(us: List[CompilationUnit], ctx: Context): Unit = {
    units = us
    compileUnits()(using ctx)
  }

  @scratch @guardedBy("writer") var profile: Profile = NoProfile

  private def compileUnits()(using Context) = Stats.maybeMonitored {
    if (!ctx.mode.is(Mode.Interactive)) // IDEs might have multi-threaded access, accesses are synchronized
      ctx.base.checkSingleThreaded()

    compiling = true

    Feature.checkDeprecatedSettingFeatures

    profile =
      if ctx.settings.Vprofile.value
        || !ctx.settings.VprofileSortedBy.value.isEmpty
        || ctx.settings.VprofileDetails.value != 0
      then ActiveProfile(ctx.settings.VprofileDetails.value.max(0).min(1000))
      else NoProfile

    // If testing pickler, make sure to stop after pickling phase:
    val stopAfter =
      if (ctx.settings.YtestPickler.value) List("pickler")
      else ctx.settings.YstopAfter.value

    val runCtx = ctx.fresh
    runCtx.setProfiler(Profiler())

    val pluginPlan = ctx.base.addPluginPhases(ctx.base.phasePlan)
    val phases = ctx.base.fusePhases(pluginPlan,
      ctx.settings.Yskip.value, ctx.settings.YstopBefore.value, ctx.settings.Ycheck.value)
    ctx.base.usePhases(phases, runCtx)

    if ctx.settings.YnoDoubleBindings.value then
      ctx.base.checkNoDoubleBindings = true

    def runPhases(allPhases: Array[Phase])(using Context) = {
      var lastPrintedTree: PrintedTree = NoPrintedTree
      val profiler = ctx.profiler
      var phasesWereAdjusted = false

      var forceReachPhaseMaybe =
        if (ctx.isBestEffort && phases.exists(_.phaseName == "typer")) Some("typer")
        else None

      def matchesStopAfter(p: Phase): Boolean = p match
        case mp: dotty.tools.dotc.transform.MegaPhase =>
          mp.miniPhases.exists(sub => stopAfter.contains(sub.phaseName))
        case _ =>
          stopAfter.contains(p.phaseName)

      var stopped = false
      var i = 0
      while i < allPhases.length && !stopped do
        val phase = allPhases(i)
        doEnterPhase(phase)
        val phaseWillRun = phase.isRunnable || forceReachPhaseMaybe.nonEmpty
        if phaseWillRun then
          Stats.trackTime(s"phase time ms/$phase") {
            val start = System.currentTimeMillis
            profiler.onPhase(phase):
              try units = phase.runOn(units)
              catch case _: InterruptedException => cancelInterrupted()
            if (ctx.settings.Vprint.value.containsPhase(phase))
              for (unit <- units)
                def printCtx(unit: CompilationUnit) = phase.printingContext(
                  ctx.fresh.setPhase(phase.next).setCompilationUnit(unit))
                lastPrintedTree = printTree(lastPrintedTree)(using printCtx(unit))

            if forceReachPhaseMaybe.contains(phase.phaseName) then
              forceReachPhaseMaybe = None

            report.informTime(s"$phase ", start)
            Stats.record(s"total trees at end of $phase", ast.Trees.ntrees)
            for (unit <- units)
              Stats.record(s"retained typed trees at end of $phase", unit.tpdTree.treeSize)
            ctx.typerState.gc()
          }
          if !phasesWereAdjusted then
            phasesWereAdjusted = true
            if !Feature.ccEnabledSomewhere then
              ctx.base.unlinkPhaseAsDenotTransformer(Phases.checkCapturesPhase.prev)
              ctx.base.unlinkPhaseAsDenotTransformer(Phases.checkCapturesPhase)
            end if
          end if
        end if
        doAdvancePhase(phase, wasRan = phaseWillRun)
        if matchesStopAfter(phase) then stopped = true
        i += 1
      end while
      profiler.finished()
    }

    val fusedPhases = runCtx.base.allPhases
    if ctx.settings.explainCyclic.value then
      traceCycles = true
    runCtx.withProgressCallback: cb =>
      _progress = Progress(cb, this, fusedPhases.map(_.traversals).sum)
    val cancelAsyncTasty: () => Unit =
      if !myAsyncTastyWritten && Phases.picklerPhase.exists && !ctx.settings.XearlyTastyOutput.isDefault then
        initializeAsyncTasty()
      else () => {}

    showProgress(runPhases(allPhases = fusedPhases)(using runCtx))
    cancelAsyncTasty()
    syncAsyncTasty()

    suppressions.runFinished()
    ctx.reporter.finalizeReporting()
    if (!ctx.reporter.hasErrors)
      Rewrites.writeBack()
    while (finalizeActions.nonEmpty && canProgress()) {
      val action = finalizeActions.remove(0)
      action()
    }
    compiling = false
  }

  @scratch @guardedBy("writer") private var myCompilingSuspended: Boolean = false

  /** Is this run started via a compilingSuspended? */
  def isCompilingSuspended: Boolean = myCompilingSuspended

  /** Compile units `us` which were suspended in a previous run,
   *  also signal if all necessary async tasty files were written in a previous run.
   */
  def compileSuspendedUnits(us: List[CompilationUnit], asyncTastyWritten: Boolean): Unit =
    myCompilingSuspended = true
    myAsyncTastyWritten = asyncTastyWritten
    for unit <- us do unit.suspended = false
    compileUnits(us)

  /** Enter top-level definitions of classes and objects contained in source file `file`.
   *  The newly added symbols replace any previously entered symbols.
   *  If `typeCheck = true`, also run typer on the compilation unit, and set
   *  `rootTreeOrProvider`.
   */
  def lateCompile(file: AbstractFile, typeCheck: Boolean)(using Context): Unit =
    if (!files.contains(file) && lateFiles.enter(file)) {

      val unit = CompilationUnit(ctx.getSource(file))
      val unitCtx = runContext.fresh
        .setCompilationUnit(unit)
        .withRootImports

      def process()(using Context) =
        ctx.typer.lateEnterUnit(typeCheck)(doTypeCheck =>
          if compiling then finalizeActions += doTypeCheck
          else doTypeCheck()
        )

      process()(using unitCtx)
    }

  /** If set to true, prints every 10 seconds the files currently being compiled.
   *  Turn this flag on if you want to find out which test among many takes more time
   *  to compile than the others or causes an infinite loop in the compiler.
   */
  private inline val debugPrintProgress = false

  /** Period between progress reports, in ms */
  private inline val printProgressPeriod = 10000

  /** Shows progress if debugPrintProgress is true */
  private def showProgress(proc: => Unit)(using Context): Unit =
    if !debugPrintProgress then proc
    else
      val watchdog = new TimerTask:
        def run() = println(i"[compiling $units]")
      try
        new Timer().schedule(watchdog, printProgressPeriod, printProgressPeriod)
        proc
      finally watchdog.cancel()

  private sealed trait PrintedTree
  private /*final*/ case class SomePrintedTree(phase: String, tree: String) extends PrintedTree
  private object NoPrintedTree extends PrintedTree

  private def printTree(last: PrintedTree)(using Context): PrintedTree = {
    val unit = ctx.compilationUnit
    val fusedPhase = ctx.phase.prev.megaPhase
    val echoHeader = f"[[syntax trees at end of $fusedPhase%25s]] // ${unit.source}"
    val tree = if ctx.isAfterTyper then unit.tpdTree else unit.untpdTree
    val treeString = fusedPhase.show(tree)

    last match {
      case SomePrintedTree(phase, lastTreeString) if lastTreeString == treeString =>
        report.echo(s"$echoHeader: unchanged since $phase")
        last

      case SomePrintedTree(phase, lastTreeString) if ctx.settings.XprintDiff.value || ctx.settings.XprintDiffDel.value =>
        val diff = DiffUtil.mkColoredCodeDiff(treeString, lastTreeString, ctx.settings.XprintDiffDel.value)
        report.echo(s"$echoHeader\n$diff\n")
        SomePrintedTree(fusedPhase.phaseName, treeString)

      case _ =>
        report.echo(s"$echoHeader\n$treeString\n")
        SomePrintedTree(fusedPhase.phaseName, treeString)
    }
  }

  def compileFromStrings(scalaSources: List[String], javaSources: List[String] = Nil): Unit = {
    def sourceFile(source: String, isJava: Boolean): SourceFile = {
      val uuid = java.util.UUID.randomUUID().toString
      val ext = if (isJava) "java" else "scala"
      val name = s"compileFromString-$uuid.$ext"
      SourceFile.virtual(name, source)
    }
    val sources =
      scalaSources.map(sourceFile(_, isJava = false)) ++
       javaSources.map(sourceFile(_, isJava = true))

    compileSources(sources)
  }

  /** Print summary of warnings and errors encountered */
  def printSummary(): Unit = {
    printMaxConstraint()
    printMaxPath()
    val r = runContext.reporter
    if !r.errorsReported then
      profile.printSummary()
    r.summarizeUnreportedWarnings()
    r.printSummary()
  }

  override def reset(): Unit = {
    super[ImplicitRunInfo].reset()
    super[ConstraintRunInfo].reset()
    super[CaptureRunInfo].reset()
    // TODO: This makes `runContext` unusable after being reset.
    myCtx = null.asInstanceOf[Context]
    myUnits = Nil
    myFilesOf = (Nil, Set.empty)
  }

  /** Produces the following contexts, from outermost to innermost
   *
   *    bootStrap:   A context with next available runId and a scope consisting of
   *                 the RootPackage _root_
   *    start        A context with RootClass as owner and the necessary initializations
   *                 for type checking.
   *    imports      For each element of RootImports, an import context
   */
  protected def rootContext(using Context): Context = {
    ctx.initialize()
    ctx.base.setPhasePlan(comp.phases)
    val rootScope = new MutableScope(0)
    val bootstrap = ctx.fresh
      .setPeriod(Period(comp.nextRunId, FirstPhaseId))
      .setScope(rootScope)
    rootScope.enter(ctx.definitions.RootPackage)(using bootstrap)
    var start = bootstrap.fresh
      .setOwner(defn.RootClass)
      .setTyper(new Typer)
      .addMode(Mode.ImplicitsEnabled)
      .setTyperState(ctx.typerState.fresh(ctx.reporter))
    if ctx.settings.YexplicitNulls.value && !Feature.enabledBySetting(nme.unsafeNulls) then
      start = start.addMode(Mode.SafeNulls)
    ctx.initialize()(using start) // re-initialize the base context with start

    // Cache unpickled quote templates for the whole run (keyed by the pickled TASTY
    // bytes, stable per quote-site), so a macro that expands many times unpickles
    // each of its quotes once per run rather than once per expansion.
    QuotesCache.init(start)

    // `this` must be unchecked for safe initialization because by being passed to setRun during
    // initialization, it is not yet considered fully initialized by the initialization checker
    start.setRun(this: @unchecked)
  }

  @scratch @guardedBy("writer") private var myCtx: Context = rootContext(using ictx)

  /** The context created for this run */
  given runContext[Dummy_so_its_a_def]: Context = myCtx
}

object Run {
  /** The typer polls for cancellation every `typerCheckpointMask + 1` typed
   *  trees (a power of two, 1024 by default). A test knob. Tests lower it to make a
   *  cancellation land inside small statements; a run reads it when created.
   */
  @scratch @sharable @volatile var typerCheckpointMask: Int = 1023

  /** Process-wide counts of the cyclic references prevented and the
   *  completions unwound in interactive mode, printed at exit when
   *  `-Ddotty.interactive.cycleStats=true`: the measurement behind the
   *  graceful-degradation design. Measurement scaffolding, read by no one
   *  else: a candidate to drop before upstreaming.
   */
  @interned object CycleStats:
    private val enabled = java.lang.Boolean.getBoolean("dotty.interactive.cycleStats")
    @sharable private val preventedBy = new java.util.concurrent.ConcurrentHashMap[String, Integer]
    @sharable private val unwoundBy = new java.util.concurrent.ConcurrentHashMap[String, Integer]
    @sharable private val preventedCount = new java.util.concurrent.atomic.AtomicInteger
    @sharable private val unwoundCount = new java.util.concurrent.atomic.AtomicInteger
    @sharable private val healedCount = new java.util.concurrent.atomic.AtomicInteger
    if enabled then
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        val top = (m: java.util.concurrent.ConcurrentHashMap[String, Integer]) =>
          m.entrySet.toArray(Array.empty[java.util.Map.Entry[String, Integer]]).sortBy(-_.getValue.intValue).take(12).map(e => s"${e.getKey}=${e.getValue}").mkString(", ")
        System.err.println(s"[interactive cycleStats] cyclic references prevented: ${preventedCount.get} (${top(preventedBy)}); leaked Touched flags healed: ${healedCount.get}; completions unwound by other exceptions: ${unwoundCount.get} (${top(unwoundBy)})")
      }))
    def prevented(sym: Symbol)(using Context): Unit =
      if enabled then
        preventedCount.incrementAndGet()
        preventedBy.merge(sym.name.toString, 1, (a, b) => a + b)
    def healed(sym: Symbol)(using Context): Unit =
      if enabled then healedCount.incrementAndGet()
    def unwound(sym: Symbol, ex: Throwable)(using Context): Unit =
      if enabled then
        unwoundCount.incrementAndGet()
        unwoundBy.merge(s"${sym.name}:${ex.getClass.getSimpleName}", 1, (a, b) => a + b)

  /** A statement left untyped by a targeted run (see `typingTarget`). */
  case class SkippedStat(stat: ast.untpd.Tree, ctx: Context, owner: Symbol, inBlock: Boolean = false)


  case class SubPhase(name: String):
    override def toString: String = name

  class SubPhases(val phase: Phase):
    require(phase.exists)

    private def baseName: String = phase match
      case phase: MegaPhase => phase.shortPhaseName
      case phase => phase.phaseName

    val all = IArray.from(phase.subPhases.map(sub => s"$baseName[$sub]"))

    def next(using Context): Option[SubPhases] =
      val next0 = phase.megaPhase.next.megaPhase
      if next0.exists then Some(SubPhases(next0))
      else None

    def size: Int = all.size

    def subPhase(index: Int) =
      if index < all.size then all(index)
      else baseName


  // Phases and the units they enter advance it, in the run's own write action; only the late units a reader's
  // completion compiles (`currentLateUnitCount`) are counted from a reader.
  @scratch @guardedBy("writer") private class Progress(cb: ProgressCallback, private val run: Run, val initialTraversals: Int):
    export cb.{cancel, isCancelled}

    var totalTraversals: Int = initialTraversals  // track how many phases we expect to run
    var currentUnitCount: Int = 0 // current unit count in the current (sub)phase
    @benignRace("a progress count for the callback: a lost increment from two readers' late compilations skews a percentage")
    var currentLateUnitCount: Int = 0 // current late unit count
    var completedTraversalCount: Int = 0 // completed traversals over all files
    var currentCompletedSubtraversalCount: Int = 0 // completed subphases in the current phase
    var seenPhaseCount: Int = 0 // how many phases we've seen so far

    private var currPhase: Phase = uninitialized      // initialized by enterPhase
    private var subPhases: SubPhases = uninitialized  // initialized by enterPhase
    private var currPhaseName: String = uninitialized // initialized by enterPhase
    private var nextPhaseName: String = uninitialized // initialized by enterPhase

    /** Enter into a new real phase, setting the current and next (sub)phases */
    def enterPhase(newPhase: Phase)(using Context): Unit =
      if newPhase ne currPhase then
        currPhase = newPhase
        subPhases = SubPhases(newPhase)
        tickSubphase()

    def canAdvanceSubPhase: Boolean =
      currentCompletedSubtraversalCount + 1 < subPhases.size

    /** Compute the current (sub)phase name and next (sub)phase name */
    def tickSubphase()(using Context): Unit =
      val index = currentCompletedSubtraversalCount
      val s = subPhases
      currPhaseName = s.subPhase(index)
      nextPhaseName =
        if index + 1 < s.all.size then s.subPhase(index + 1)
        else s.next match
          case None => "<end>"
          case Some(next0) => next0.subPhase(0)
      if seenPhaseCount > 0 then
        refreshProgress()


    /** Counts the number of completed full traversals over files, plus the number of units in the current phase */
    private def currentProgress(): Int =
      completedTraversalCount * work() + currentUnitCount + currentLateUnitCount

    /**Total progress is computed as the sum of
     * - the number of traversals we expect to make over all files
     * - the number of late compilations
     */
    private def totalProgress()(using Context): Int =
      totalTraversals * work() + run.lateFiles.size

    private def work(): Int = run.files.size

    private def requireInitialized(): Unit =
      require((currPhase: Phase | Null) != null, "enterPhase was not called")

    def checkCancellation(): Boolean =
      if Thread.interrupted() then cancel()
      isCancelled()

    /** trace that we are beginning a unit in the current (sub)phase, unless cancelled */
    def tryEnterUnit(unit: CompilationUnit): Boolean =
      if checkCancellation() then false
      else
        requireInitialized()
        cb.informUnitStarting(currPhaseName, unit)
        true

    /** trace the current progress out of the total, in the current (sub)phase, reporting the next (sub)phase */
    def refreshProgress()(using Context): Unit =
      requireInitialized()
      val total = totalProgress()
      if total > 0 && !cb.progress(currentProgress(), total, currPhaseName, nextPhaseName) then
        cancel()

  extension (run: Run | Null)

    /** record that the current phase has begun for the compilation unit of the current Context */
    def enterUnit(unit: CompilationUnit)(using Context): Boolean =
      if run != null then run.didEnterUnit(unit)
      else true // don't check cancellation if we're not tracking progress

    /** check progress cancellation, true if not cancelled */
    def enterRegion()(using Context): Boolean =
      if run != null then run.canProgress()
      else true // don't check cancellation if we're not tracking progress

    /** advance the unit count and record progress in the current phase */
    def advanceUnit()(using Context): Unit =
      if run != null then run.doAdvanceUnit()

    /** if there exists another subphase, switch to it and record progress */
    def enterNextSubphase()(using Context): Unit =
      if run != null then run.tryAdvanceSubPhase()

    /** advance the late count and record progress in the current phase */
    def advanceLate()(using Context): Unit =
      if run != null then run.doAdvanceLate()

    def enrichedErrorMessage: Boolean = if run == null then false else run.myEnrichedErrorMessage
    def enrichErrorMessage(errorMessage: String)(using Context): String =
      if run == null then
        report.enrichErrorMessage(errorMessage)
      else if !run.enrichedErrorMessage then
        run.myEnrichedErrorMessage = true
        report.enrichErrorMessage(errorMessage)
      else
        errorMessage
    def doNotEnrichErrorMessage: Unit =
      if run != null then run.myEnrichedErrorMessage = true
}
