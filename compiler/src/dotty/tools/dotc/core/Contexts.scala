package dotty.tools
package dotc
package core

import interfaces.CompilerCallback
import Decorators.*
import Periods.*
import Names.*
import Phases.*
import Types.*
import Symbols.*
import Scopes.*
import Uniques.*
import ast.Trees.*
import Flags.ParamAccessor
import ast.untpd
import util.{HashSet, NoSource, SimpleIdentityMap, SourceFile, SrcPos, Store, WrappedSourceFile}
import typer.{Implicits, ImportInfo, Nullables, SearchHistory, SearchRoot, TypeAssigner, Typer}
import inlines.Inliner
import Nullables.*
import Implicits.ContextualImplicits
import config.Settings.*
import config.Config
import reporting.*
import reporting.Diagnostic.LoadingFailure
import io.{AbstractFile, PlainFile, Path}
import scala.io.Codec
import collection.mutable
import printing.*
import config.{JavaPlatform, SJSPlatform, Platform, ScalaSettings}
import StdNames.nme
import compiletime.uninitialized
import scala.annotation.internal.{benignRace, cache, confined, guardedBy, interned, publishedBy, scratch, sharable}
import java.lang.invoke.VarHandle
import DenotTransformers.DenotTransformer
import dotty.tools.dotc.profile.Profiler
import dotty.tools.dotc.sbt.interfaces.{IncrementalCallback, ProgressCallback}
import util.Property.Key
import plugins.*
import java.nio.file.InvalidPathException
import dotty.tools.dotc.coverage.Coverage
import dotty.tools.dotc.rewrites.Rewrites
import scala.annotation.tailrec
import dotty.tools.dotc.inlines.Inlines.InlineTraitState

object Contexts {

  private val (compilerCallbackLoc,  store1) = Store.empty.newLocation[CompilerCallback | Null]()
  private val (incCallbackLoc,       store2) = store1.newLocation[IncrementalCallback | Null]()
  private val (printerFnLoc,         store3) = store2.newLocation[Context => Printer](new RefinedPrinter(_))
  private val (settingsStateLoc,     store4) = store3.newLocation[SettingsState]()
  private val (compilationUnitLoc,   store5) = store4.newLocation[CompilationUnit]()
  private val (runLoc,               store6) = store5.newLocation[Run | Null]()
  private val (profilerLoc,          store7) = store6.newLocation[Profiler]()
  private val (notNullInfosLoc,      store8) = store7.newLocation[List[NotNullInfo]]()
  private val (importInfoLoc,        store9) = store8.newLocation[ImportInfo | Null]()
  private val (typeAssignerLoc,     store10) = store9.newLocation[TypeAssigner](TypeAssigner)
  private val (progressCallbackLoc, store11) = store10.newLocation[ProgressCallback | Null]()
  private val (retainedSymbolLoadingFailuresLoc, store12) =
    store11.newLocation[mutable.WeakHashMap[Symbol, LoadingFailure] | Null]()

  private val initialStore = store12

  /** The current context */
  inline def ctx(using ctx: Context): Context = ctx

  /** Run `op` with given context */
  inline def inContext[T](c: Context)(inline op: Context ?=> T): T =
    op(using c)

  /** Execute `op` at given period */
  inline def atPeriod[T](pd: Period)(inline op: Context ?=> T)(using Context): T =
    op(using ctx.fresh.setPeriod(pd))

  /** Execute `op` at given phase id */
  inline def atPhase[T](pid: PhaseId)(inline op: Context ?=> T)(using Context): T =
    op(using ctx.withPhase(pid))

  /** Execute `op` at given phase */
  inline def atPhase[T](phase: Phase)(inline op: Context ?=> T)(using Context): T =
    op(using ctx.withPhase(phase))

  inline def atNextPhase[T](inline op: Context ?=> T)(using Context): T =
    atPhase(ctx.phase.next)(op)

  /** Execute `op` at the current phase if it's before the first transform phase,
   *  otherwise at the last phase before the first transform phase.
   *
   *  Note: this should be used instead of `atPhaseNoLater(ctx.picklerPhase)`
   *  because the later won't work if the `Pickler` phase is not present (for example,
   *  when using `QuoteCompiler`).
   */
  inline def atPhaseBeforeTransforms[T](inline op: Context ?=> T)(using Context): T =
    atPhaseNoLater(firstTransformPhase.prev)(op)

  inline def atPhaseNoLater[T](limit: Phase)(inline op: Context ?=> T)(using Context): T =
    op(using if !limit.exists || ctx.phase <= limit then ctx else ctx.withPhase(limit))

  inline def atPhaseNoEarlier[T](limit: Phase)(inline op: Context ?=> T)(using Context): T =
    op(using if !limit.exists || limit <= ctx.phase then ctx else ctx.withPhase(limit))

  inline def withModeBits[T](mode: Mode)(inline op: Context ?=> T)(using ctx: Context): T =
    op(using if mode != ctx.mode then ctx.fresh.setMode(mode) else ctx)

  inline def withMode[T](mode: Mode)(inline op: Context ?=> T)(using ctx: Context): T =
    withModeBits(ctx.mode | mode)(op)

  inline def withoutMode[T](mode: Mode)(inline op: Context ?=> T)(using ctx: Context): T =
    withModeBits(ctx.mode &~ mode)(op)

  /** A context is passed basically everywhere in dotc.
   *  This is convenient but carries the risk of captured contexts in
   *  objects that turn into space leaks. To combat this risk, here are some
   *  conventions to follow:
   *
   *    - Never let an implicit context be an argument of a class whose instances
   *      live longer than the context.
   *    - Classes that need contexts for their initialization take an explicit parameter
   *      named `initctx`. They pass initctx to all positions where it is needed
   *      (and these positions should all be part of the intialization sequence of the class).
   *    - Classes that need contexts that survive initialization are instead passed
   *      a "condensed context", typically named `cctx` (or they create one). Condensed contexts
   *      just add some basic information to the context base without the
   *      risk of capturing complete trees.
   *    - To make sure these rules are kept, it would be good to do a sanity
   *      check using bytecode inspection with javap or scalap: Keep track
   *      of all class fields of type context; allow them only in allowlisted
   *      classes (which should be short-lived).
   */
  abstract class Context(val base: ContextBase) { thiscontext =>

    protected given Context = this

    def outer: Context

    /** All outer contexts, ending in `base.initialCtx` and then `NoContext` */
    def outersIterator: Iterator[Context] = new Iterator[Context] {
      var current = thiscontext
      def hasNext = current != NoContext
      def next() = { val c = current; current = current.outer; c }
    }

    def period: Period
    def mode: Mode
    def owner: Symbol
    def tree: Tree[?]
    def scope: Scope
    def typerState: TyperState

    /** The per-request scratch this context types under (see `Reader`): the
     *  type comparer stack, the context pools, the recursion guards, and, in
     *  interactive mode, the target and the cancellation checker. The base's
     *  (one for batch compilation; the current thread's in interactive mode),
     *  not a field of the context: contexts are captured and outlive requests.
     */
    final def reader: Reader = base.reader
    def gadt: GadtConstraint = gadtState.gadt
    def gadtState: GadtState
    def inlineTraitState: InlineTraitState
    def searchHistory: SearchHistory
    def source: SourceFile

    /** A map in which more contextual properties can be stored
     *  Typically used for attributes that are read and written only in special situations.
     */
    def moreProperties: Map[Key[Any], Any]

    def property[T](key: Key[T]): Option[T] =
      moreProperties.get(key).asInstanceOf[Option[T]]

    /** A store that can be used by sub-components.
     *  Typically used for attributes that are defined only once per compilation unit.
     *  Access to store entries is much faster than access to properties, and only
     *  slightly slower than a normal field access would be.
     */
    def store: Store

    /** Failures retained for symbols whose loader has installed `NoType`,
     *  or `null` when retention is disabled.
     */
    private[tools] def retainedSymbolLoadingFailures: mutable.WeakHashMap[Symbol, LoadingFailure] | Null =
      store(retainedSymbolLoadingFailuresLoc)

    /** The compiler callback implementation, or null if no callback will be called. */
    def compilerCallback: CompilerCallback | Null = store(compilerCallbackLoc)

    /** The Zinc callback implementation if we are run from Zinc, null otherwise */
    def incCallback: IncrementalCallback | Null = store(incCallbackLoc)

    /** Run `op` if there exists an incremental callback */
    inline def withIncCallback(inline op: IncrementalCallback => Unit): Unit =
      val local = incCallback
      if local != null then op(local)

    def runZincPhases: Boolean =
      def forceRun = settings.YdumpSbtInc.value || settings.YforceSbtPhases.value
      val local = incCallback
      local != null && local.enabled || forceRun

    /** The Zinc compile progress callback implementation if we are run from Zinc or used by presentation compiler, null otherwise */
    def progressCallback: ProgressCallback | Null = store(progressCallbackLoc)

    /** Run `op` if there exists a Zinc progress callback */
    inline def withProgressCallback(inline op: ProgressCallback => Unit): Unit =
      val local = progressCallback
      if local != null then op(local)

    /** The current plain printer */
    def printerFn: Context => Printer = store(printerFnLoc)

    /** A function creating a printer */
    def printer: Printer =
      val pr = printerFn(this)
      if this.settings.YplainPrinter.value then pr.plain else pr

    /** The current settings values */
    def settingsState: SettingsState = store(settingsStateLoc)

    /** The current compilation unit */
    def compilationUnit: CompilationUnit = store(compilationUnitLoc)

    /** The current compiler-run */
    def run: Run | Null = store(runLoc)

    /**  The current compiler-run profiler */
    def profiler: Profiler = store(profilerLoc)

    /** The paths currently known to be not null */
    def notNullInfos: List[NotNullInfo] = store(notNullInfosLoc)

    /** The currently active import info */
    def importInfo: ImportInfo | Null = store(importInfoLoc)

    /** The current type assigner or typer */
    def typeAssigner: TypeAssigner = store(typeAssignerLoc)

    /** The new implicit references that are introduced by this scope */
    // Readers that share a context (a completer's creation context) may both compute it and the later store wins:
    // the values are equivalent, and a `ContextualImplicits` is made of final fields, so either one is safely seen.
    @cache @benignRace("every writer stores an equivalent ContextualImplicits, whose fields are final")
    private var implicitsCache: ContextualImplicits | Null = null
    def implicits: ContextualImplicits = {
      val cached = implicitsCache
      if (cached != null) cached
      else {
        val result = {
          val infoIfImport = importInfoIfImportContext
          val implicitRefs: List[ImplicitRef] =
            if (isClassDefContext)
              try owner.thisType.implicitMembers
              catch {
                case ex: CyclicReference => Nil
              }
            else if (infoIfImport `ne` null) infoIfImport.importedImplicits
            else if (isNonEmptyScopeContext) scope.implicitDecls
            else Nil
          val outerImplicits =
            if (infoIfImport != null && infoIfImport.unimported.exists)
              outer.implicits.exclude(infoIfImport.unimported)
            else
              outer.implicits
          if (implicitRefs.isEmpty) outerImplicits
          else new ContextualImplicits(implicitRefs, outerImplicits, infoIfImport)(this)
        }
        implicitsCache = result
        result
      }
    }

    /** Either the current scope, or, if the current context owner is a class,
     *  the declarations of the current class.
     */
    def effectiveScope(using Context): Scope =
      val myOwner: Symbol | Null = owner
      if myOwner != null && myOwner.isClass then myOwner.asClass.unforcedDecls
      else scope

    def nestingLevel: Int = effectiveScope.nestingLevel

    /** Sourcefile corresponding to given abstract file, memoized */
    def getSource(file: AbstractFile, codec: => Codec = Codec(settings.encoding.value)) = {
      util.Stats.record("Context.getSource")
      base.locked(base.sources().getOrElseUpdate(file, SourceFile(file, settings.sourceroot.value, codec)))(using base.mode)
    }

    /** SourceFile with given path, memoized */
    def getSource(path: String): SourceFile = getFile(path) match
      case None => NoSource
      case Some(file) => getSource(file)

    /** AbstractFile with given path, memoized */
    def getFile(path: String): Option[AbstractFile] =
      base.locked(base.files().get(path))(using base.mode).orElse(
        try
          val file = new PlainFile(Path(path))
          // readers that miss at once store equal files
          base.locked(base.files()(path) = file)(using base.mode)
          Some(file)
        catch
          case ex: InvalidPathException =>
            report.error(em"invalid file path: ${ex.getMessage}")
            None
      )

    // The contexts derived from this one, shared by the readers of a context they share (a completer's creation
    // context). A lost update drops a derived context, which is made again. A derived context's fields are plain, so
    // with concurrent readers its store is preceded by a release fence and a lookup is followed by an acquire fence
    // (as `util.Publish` orders a stamp): the reader that finds it sees it complete.
    @cache @benignRace("a lost update only drops a derived context, which is made again; entries are published after a release fence")
    private var related: SimpleIdentityMap[Phase | SourceFile, Context] | Null = null

    private def concurrentBase: Boolean =
      val b: ContextBase | Null = base // `NoContext` has none
      b != null && b.concurrent

    private def lookup(key: Phase | SourceFile): Context | Null =
      util.Stats.record("Context.related.lookup")
      val map = related
      if map == null then
        related = SimpleIdentityMap.empty
        null
      else
        if concurrentBase then VarHandle.acquireFence()
        map(key)

    private def relate(key: Phase | SourceFile, derived: Context): Unit =
      val map = related.nn
      if concurrentBase then VarHandle.releaseFence()
      related = map.updated(key, derived)

    private def withPhase(phase: Phase, pid: PhaseId): Context =
      util.Stats.record("Context.withPhase")
      val curId = phaseId
      if curId == pid then
        this
      else
        var ctx1 = lookup(phase)
        if ctx1 == null then
          util.Stats.record("Context.withPhase.new")
          ctx1 = fresh.setPhase(pid)
          relate(phase, ctx1)
        ctx1

    final def withPhase(phase: Phase): Context = withPhase(phase, phase.id)
    final def withPhase(pid: PhaseId): Context = withPhase(base.phases(pid), pid)

    final def withSource(source: SourceFile): Context =
      util.Stats.record("Context.withSource")
      if this.source eq source then
        this
      else
        var ctx1 = lookup(source)
        if ctx1 == null then
          util.Stats.record("Context.withSource.new")
          val ctx2 = fresh.setSource(source)
          if ctx2.compilationUnit eq NoCompilationUnit then
            // `source` might correspond to a file not necessarily
            // in the current project (e.g. when inlining library code),
            // so set `mustExistIfNotNull` to false.
            ctx2.setCompilationUnit(CompilationUnit(source, mustExistIfNotNull = false))
          ctx1 = ctx2
          relate(source, ctx2)
        ctx1

    // `creationTrace`-related code. To enable, uncomment the code below and the
    // call to `setCreationTrace()` in this file.
    /*
    /** If -Ydebug is on, the top of the stack trace where this context
     *  was created, otherwise `null`.
     */
    private var creationTrace: Array[StackTraceElement] = uninitialized

    private def setCreationTrace() =
      creationTrace = (new Throwable).getStackTrace().take(20)

    /** Print all enclosing context's creation stacktraces */
    def printCreationTraces() = {
      println("=== context creation trace =======")
      for (ctx <- outersIterator) {
        println(s">>>>>>>>> $ctx")
        if (ctx.creationTrace != null) println(ctx.creationTrace.mkString("\n"))
      }
      println("=== end context creation trace ===")
    }
    */

    /** The current reporter */
    def reporter: Reporter = typerState.reporter

    final def phase: Phase = base.phases(period.firstPhaseId)
    final def runId = period.runId
    final def phaseId = period.firstPhaseId

    final def lastPhaseId = base.phases.length - 1

    /** Does current phase use an erased types interpretation? */
    final def erasedTypes = phase.erasedTypes

    /** Are we in a Java compilation unit? */
    final def isJava: Boolean = compilationUnit.isJava

    /** Is current phase after TyperPhase? */
    final def isAfterTyper = base.isAfterTyper(phase)
    final def isAfterInlining = base.isAfterInlining(phase)
    final def isTyper = base.isTyper(phase)

    /** Is this a context for the members of a class definition? */
    def isClassDefContext: Boolean =
      owner.isClass && (owner ne outer.owner)

    /** Is this a context that introduces an import clause? */
    def isImportContext: Boolean =
      val i = importInfo
      (i ne null) && (i ne outer.importInfo)

    /** If this a context that introduces an import clause, then `importInfo`, else `null`. */
    def importInfoIfImportContext: ImportInfo | Null =
      val i = importInfo
      if (i ne null) && (i ne outer.importInfo) then i else null

    /** Is this a context that introduces a non-empty scope? */
    def isNonEmptyScopeContext: Boolean =
      (this.scope ne outer.scope) && !this.scope.isEmpty

    /** Is this a context for typechecking an inlined body? */
    def isInlineContext: Boolean =
      typer.isInstanceOf[Inliner#InlineTyper]

    /** The next outer context whose tree is a template or package definition
     *  Note: Currently unused
    def enclTemplate: Context = {
      var c = this
      while (c != NoContext && !c.tree.isInstanceOf[Template[?]] && !c.tree.isInstanceOf[PackageDef[?]])
        c = c.outer
      c
    }*/

    /** The context for a supercall. This context is used for elaborating
     *  the parents of a class and their arguments.
     *  The context is computed from the current class context. It has
     *
     *  - as owner: The primary constructor of the class
     *  - as outer context: The context enclosing the class context
     *  - as scope: type parameters, the parameter accessors,
     *    the dummy capture parameters and the context bound companions in the class context,
     *
     *  The reasons for this peculiar choice of attributes are as follows:
     *
     *  - The constructor must be the owner, because that's where any local methods or closures
     *    should go.
     *  - The context may not see any class members (inherited or defined), and should
     *    instead see definitions defined in the outer context which might be shadowed by
     *    such class members. That's why the outer context must be the outer context of the class.
     *  - At the same time the context should see the parameter accessors of the current class,
     *    that's why they get added to the local scope. An alternative would have been to have the
     *    context see the constructor parameters instead, but then we'd need a final substitution step
     *    from constructor parameters to class parameter accessors.
     */
    def superCallContext: Context =
      val locals = owner.typeParams
          ++ owner.asClass.unforcedDecls.filter: sym =>
              sym.is(ParamAccessor) || sym.isContextBoundCompanion || sym.isDummyCaptureParam
      superOrThisCallContext(owner.primaryConstructor, newScopeWith(locals*))

    /** The context for the arguments of a this(...) constructor call.
     *  The context is computed from the local auxiliary constructor context.
     *  It has
     *
     *   - as owner: The auxiliary constructor
     *   - as outer context: The context enclosing the enclosing class context
     *   - as scope: The parameters of the auxiliary constructor.
     */
    def thisCallArgContext: Context = {
      val constrCtx = outersIterator.dropWhile(_.outer.owner == owner).next()
      superOrThisCallContext(owner, constrCtx.scope)
        .setTyperState(typerState)
        .setGadtState(gadtState)
        .setInlineTraitState(inlineTraitState)
        .fresh
        .setScope(this.scope)
    }

    /** Is this a super call context?
     *  This is the case if we are in a primary constructor and
     *  the outer context has as owner the owner of the enclosing class.
     *  The enclosing class is `owner.owner`, hence `outer.owner == owner.owner.owner`.
     */
    def isSuperCallContext: Boolean =
      owner.isPrimaryConstructor && outer.owner == owner.owner.owner

    /** The super- or this-call context with given owner and locals. */
    private def superOrThisCallContext(owner: Symbol, locals: Scope): FreshContext = {
      val classCtx = outersIterator.dropWhile(!_.isClassDefContext).next()
      classCtx.outer.fresh.setOwner(owner)
        .setScope(locals)
        .setMode(classCtx.mode)
    }

    /** The context of expression `expr` seen as a member of a statement sequence */
    def exprContext(stat: Tree[?], exprOwner: Symbol): Context =
      if (exprOwner == this.owner) this
      else if (untpd.isSuperConstrCall(stat) && this.owner.isClass) superCallContext
      else fresh.setOwner(exprOwner)

    /** A new context that summarizes an import statement */
    def importContext(imp: Import[?], sym: Symbol): FreshContext =
       fresh.setImportInfo(ImportInfo(sym, imp.selectors, imp.expr))

    /** Is the debug option set? */
    def debug: Boolean = base.settings.Ydebug.value

    /** Is the verbose option set? */
    def verbose: Boolean = base.settings.verbose.value

    /** Should use colors when printing? */
    def useColors: Boolean =
      base.settings.color.value == "always"

    def withColors: FreshContext =
      fresh.setSetting(ctx.settings.color, "always")

    def withoutColors: FreshContext =
      fresh.setSetting(ctx.settings.color, "never")

    /** Is the explicit nulls option set? */
    def explicitNulls: Boolean = base.settings.YexplicitNulls.value

    /** Is the flexible types option set? */
    def flexibleTypes: Boolean = base.settings.YexplicitNulls.value && !base.settings.YnoFlexibleTypes.value

    /** Is the flexify tasty option set? */
    def flexifyTasty: Boolean = base.settings.YexplicitNulls.value && base.settings.YflexifyTasty.value

    /** Is the best-effort option set? */
    def isBestEffort: Boolean = base.settings.YbestEffort.value

    /** Is the with-best-effort-tasty option set? */
    def withBestEffortTasty: Boolean = base.settings.YwithBestEffortTasty.value

    /** Were any best effort tasty dependencies used during compilation? */
    def usedBestEffortTasty: Boolean = base.usedBestEffortTasty

    /** Confirm that a best effort tasty dependency was used during compilation. */
    def setUsedBestEffortTasty(): Unit = base.usedBestEffortTasty = true

    /** Is either the best-effort option set or .betasty files were used during compilation? */
    def tolerateErrorsForBestEffort = isBestEffort || usedBestEffortTasty

    /** A fresh clone of this context embedded in this context. */
    def fresh: FreshContext = freshOver(this)

    /** A fresh clone of this context embedded in the specified `outer` context. */
    def freshOver(outer: Context): FreshContext =
      FreshContext(base).init(outer, this).setTyperState(this.typerState)

    final def withOwner(owner: Symbol): Context =
      if (owner ne this.owner) fresh.setOwner(owner) else this

    final def withTyperState(typerState: TyperState): Context =
      if typerState ne this.typerState then fresh.setTyperState(typerState) else this

    final def withUncommittedTyperState: Context =
      withTyperState(typerState.uncommittedAncestor)

    /**
     * Ensures recursive operations obey the fuel limit, and throws user-friendly errors when they do not.
     *
     * If a position is meaningful, pass one.
     * If not, make sure this is called in a stack where a previous call had a meaningful position,
     * or the final error won't have one.
     */
    final inline def handleRecursive[T](title: String, details: RecursiveOperationDetails, pos: SrcPos | Null = null)(inline block: T): T =
      // This method is hot, as it must be called every so often in potentially recursive operations
      // to catch stack overflows before they actually happen.
      // Thus, it's important for it not to allocate or do any unnecessary work,
      // which is why `reader.recursiveOperations` is a preallocated array of mutable classes in which we can copy the arguments.
      // We use the fact that `reader.recursiveOperations.length` is set to the max fuel to not have to explicitly load it.
      // Also, we support `-Xno-enrich-error-messages` by setting `reader.recursiveDepth` to `Int.MinValue`, ensuring the throw check never fires.
      // These two checks don't add overhead because accessing `ops` needs `depth` to be bounds-checked anyway.
      val r = reader
      val depth = r.recursiveDepth
      val ops = r.recursiveOperations
      if depth >= ops.length then
        throw RecursionOverflow(ops, title, details, pos)
      if depth >= 0 then
        r.recursiveDepth = depth + 1
        ops(depth).title = title
        ops(depth).details = details
        ops(depth).pos = pos
      try block
      finally r.recursiveDepth = depth

    final def withProperty[T](key: Key[T], value: Option[T]): Context =
      if (property(key) == value) this
      else value match {
        case Some(v) => fresh.setProperty(key, v)
        case None => fresh.dropProperty(key)
      }

    def typer: Typer = this.typeAssigner match {
      case typer: Typer => typer
      case _ => new Typer
    }

    override def toString: String =
      def iinfo(using Context) =
        val info = ctx.importInfo
        if (info == null) "" else i"${info.selectors}%, %"
      def cinfo(using Context) =
        val core = s"  owner = ${ctx.owner}, scope = ${ctx.scope}, import = $iinfo"
        if (ctx ne NoContext) && (ctx.implicits ne ctx.outer.implicits) then
          s"$core, implicits = ${ctx.implicits}"
        else
          core
      s"""Context(
         |${outersIterator.map(ctx => cinfo(using ctx)).mkString("\n\n")})""".stripMargin

    def settings: ScalaSettings            = base.settings
    def definitions: Definitions           = base.definitions
    def platform: Platform                 = base.platform
    def pendingUnderlying: util.HashSet[Type]      = reader.pendingUnderlying
    def uniqueNamedTypes: Uniques.NamedTypeUniques = base.uniqueNamedTypes
    def uniques: util.WeakHashSet[Type]            = base.uniques

    def initialize()(using Context): Unit = base.initialize()

    protected def resetCaches(): Unit =
      implicitsCache = null
      related = null

    /** Reuse this context as a fresh context nested inside `outer`
     *  But keep the typerstate, this one has to be set explicitly if needed.
     */
    def reuseIn(outer: Context): this.type
  }

  /** A condensed context provides only a small memory footprint over
   *  a Context base, and therefore can be stored without problems in
   *  long-lived objects.
  abstract class CondensedContext extends Context {
    override def condensed = this
  }
  */

  /** A fresh context allows selective modification
   *  of its attributes using the with... methods.
   */
  @scratch
  class FreshContext(base: ContextBase) extends Context(base) {
    util.Stats.record("Context.fresh")

    // A fresh context's fields are set by whoever makes it (`fresh.setX`, `init`, `FreshContext.initial`) before it is
    // handed on, and not changed after: so readers that share a context (a completer's creation context) see its
    // fields as set. The exceptions are a pooled context, which only its reader's pool reuses (`reuseIn`) and which
    // must not be retained, and `_period`, which `SingleDenotation._goForward` sets and restores on the context it is
    // given while a denotation transformer runs.

    @publishedBy("init")
    private var _outer: Context = uninitialized
    def outer: Context = _outer

    @publishedBy("init") private var _period: Period = uninitialized // set while the context is built, before it is shared
    final def period: Period = _period

    @publishedBy("init")
    private var _mode: Mode = uninitialized
    final def mode: Mode = _mode

    @publishedBy("init")
    private var _owner: Symbol = uninitialized
    final def owner: Symbol = _owner

    @publishedBy("init")
    private var _tree: Tree[?] = uninitialized
    final def tree: Tree[?] = _tree

    @publishedBy("init")
    private var _scope: Scope = uninitialized
    final def scope: Scope = _scope

    @publishedBy("init")
    private var _typerState: TyperState = uninitialized
    final def typerState: TyperState = _typerState

    @publishedBy("init")
    private var _gadtState: GadtState = uninitialized
    final def gadtState: GadtState = _gadtState
    
    @publishedBy("init")
    private var _inlineTraitState: InlineTraitState = uninitialized
    final def inlineTraitState: InlineTraitState = _inlineTraitState

    @publishedBy("init")
    private var _searchHistory: SearchHistory = uninitialized
    final def searchHistory: SearchHistory = _searchHistory

    @publishedBy("init")
    private var _source: SourceFile = uninitialized
    final def source: SourceFile = _source

    @publishedBy("init")
    private var _moreProperties: Map[Key[Any], Any] = uninitialized
    final def moreProperties: Map[Key[Any], Any] = _moreProperties

    @publishedBy("init")
    private var _store: Store = uninitialized
    final def store: Store = _store

   /** Initialize all context fields, except typerState, which has to be set separately
     *  @param  outer   The outer context
     *  @param  origin  The context from which fields are copied
     */
    private[Contexts] def init(outer: Context, origin: Context): this.type = {
      _outer = outer
      _period = origin.period
      _mode = origin.mode
      _owner = origin.owner
      _tree = origin.tree
      _scope = origin.scope
      _gadtState = origin.gadtState
      _inlineTraitState = origin.inlineTraitState
      _searchHistory = origin.searchHistory
      _source = origin.source
      _moreProperties = origin.moreProperties
      _store = origin.store
      this
    }

    def reuseIn(outer: Context): this.type =
      resetCaches()
      init(outer, outer)

    def setPeriod(period: Period): this.type =
      util.Stats.record("Context.setPeriod")
      //assert(period.firstPhaseId == period.lastPhaseId, period)
      this._period = period
      this

    def setMode(mode: Mode): this.type =
      util.Stats.record("Context.setMode")
      this._mode = mode
      this

    def setOwner(owner: Symbol): this.type =
      util.Stats.record("Context.setOwner")
      assert(owner != NoSymbol)
      this._owner = owner
      this

    def setTree(tree: Tree[?]): this.type =
      util.Stats.record("Context.setTree")
      this._tree = tree
      this

    def setScope(scope: Scope): this.type =
      this._scope = scope
      this

    def setNewScope: this.type =
      util.Stats.record("Context.setScope")
      this._scope = newScope
      this

    def setTyperState(typerState: TyperState): this.type =
      this._typerState = typerState
      this
    def setNewTyperState(): this.type =
      setTyperState(typerState.fresh(committable = true))
    def setExploreTyperState(): this.type =
      setTyperState(typerState.fresh(committable = false))
    def setReporter(reporter: Reporter): this.type =
      setTyperState(typerState.fresh().setReporter(reporter))

    def setTyper(typer: Typer): this.type =
      this._scope = typer.scope
      setTypeAssigner(typer)

    def setGadtState(gadtState: GadtState): this.type =
      util.Stats.record("Context.setGadtState")
      this._gadtState = gadtState
      this
    def setFreshGADTBounds: this.type =
      setGadtState(gadtState.fresh)

    def setInlineTraitState(inlineTraitState: InlineTraitState): this.type =
      util.Stats.record("Context.setInlineTraitState")
      this._inlineTraitState = inlineTraitState
      this

    def setSearchHistory(searchHistory: SearchHistory): this.type =
      util.Stats.record("Context.setSearchHistory")
      this._searchHistory = searchHistory
      this

    def setSource(source: SourceFile): this.type =
      util.Stats.record("Context.setSource")
      this._source = source
      this

    private def setMoreProperties(moreProperties: Map[Key[Any], Any]): this.type =
      util.Stats.record("Context.setMoreProperties")
      this._moreProperties = moreProperties
      this

    private def setStore(store: Store): this.type =
      util.Stats.record("Context.setStore")
      this._store = store
      this

    def setCompilationUnit(compilationUnit: CompilationUnit): this.type = {
      setSource(compilationUnit.source)
      updateStore(compilationUnitLoc, compilationUnit)
    }


    def setCompilerCallback(callback: CompilerCallback): this.type = updateStore(compilerCallbackLoc, callback)
    def setIncCallback(callback: IncrementalCallback): this.type = updateStore(incCallbackLoc, callback)
    def setProgressCallback(callback: ProgressCallback): this.type = updateStore(progressCallbackLoc, callback)
    def setPrinterFn(printer: Context => Printer): this.type = updateStore(printerFnLoc, printer)
    def setSettings(settingsState: SettingsState): this.type = updateStore(settingsStateLoc, settingsState)
    def setRun(run: Run | Null): this.type = updateStore(runLoc, run)
    def setProfiler(profiler: Profiler): this.type = updateStore(profilerLoc, profiler)
    def setNotNullInfos(notNullInfos: List[NotNullInfo]): this.type = updateStore(notNullInfosLoc, notNullInfos)
    def setImportInfo(importInfo: ImportInfo): this.type =
      importInfo.mentionsFeature(nme.unsafeNulls) match
        case Some(true) =>
          setMode(this.mode &~ Mode.SafeNulls)
        case Some(false) if ctx.settings.YexplicitNulls.value =>
          setMode(this.mode | Mode.SafeNulls)
        case _ =>
      updateStore(importInfoLoc, importInfo)
    def setTypeAssigner(typeAssigner: TypeAssigner): this.type = updateStore(typeAssignerLoc, typeAssigner)
    private[tools] def setRetainedSymbolLoadingFailures(
        failures: mutable.WeakHashMap[Symbol, LoadingFailure] | Null
    ): this.type = updateStore(retainedSymbolLoadingFailuresLoc, failures)

    def setProperty[T](key: Key[T], value: T): this.type =
      setMoreProperties(moreProperties.updated(key, value))

    def dropProperty(key: Key[?]): this.type =
      setMoreProperties(moreProperties - key)

    def addLocation[T](initial: T): Store.Location[T] = {
      val (loc, store1) = store.newLocation(initial)
      setStore(store1)
      loc
    }

    def addLocation[T](): Store.Location[T] = {
      val (loc, store1) = store.newLocation[T]()
      setStore(store1)
      loc
    }

    def updateStore[T](loc: Store.Location[T], value: T): this.type =
      setStore(store.updated(loc, value))

    def setPhase(pid: PhaseId): this.type = setPeriod(Period(runId, pid))
    def setPhase(phase: Phase): this.type = setPeriod(Period(runId, phase.start, phase.end))

    def setSetting[T](setting: Setting[T], value: T): this.type =
      setSettings(setting.updateIn(settingsState, value))

    def setDebug: this.type = setSetting(base.settings.Ydebug, true)
  }

  object FreshContext:
    /** Defines an initial context with given context base and possible settings. */
    def initial(base: ContextBase, settingsGroup: SettingGroup): Context =
      val c = new FreshContext(base)
      c._outer = NoContext
      c._period = InitialPeriod
      c._mode = Mode.None
      c._typerState = TyperState.initialState()
      c._owner = NoSymbol
      c._tree = untpd.EmptyTree
      c._moreProperties = Map(MessageLimiter -> DefaultMessageLimiter())
      c._scope = EmptyScope
      c._source = NoSource
      c._store = initialStore
          .updated(settingsStateLoc, settingsGroup.defaultState)
          .updated(notNullInfosLoc, Nil)
          .updated(compilationUnitLoc, NoCompilationUnit)
          .updated(profilerLoc, Profiler.NoOp)
      c._searchHistory = new SearchRoot(perThread = base.concurrent)
      c._gadtState = GadtState(GadtConstraint.empty)
      c._inlineTraitState = InlineTraitState()
      c
  end FreshContext

  extension (ctx: Context)
    /** Get the original compilation unit, ignoring any highlighting wrappers. */
    @tailrec
    def originalCompilationUnit: CompilationUnit =
      val cu = ctx.compilationUnit
      if cu.source.name == SyntaxHighlighting.VirtualSourceName then ctx.outer.originalCompilationUnit
      else cu

  extension (c: Context)
    def addNotNullInfo(info: NotNullInfo) =
      if c.explicitNulls then c.withNotNullInfos(c.notNullInfos.extendWith(info)) else c

    def addNotNullRefs(refs: Set[TermRef]) =
      if c.explicitNulls then c.addNotNullInfo(NotNullInfo(refs, Set())) else c

    def withNotNullInfos(infos: List[NotNullInfo]): Context =
      if !c.explicitNulls || (c.notNullInfos eq infos) then c else c.fresh.setNotNullInfos(infos)

  // TODO: Fix issue when converting ModeChanges and FreshModeChanges to extension givens
  extension (c: Context)
    final def withModeBits(mode: Mode): Context =
      if (mode != c.mode) c.fresh.setMode(mode) else c

    final def addMode(mode: Mode): Context = withModeBits(c.mode | mode)
    final def retractMode(mode: Mode): Context = withModeBits(c.mode &~ mode)

  extension (c: FreshContext)
    final def addMode(mode: Mode): c.type = c.setMode(c.mode | mode)
    final def retractMode(mode: Mode): c.type = c.setMode(c.mode &~ mode)

  /** Run `op` with a pool-allocated context that has an ExploreTyperState. */
  inline def explore[T](inline op: Context ?=> T)(using Context): T =
    exploreInFreshCtx(op)

  /** Run `op` with a pool-allocated FreshContext that has an ExploreTyperState. */
  inline def exploreInFreshCtx[T](inline op: FreshContext ?=> T)(using Context): T =
    val pool = ctx.reader.exploreContextPool
    val nestedCtx = pool.next()
    try op(using nestedCtx)
    finally
      nestedCtx.typerState.reporter.asInstanceOf[ExploringReporter].reset()
      pool.free()

  /** Run `op` with a pool-allocated context that has a fresh typer state.
   *  Commit the typer state if `commit` applied to `op`'s result returns true.
   */
  inline def withFreshTyperState[T](inline op: Context ?=> T, inline commit: T => Context ?=> Boolean)(using Context): T =
    val pool = ctx.reader.freshTSContextPool
    val nestedCtx = pool.next()
    try
      val result = op(using nestedCtx)
      if commit(result)(using nestedCtx) then
        nestedCtx.typerState.commit()
      result
    finally
      // the pooled typer state is reused: committable again whatever happened
      // (`commit` clears the flag before it merges, and a merge can throw)
      nestedCtx.typerState.setCommittable(true)
      pool.free()

  /** Run `op` with a pool-allocated context that has the given `owner`. */
  inline def runWithOwner[T](owner: Symbol)(inline op: Context ?=> T)(using Context): T =
    if Config.reuseOwnerContexts then
      val pool = ctx.reader.generalContextPool
      try op(using pool.next().setOwner(owner).setTyperState(ctx.typerState))
      finally pool.free()
    else
      op(using ctx.fresh.setOwner(owner))

  /** The type comparer of the kind created by `maker` to be used.
   *  This is the currently active type comparer CMP if
   *   - CMP is associated with the current context, and
   *   - CMP is of the kind created by maker or maker creates a plain type comparer.
   *  Note: plain TypeComparers always take on the kind of the outer comparer if they are in the same context.
   *  In other words: tracking or explaining is a sticky property in the same context.
   */
  def comparer(using Context): TypeComparer =
    util.Stats.record("comparing")
    val reader = ctx.reader
    if reader.comparersInUse > 0
       && (reader.comparers(reader.comparersInUse - 1).comparerContext eq ctx)
    then
      reader.comparers(reader.comparersInUse - 1).currentInstance
    else
      val result =
        if reader.comparersInUse < reader.comparers.size then
          reader.comparers(reader.comparersInUse)
        else
          val result = TypeComparer(ctx)
          reader.comparers += result
          result
      reader.comparersInUse += 1
      result.init(ctx)
      result

  def currentComparer(using Context): TypeComparer =
    val reader = ctx.reader
    if reader.comparersInUse > 0 then
      reader.comparers(reader.comparersInUse - 1)
    else
      comparer

  inline def comparing[T](inline op: TypeComparer => T)(using Context): T =
    util.Stats.record("comparing")
    val saved = ctx.reader.comparersInUse
    try op(comparer)
    finally ctx.reader.comparersInUse = saved
  end comparing

  @sharable val NoContext: Context = new FreshContext(null.asInstanceOf[ContextBase]) {
    override val implicits: ContextualImplicits = new ContextualImplicits(Nil, null, null)(this: @unchecked)
    override val importInfo: ImportInfo | Null = null
    setSource(NoSource)
  }

  /** A context base defines state and associated methods that exist once per
   *  logical compiler instance.
   */
  class ContextBase(sharing: Sharing) extends ContextState(sharing)
                       with Phases.PhasesBase
                       with Plugins {

    /** A base for batch compilation (and Java callers: the sbt bridge). */
    def this() = this(Sharing.Batch)

    // several readers need a reader per thread, which only the interactive driver's base binds (it overrides `reader`)
    assert(!sharing.concurrent || getClass != classOf[ContextBase], s"$sharing on a base that does not bind readers to threads")


    val settings: ScalaSettings = ScalaSettings

    /** The initial context */
    val initialCtx: Context = FreshContext.initial(this: @unchecked, settings)

    /** The platform, initialized by `initPlatform()`. Set by `initialize`, which a run calls as it begins (an
     *  interactive run is a write action, with no reader), and read after. */
    @cache @publishedBy("init")
    private var _platform: Platform | Null = null

    /** The platform */
    def platform: Platform = {
      val p = _platform
      if p == null then
        throw new IllegalStateException(
            "initialize() must be called before accessing platform")
      p
    }

    protected def newPlatform(using Context): Platform =
      if (settings.scalajs.value) new SJSPlatform
      else new JavaPlatform

    /** The loader that loads the members of _root_ */
    def rootLoader(root: TermSymbol)(using Context): SymbolLoader = platform.rootLoader(root)

    /** The standard definitions */
    val definitions: Definitions = new Definitions

    // Set up some phases to get started */
    usePhases(List(SomePhase), FreshContext(this))

    /** Initializes the `ContextBase` with a starting context.
     *  This initializes the `platform`, the `recursiveOperations` based on max fuel, and the `definitions`.
     */
    def initialize()(using Context): Unit = {
      // In interactive mode (REPL/IDE), preserve the existing platform if already initialized.
      // This is important because :dep/:jar commands add JARs to the platform's classpath,
      // and we must not lose those when a new Run is created for each input.
      // In non-interactive mode, always create a fresh platform to preserve original behavior.
      if _platform == null || !ctx.mode.is(Mode.Interactive) then
        _platform = newPlatform
      platform.init()
      // See `Context.handleRecursive` for an explanation of these values
      maxFuel = ctx.settings.XmaxFuel.value
      enrichErrorMessages = !ctx.settings.XnoEnrichErrorMessages.value
      reader.initFuel(maxFuel, enrichErrorMessages) // the batch reader, or the initializing thread's
      definitions.init()
    }

    def fusedContaining(p: Phase): Phase =
      allPhases.find(_.period.containsPhaseId(p.id)).getOrElse(NoPhase)
  }

  class ContextPool:
    protected def fresh()(using Context): FreshContext =
      FreshContext(ctx.base).init(ctx, ctx)

    // the pools are a reader's (`Reader.exploreContextPool` and its siblings)
    @scratch @confined private var inUse: Int = 0
    @scratch @confined private var pool = new mutable.ArrayBuffer[FreshContext]

    def next()(using Context): FreshContext =
      val base = ctx.base
      import base.*
      val nestedCtx =
        if inUse < pool.size then
          pool(inUse).reuseIn(ctx)
        else
          val c = fresh()
          pool += c
          c
      inUse += 1
      nestedCtx

    final def free(): Unit =
      inUse -= 1
  end ContextPool

  class TSContextPool extends ContextPool:
    override def next()(using Context) =
      val nextCtx = super.next()
      nextCtx.typerState.init(ctx.typerState, ctx.typerState.constraint)
      nextCtx

  class FreshTSContextPool extends TSContextPool:
    override protected def fresh()(using Context) =
      super.fresh().setTyperState(ctx.typerState.fresh(committable = true))

  class ExploreContextPool extends TSContextPool:
    override protected def fresh()(using Context) =
      val ts = TyperState()
        .setReporter(ExploringReporter())
        .setCommittable(false)
      super.fresh().setTyperState(ts)

  /** The essential mutable state of a context base, collected into a common class */
  class ContextState(
    /** How this base is shared between threads, chosen when it is made: `Sharing.Batch` but for the interactive
     *  driver's (see `Sharing` and docs/_docs/internals/interactive-multi-reader.md). It picks the interners, the
     *  caches that are maps and the symbol id counter below, and whether completions take `completionLock`
     *  (serialised readers) or claim `completionRoots` (parallel readers). */
    final val sharing: Sharing) extends Sharing.Guard {

    /** `sharing.concurrent` and `sharing.parallel`, copied to the base: a hot test reads one field of it. */
    final val concurrent: Boolean = sharing.concurrent
    final val parallel: Boolean = sharing.parallel

    /** `sharing.mode`, copied to the base: the given of the `dotty.tools.sharing` operations (`dotc.sharingMode`),
     *  a value class over `concurrent`, so that its test reads one field of the base, as a test of `concurrent` does. */
    final val mode: _root_.dotty.tools.sharing.Mode = sharing.mode

    /** Interactive mode with several readers: the lock every completion
     *  (`SymDenotation.completeFrom`), every bring-forward of a denotation
     *  to a new run (`Denotation.current`) and every computation of an
     *  inherited cache (base classes, member names) runs under, so that the
     *  lazy facts of the symbol table have one writer at a time while bodies
     *  are typed concurrently. Reentrant: a completion forces others. Not
     *  used in batch compilation.
     */
    @interned @publishedBy("init")
    val completionLock: CompletionLock = CompletionLock(this)

    /** The owners of completion roots with parallel readers (see
     *  `Sharing.Parallel`, `CompletionRoots`).
     */
    @interned @publishedBy("init")
    val completionRoots: CompletionRoots = CompletionRoots(this)

    /** What a `Sharing.claim` takes on a concurrent base (`dotc.claimRegistry`): the root's claim with parallel
     *  readers, the completion lock (as the key's `ClaimKind` says) with serialised ones. */
    @interned @publishedBy("init")
    val claimRegistry: _root_.dotty.tools.sharing.ClaimRegistry =
      if parallel then completionRoots else completionLock

    /** The stamp of the structure of published scopes: bumped by every change to a scope after its owner's
     *  completion published it (`MutableScope.published`). A cache computed from scopes can record it, and is
     *  current while it has not moved.
     */
    private val myStructureEpoch = java.util.concurrent.atomic.AtomicInteger()
    private[dotc] def structureEpoch: Int = myStructureEpoch.get
    private[dotc] def bumpStructureEpoch(): Unit = myStructureEpoch.incrementAndGet()

    /** The thread in a write action of the interactive driver, if any: on a concurrent base, the only thread that
     *  may change a published scope (`MutableScope.changed`).
     */
    @scratch @publishedBy("volatile") @volatile private[dotc] var writeActionThread: Thread | Null = null

    // Symbols state

    /** Counter for unique symbol ids */
    private var _nextSymId: Int = 0
    private val _concurrentSymIds = new java.util.concurrent.atomic.AtomicInteger(0)
    def nextSymId: Int =
      if concurrent then _concurrentSymIds.incrementAndGet()
      else { _nextSymId += 1; _nextSymId }

    /** Sources and Files that were loaded. Readers of a concurrent base share them, so they are reached only with
     *  the evidence of this base's monitor (`Sharing.serialised`, `locking`).
     */
    @interned @guardedBy("this")
    private[core] val sources = guarded(util.HashMap[AbstractFile, SourceFile]())
    @cache @guardedBy("this")
    private[core] val files = guarded(util.HashMap[String, AbstractFile]())

    /** Cache for magic offset header lookups, scoped to this compiler instance
     *  so that concurrent compilers in the same classloader don't share stale entries. */
    @cache @guardedBy("magicHeaderCache")
    private[dotc] val magicHeaderCache: util.HashMap[SourceFile, WrappedSourceFile.MagicHeaderInfo] = util.HashMap()

    /** Was best effort file used during compilation? */
    @scratch @benignRace("only ever set to true, by any reader that loads a best effort file")
    private[core] var usedBestEffortTasty = false

    /** If coverage option is used, it stores all instrumented statements (for InstrumentCoverage).
     * We need this information to be persisted across different runs, so it's stored here.
     */
    // written and read by the InstrumentCoverage phase, which runs after the typer on one thread
    @scratch @confined
    private[dotc] var coverage: Coverage | Null = null

    /** The reader of batch compilation: the comparer stack, the context
     *  pools, the recursion guards (see `Reader`). `InteractiveContextBase`
     *  overrides `reader` with the one bound to the current thread.
     */
    val initialReader: Reader = Reader.initial()

    /** The reader the current thread types under. */
    def reader: Reader = initialReader

    /** The fuel settings of a reader (`Context.handleRecursive`), known once
     *  `ContextBase.initialize` has run.
     */
    // set by `ContextBase.initialize`, which a run calls as it begins (an interactive run is a write action)
    @cache @publishedBy("init")
    private[dotc] var maxFuel: Int = Reader.initialFuel
    @cache @publishedBy("init")
    private[dotc] var enrichErrorMessages: Boolean = true

    /** A reader with this base's fuel and the given cancellation checker. */
    def newReader(cancelChecker: () => Boolean): Reader = new Reader(maxFuel, enrichErrorMessages, cancelChecker)

    // Types state
    /** A table for hash consing unique types */
    private[core] val uniques: Uniques = sharing.newUniques()

    /** A table for hash consing unique applied types */
    private[dotc] val uniqueAppliedTypes: AppliedUniques = sharing.newAppliedUniques()

    /** A table for hash consing unique named types */
    private[core] val uniqueNamedTypes: NamedTypeUniques = sharing.newNamedTypeUniques()

    // hash-consed: readers that make one at once store the same instance
    @cache @benignRace("every writer stores the interned instance")
    var emptyTypeBounds: TypeBounds | Null = null
    @cache @benignRace("every writer stores the interned instance")
    var emptyWildcardBounds: WildcardType | Null = null

    /** A map from ErrorType to associated message. We use this map
     *  instead of storing messages directly in ErrorTypes in order
     *  to avoid space leaks - the message usually captures a context.
     */
    @cache
    private[core] val errorTypeMsg: mutable.Map[Types.ErrorType, Message] = sharing.newMap()

    // Phases state

    // The phases are set by `usePhases` and `setPhasePlan`, which a run calls as it begins (an interactive run is a
    // write action, with no reader), and read after.
    @cache @publishedBy("init")
    private[core] var phasesPlan: List[List[Phase]] = uninitialized

    /** Phases by id */
    @cache @publishedBy("init")
    private[dotc] var phases: Array[Phase] = uninitialized

    /** Phases with consecutive Transforms grouped into a single phase, Empty array if fusion is disabled */
    @cache @publishedBy("init")
    private[core] var fusedPhases: Array[Phase] = Array.empty[Phase]

    /** Next denotation transformer id */
    @cache @publishedBy("init")
    private[core] var nextDenotTransformerId: Array[Periods.PhaseId] = uninitialized

    @cache @publishedBy("init")
    private[core] var denotTransformers: Array[DenotTransformer] = uninitialized

    /** Cached -Yno-double-bindings setting. This is accessed from `setDenot`, which
     *  is fairly hot, so we don't want to lookup the setting each time it is called.
     */
    @cache @benignRace("set to true from the -Yno-double-bindings setting as a run begins, never reset")
    private[dotc] var checkNoDoubleBindings = false

    // Reporters state (the margin of `trace` output is `Reader.indent`)
    protected[dotc] val indentTab: String = "  "

    @cache @benignRace("every writer stores an equal (setting, WConf) pair, an immutable value")
    private[dotc] var wConfCache: (List[String], WConf) = uninitialized

    private[dotc] val patched: Rewrites.PatchedFiles = Rewrites.newPatchedFiles()

    def reset(): Unit =
      uniques.clear()
      uniqueAppliedTypes.clear()
      uniqueNamedTypes.clear()
      emptyTypeBounds = null
      emptyWildcardBounds = null
      errorTypeMsg.clear()
      alwaysLocked {
        sources().clear()
        files().clear()
      }
      initialReader.reset()  // forces re-evaluation of top and bottom classes in TypeComparer

    // Test that access is single threaded

    /** The thread on which `checkSingleThreaded was invoked last */
    // only `Run.compileUnits` calls the check, and not in interactive mode
    @scratch @confined
    @sharable private var thread: Thread | Null = null

    /** Check that we are on the same thread as before */
    def checkSingleThreaded(): Unit =
      if (thread == null) thread = Thread.currentThread()
      else assert(thread == Thread.currentThread(), "illegal multithreaded access to ContextBase")
  }
}
