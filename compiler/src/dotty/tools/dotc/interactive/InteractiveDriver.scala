package dotty.tools
package dotc
package interactive

import java.net.URI
import scala.collection.*
import scala.jdk.CollectionConverters.*

import dotty.tools.dotc.sbt.interfaces.ProgressCallback

import ast.{Trees, tpd, untpd}
import config.*
import core.*, core.Decorators.*
import Contexts.*, Names.*, NameOps.*, Symbols.*, SymDenotations.*, Trees.*, Types.*
import reporting.*
import util.*
import dotty.tools.io.AbstractFile
import Spans.{Span, NoSpan}
import scala.annotation.meta.field
import scala.annotation.internal.{cache, confined, guardedBy, publishedBy, scratch, treeState}

private class InteractiveContextBase(sourcePackage: CachedLogicalPackage, sharing: Sharing) extends ContextBase(sharing) {

  override protected def newPlatform(using Context): Platform = {
      if (settings.scalajs.value) new SJSPlatform(sourcePackage.value)
      else new JavaPlatform(sourcePackage.value)
  }

  /** The reader of each thread that types under this base (see `Reader`): a
   *  request binds its reader to its thread (`bindReader`), and a thread that
   *  never did (a provider reading the typed tree after a request) gets one
   *  of its own. Batch compilation keeps the base's single reader, a field
   *  read; here it is a `ThreadLocal` read, in interactive mode only.
   */
  private lazy val threadReader: ThreadLocal[Reader] = ThreadLocal.withInitial(() => newReader(Reader.neverCancelled))

  override def reader: Reader = threadReader.get.nn

  def bindReader(reader: Reader): Unit = threadReader.set(reader)

  /** Forget this thread's state on this base: its reader and its implicit
   *  search state. A thread's `ThreadLocal` map holds each value strongly and
   *  the values reach this base, which owns the keys, so a thread that outlives
   *  the driver (a pool thread of the presentation compiler) would keep the
   *  whole symbol table alive.
   */
  def unbindThread(): Unit =
    threadReader.remove()
    initialCtx.searchHistory match
      case root: typer.SearchRoot => root.clearThread()
      case _ =>
}

// Assumes the context does not change; only use if this is true!
class CachedLogicalPackage(extractor: Context ?=> Option[LogicalPackage]) {
  // shared by every driver given it (`CachedLogicalPackage.none` is), so two bases may ask at once: the extractor
  // runs once, and the package tree it builds is published by the monitor (`ParsedLogicalPackage` is read-only after)
  @cache @guardedBy("this") private var cached: Option[LogicalPackage] | Null = null
  def value(using Context): Option[LogicalPackage] = synchronized:
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
    // several requests at once (see `reading`): the base is concurrent, its
    // readers parallel with `-Yinteractive-parallel`; chosen here, before
    // the settings are parsed, since the base's interners depend on it
    val parallel = settings.exists(s => s == "-Yinteractive-parallel" || s == "-Yinteractive-parallel:true")
    val sharing = if parallel then Sharing.Parallel else Sharing.Serialised
    new InteractiveContextBase(logicalRootPackage, sharing).initialCtx

  override def sourcesRequired: Boolean = false

  /** The driver's sticky cancellation: set by `cancel()` (which `Run.Progress`
   *  calls when it notices a thread interrupt) and reset when the next
   *  request starts typing. The client's own cancellation test for a request
   *  is on the request's `Reader` (`withCancelChecker`). `isCancelled` does
   *  not consume the interrupt flag itself: `Run.Progress.checkCancellation`
   *  already does, and it would hide the interrupt from `cancel()`.
   */
  @scratch @publishedBy("volatile") @volatile private var myCancelled = false

  private val myProgressCallback: ProgressCallback = new ProgressCallback:
    // a pending writer cancels readers, not the writer that holds the lock
    // (another writer queued behind it would otherwise empty its run)
    override def isCancelled(): Boolean = myCancelled || (writerPending && !regime.writeLock.isHeldByCurrentThread)
    override def cancel(): Unit = myCancelled = true

  // ---- Readers and writers ----
  //
  // Several requests may be served at once (step 3 of the multi-reader
  // notes): a *reader* is a request served from the session or resumed into
  // one of its skipped statements, and runs under the read lock; a *writer*
  // is anything that changes what readers read (a fresh run on new content, a
  // re-index of a statement whose typing was cancelled, the end of a run),
  // raises `writerPending`, which every reader polls at its typer checkpoints
  // (`Reader.checkCancelledTyping`, through the progress callback) and unwinds
  // from with a `CancelledCompilation`, then takes the write lock and runs
  // alone. A reader that a writer pre-empted is retried by `reading` once the
  // writer is done, so its caller never sees the writer's cancellation, only
  // its own checker's. Completions, bring-forwards and inherited caches take
  // the base's `completionLock` (one lazy fact of the symbol table computed at
  // a time); the session's own bookkeeping (which statements are skipped,
  // typed or being typed, the splice, the diagnostics) is under the session's
  // monitor. What is concurrent is the typing of bodies.

  @treeState private val regime = new java.util.concurrent.locks.ReentrantReadWriteLock(true)
  @treeState @publishedBy("volatile") @volatile private var writerPending = false

  /** Run `body` as a reader; retried when a writer pre-empted it. A
   *  cancellation unwinding from `body` runs `afterCancel` once the read lock
   *  is released (it may write), before the retry or the rethrow.
   */
  private def reading[T](body0: => T)(afterCancel: () => Unit = () => (), serialiseWhole: Boolean = true): T =
    // Without `-Yinteractive-parallel` every read action runs under the
    // completion lock, so readers are serialised with completions and with
    // each other (writers still pre-empt): a reader outside it would see a
    // completion's intermediate info and typer states shared with completers
    // (findings I42, I48). With it, completions hide their intermediate state
    // from other readers themselves (step 4 of the multi-reader notes).
    def body: T = if serialiseWhole then serialised(CompletionLock.readAction)(body0) else body0
    while true do
      val lock = regime.readLock
      lock.lock()
      val reader = base.reader
      reader.cancelledByChecker = false
      val cancelled =
        try
          val result = body
          reader.resultStamp = myWrites
          return result
        catch case ex: CompilationUnit.CancelledCompilation => ex
        finally lock.unlock()
      afterCancel()
      // the request's own cancellation (its checker, or a thread interrupt)
      // unwinds to the caller; a writer's pre-emption is retried once the
      // writer is done (the pending flag may be down again by now: the reader
      // remembers which it was)
      if reader.cancelledByChecker || myCancelled then throw cancelled
    throw new IllegalStateException("unreachable")

  /** Run `body` as the only thread on the session. */
  private def writing[T](body: => T): T =
    val lock = regime.writeLock
    if lock.isHeldByCurrentThread then body
    else
      writerPending = true
      lock.lock()
      try
        writerPending = false
        base.writeActionThread = Thread.currentThread
        myWrites += 1 // only the writer writes it
        val result = body
        base.reader.resultStamp = myWrites
        result
      finally
        base.writeActionThread = null
        lock.unlock()

  /** Run `op` under the base's completion lock. A completer writes its type
   *  vars and constraints into its creation context's typer state, the run's
   *  shared one, under that lock; a commit of a resumed statement's typer
   *  state into the same shared state must take the same lock, or the two
   *  writes race and the constraint graph can be left inconsistent (finding
   *  I42: an `OrderingConstraint` that `instantiateTypeVars` looped over until
   *  the heap ran out). Lock order: the session's monitor, then this lock.
   *  A typer state per completion (step 4, §3.1) removes the sharing.
   */
  private def completionLocked[T](op: => T): T = completionLocked(CompletionLock.commit)(op)

  private def completionLocked[T](what: CompletionLock.What)(op: => T): T =
    base.completionLock.locked(what)(op)

  /** The context a provider's read action runs in. With parallel readers
   *  a typer state of its own, never committed: reads instantiate type
   *  variables too (completion's implicit search, `isFullyDefined`), and two
   *  readers in the run's root typer state raced on it (finding I52: a
   *  completion that saw `filter` at `Nothing => Boolean`, a type variable
   *  whose owner a commit had lost).
   */
  private def readCtx: Context =
    if parallelReaders then
      val c = myCtx.fresh.setNewTyperState()
      // This state stands where the run's root state stands with one reader, so its reporter
      // is not one that holds "unreported errors": `interpolateTypeVars` returns early when
      // `hasUnreportedErrors`, which a buffering reporter with an outer one answers true to
      // after any candidate that does not apply (an extension method tried in a completion),
      // and the type variables of every later application stay uninstantiated (finding I89).
      // The outer reporter stays: the typer asks `errorsReported` through it, and a state
      // without one recovers from a nested error differently (`StoreReporter(null)` made
      // `Infer.d.leng` answer nothing after `val d = summon[Show[Int]].sh`).
      c.typerState.setReporter(new StoreReporter(myCtx.typerState.reporter, fromTyperState = true) { override def hasUnreportedErrors = false })
      c
    else myCtx

  /** `op` under the completion lock unless readers are parallel
   *  (`-Yinteractive-parallel`): a request's read action is one unit, a
   *  completing pass's statement is one unit (a pass that held the lock for
   *  all of its statements would make every request wait for the whole pass).
   */
  private def serialised[T](what: => CompletionLock.What)(op: => T): T =
    if !parallelReaders then completionLocked(what)(op) else op

  /** Abandon the statements whose typing a cancellation unwound (a re-index:
   *  a write), before a pre-empted or cancelled reader is retried or unwinds.
   */
  private def abandonPending(s: Session): Unit =
    if s.hasPendingAbandons then writing {
      // only while `s` is still the session: a writer that ran meanwhile
      // replaced it, and a re-index in the old run would meet denotations
      // brought forward to the new one ("invalid in run")
      if isCurrent(s) then s.abandonPending()
    }

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

  // written only by write actions (a run, `initialize`), read by anyone
  @treeState @publishedBy("volatile") @volatile private var myCtx: Context = myInitCtx

  /** Does `runUpTo` type only the path to its target? Off with
   *  `-Yno-targeted-requests`, in which case every request types the whole
   *  file as `run` does: the gate on the behaviour of this branch.
   */
  val targetedRequests: Boolean = !myInitCtx.settings.YnoTargetedRequests.value(using myInitCtx)

  /** Do requests type concurrently, each completion with an owner
   *  (`-Yinteractive-parallel`)? Off, they are serialised with completions.
   */
  val parallelReaders: Boolean = myInitCtx.base.parallel
  assert(parallelReaders == myInitCtx.settings.YinteractiveParallel.value(using myInitCtx),
    s"-Yinteractive-parallel read from the arguments (${myInitCtx.base.sharing}) differs from the parsed setting")

  /** Does the session survive an edit inside the body of a definition with a
   *  declared type (`-Yinteractive-body-edits`, docs §12)? Then a whole-file
   *  run keeps a session too.
   */
  val bodyEdits: Boolean = targetedRequests && myInitCtx.settings.YinteractiveBodyEdits.value(using myInitCtx)
  /** The context of the last run, for a provider that reads the typed tree after a
   *  request. With parallel readers the run's root typer state is read-only (a
   *  commit into it fails: "commit of ... into the run's root state"), so the
   *  context is one with a typer state of its own, made once per run: the same
   *  context on every call, as with one reader, and a state that these
   *  providers, which a presentation compiler runs one request at a time, share
   *  as they shared the root state. A provider that reads inside `readUpTo`
   *  gets a state per read from its context argument.
   */
  def currentCtx: Context =
    if !parallelReaders then myCtx
    else
      val root = myCtx
      myProviderCtx match
        case (r, c) if r eq root => c
        case _ => synchronized:
          myProviderCtx match
            case (r, c) if r eq root => c
            case _ =>
              val c = readCtx
              myProviderCtx = (root, c)
              c

  @volatile private var myProviderCtx: (Context, Context) | Null = null

  // ---- Readers ----
  //
  // Each request types under a `Reader` of its own (its comparer stack and
  // context pools, its target and skipped statements, its completion
  // bookkeeping, its cancellation checker; see `Reader`), bound to the thread
  // of the request on the context base (`InteractiveContextBase.reader`), so
  // that every context the request types under, captured earlier or not,
  // reaches it. The reader stays bound to the thread after the request, so
  // that the providers that read the typed tree afterwards, and the tests
  // that read a request's bookkeeping, find it. Still one reader at a time:
  // the readers of concurrent requests are step 3 of the multi-reader notes.

  private def base: InteractiveContextBase = myInitCtx.base.asInstanceOf[InteractiveContextBase]

  /** The nesting of `withCancelChecker` scopes on this thread: a request
   *  outside any scope gets a fresh reader of its own (`requestReader`). Per
   *  thread: a shared counter let one thread's open scope keep another
   *  thread on its previous reader, whose checker belonged to an earlier,
   *  cancelled request (finding I47).
   */
  @scratch @confined private val myScopeDepth: ThreadLocal[Integer] = ThreadLocal.withInitial(() => Integer.valueOf(0))

  /** The reader of the last request that typed (not of a scope that only
   *  reads), for the x-ray's status thread and for the tests that read a
   *  request's bookkeeping afterwards from another job; not used by the
   *  compiler.
   */
  @scratch @publishedBy("volatile") @volatile private var myLastReader: Reader = base.reader
  def readerOfLastRequest: Reader = myLastReader

  /** The holder of the completion lock and the root of its work, or "" (for
   *  the x-ray).
   */
  def completionOwnerNow: String =
    if parallelReaders then base.completionRoots.holdersNow else base.completionLock.holderNow

  /** The reader bound to the calling thread (inside `withCancelChecker`, the
   *  scope's), for tools that watch a request from another thread.
   */
  def readerOfThisThread: Reader = base.reader

  private def bindReader(check: () => Boolean): Reader =
    val r = base.newReader(check)
    base.bindReader(r)
    r

  /** Run `body` with `check` as the cancellation test polled by the typer
   *  (see `Reader.checkCancelledTyping`), on a fresh reader bound to this
   *  thread. A run started inside `body` is aborted with
   *  `CompilationUnit.CancelledCompilation` once `check` returns true. An
   *  outermost scope unbinds the thread from the base when it ends, so that no
   *  per-thread state survives a request on a thread that lives on (see
   *  `InteractiveContextBase.unbindThread`); what the request computed is read
   *  inside the scope, or under `currentCtx` and `readerOfLastRequest`.
   */
  def withCancelChecker[T](check: () => Boolean)(body: => T): T =
    val previous = base.reader
    bindReader(check)
    val depth = myScopeDepth.get.nn.intValue
    myScopeDepth.set(depth + 1)
    try if depth == 0 && parallelReaders then servedCurrent(check)(body) else body
    finally
      if depth > 0 then
        myScopeDepth.set(depth)
        base.bindReader(previous)
      else
        myScopeDepth.remove()
        base.unbindThread()

  /** The number of write actions so far: a request's result stamp (`Reader.resultStamp`). After freezing, a change to
   *  a published scope is made only in a write action (`MutableScope.changed`), so the count covers the structure
   *  epoch as well as the runs and the splices; the epoch itself also moves when a reader invalidates a class's own
   *  member caches, which would serve requests again for nothing.
   */
  @scratch @publishedBy("volatile") @volatile private var myWrites: Int = 0

  /** Requests served again because a write action ran after what they read, and requests handed their result after
   *  `MaxStaleRetries` such tries (for the tests and the log).
   */
  val staleRetries = java.util.concurrent.atomic.AtomicInteger()
  val staleServed = java.util.concurrent.atomic.AtomicInteger()
  private val MaxStaleRetries = 3

  /** A provider reads the typed tree after `runUpTo` or `run` has left the read lock (through `currentCtx` and
   *  `compilationUnits`), so with parallel readers a write action may replace what it read before its result goes to
   *  the editor. The result carries the write count of the last lock its request left (`resultStamp`), and is
   *  computed again, on a fresh reader, while a write ran after it.
   */
  private def servedCurrent[T](check: () => Boolean)(body: => T): T =
    var tries = 0
    while true do
      val result = body
      val stamp = base.reader.resultStamp
      if stamp < 0 || stamp == myWrites then return result
      if tries == MaxStaleRetries then
        staleServed.incrementAndGet()
        return result
      tries += 1
      staleRetries.incrementAndGet()
      bindReader(check)
    throw new IllegalStateException("unreachable")

  /** The reader of the request that starts: the scope's, or a fresh one. */
  private def requestReader(): Reader =
    val r = if myScopeDepth.get.nn.intValue == 0 then bindReader(Reader.neverCancelled) else base.reader
    myLastReader = r
    r

  private val compiler: Compiler = new InteractiveCompiler

  // The maps of open files: written by writers and by the splice of a
  // resume, read by the providers at any time; the readers get a snapshot.
  @treeState @guardedBy("myOpenedFiles") private val myOpenedFiles = new mutable.LinkedHashMap[URI, SourceFile].withDefaultValue(NoSource)
  def openedFiles: Map[URI, SourceFile] = myOpenedFiles.synchronized(immutable.VectorMap.from(myOpenedFiles)).withDefaultValue(NoSource)

  @treeState @guardedBy("myOpenedTrees") private val myOpenedTrees = new mutable.LinkedHashMap[URI, List[SourceTree]].withDefaultValue(Nil)
  def openedTrees: Map[URI, List[SourceTree]] = myOpenedTrees.synchronized(immutable.VectorMap.from(myOpenedTrees)).withDefaultValue(Nil)

  @treeState @guardedBy("myCompilationUnits") private val myCompilationUnits = new mutable.LinkedHashMap[URI, CompilationUnit]
  def compilationUnits: Map[URI, CompilationUnit] = myCompilationUnits.synchronized(immutable.VectorMap.from(myCompilationUnits))

  initialize()

  def run(uri: URI, sourceCode: String): List[Diagnostic] = run(uri, SourceFile.virtual(uri, sourceCode))

  def run(uri: URI, source: SourceFile): List[Diagnostic] =
    readAll(uri, source)((_, _, diags) => diags)

  /** Type the whole of `source` (`run`) and apply `read` to the typed unit
   *  under the read lock, so that no writer replaces the unit before `read`
   *  has seen it.
   */
  def readAll[T](uri: URI, source: SourceFile, visible: Span = NoSpan)(read: (Context, CompilationUnit, List[Diagnostic]) => T): T =
    requestReader()
    adoptEditWriting(uri, source)
    while true do
      val served: Option[T] = reading {
        sessionOf(uri) match
          case s: Session if targetedRequests && s.isOn(uri, source) && s.isResumable && !s.hasPendingAbandons =>
            // A whole-file run on the content of the session completes the session
            // instead of replacing it, so the requests after it are served from the
            // run that holds the whole file typed.
            val diags =
              if s.hasSkipped || !s.typingNow.isEmpty then logged(s"run ${fileNameOf(s.uri)} (completes the session)")(s.complete(visible))(sessionSummary(uri))
              else s.currentDiagnostics
            // statements abandoned meanwhile (another reader's cancellation)
            // are not in the unit: a write first, then again
            if s.poisoned || s.hasPendingAbandons then None
            else Some(serialised(CompletionLock.readAction)(read(readCtx, s.unit, diags)))
          case _ => None
      }(afterCancel = () => { val s = sessionOf(uri); if s != null then abandonPending(s) }, serialiseWhole = false)
      served match
        case Some(t) => return t
        case None =>
          val s = sessionOf(uri)
          if s != null && s.hasPendingAbandons then abandonPending(s)
          else writtenAndRead(uri, run(uri, source, NoSpan), read) match
            case Some(t) => return t
            case None => // the run failed fatally (no unit): as before, again
    throw new IllegalStateException("unreachable")

  /** A fresh run for this request and the read of its result, in one write
   *  action. Reading the result afterwards under the read lock let another
   *  request's run replace it in between, and two readers on different
   *  content then replaced each other's runs forever (finding I43: a
   *  livelock, run ids past 65,535 wrapping to 0, the heap filled with runs).
   *  The read is the provider's, usually short; it holds off the next writer,
   *  as IntelliJ's write action holds off readers.
   */
  private def writtenAndRead[T](uri: URI, ran: => List[Diagnostic], read: (Context, CompilationUnit, List[Diagnostic]) => T): Option[T] =
    writing {
      val diags = ran
      myCompilationUnits.synchronized(myCompilationUnits.get(uri)).map(unit => read(readCtx, unit, diags))
    }

  /** The contexts of the definitions a run typed (`Typer.RecordedDefContexts`): readers typing skipped statements
   *  concurrently put into it, a body edit (a write action) gets from it.
   */
  private class SyncIdentityHashMap[K, V] extends java.util.IdentityHashMap[K, V]:
    override def put(key: K, value: V): V = synchronized(super.put(key, value))
    override def get(key: Object | Null): V = synchronized(super.get(key))

  /** The state of a targeted run (see `runUpTo`): the run, the unit with
   *  the partially typed tree, the statements the run skipped (with the
   *  context to type them in), and the diagnostics so far. Its operations
   *  mutate only the session, its unit and its run.
   */
  private class Session(val uri: URI, val source: SourceFile, val run: Run,
      val reporter: SessionReporter, val unit: CompilationUnit,
      @(treeState @field) @(guardedBy @field)("writer") recorded: java.util.IdentityHashMap[Symbol, (Context, Symbol)] | Null,
      shared: Boolean = false, takesNopos: Boolean = true):
    private def reader: Reader = base.reader

    // `recorded` is read by `bodyEdit`, a write action; the map is filled by readers' typing (`Typer`), so it is a `SyncIdentityHashMap`

    // The session's bookkeeping is read and written under the session's
    // monitor; the typing of a claimed statement happens outside it.

    @guardedBy("this") private var skipped: List[Run.SkippedStat] = Nil

    /** The skipped statements a reader is typing right now (`claim`). */
    // with the thread of the reader that claimed each: a claim whose owner
    // is gone is a leak, not a statement being typed (I61)
    private val typing = new java.util.IdentityHashMap[Run.SkippedStat, Thread]()

    /** The statements whose typing a cancellation unwound, to be abandoned
     *  (replaced or poisoning the session) by a writer (`abandonPending`).
     */
    private var pendingAbandons: List[Run.SkippedStat] = Nil

    /** Set when the typing of a skipped statement was aborted and the statement
     *  could not be replaced: typing consumes the attachments of untyped trees
     *  (`SymOfTree`, `TypedAhead`, `ExpandedTree`), so it can not be typed a
     *  second time, and the next request starts a fresh run.
     */
    @volatile var poisoned: Boolean = false

    def setInitial(skipped: List[Run.SkippedStat]): Unit = synchronized {
      this.skipped = skipped
    }

    /** The union of the diagnostic stores of the run's reporter (§12,
     *  "Diagnostics"): all of them, or, with several sessions on one run
     *  (`openAll`), this file's.
     */
    def currentDiagnostics: List[Diagnostic] =
      if shared then reporter.of(unit.source, takesNopos) else reporter.all
    def skippedNow: List[Run.SkippedStat] = synchronized(skipped)
    def hasSkipped: Boolean = synchronized(skipped.nonEmpty)
    def hasPendingAbandons: Boolean = synchronized(pendingAbandons.nonEmpty)

    def skippedAt(target: Span): Option[Run.SkippedStat] = synchronized(skipped.find(_.stat.span.contains(target)))

    /** What a request at `target` finds: the statements it touches typed
     *  already, skipped statements to resume into (the one a point lands in,
     *  every one a range touches; claimed for this reader together: removed
     *  from `skipped`, in `typing`), or one that another reader is typing.
     */
    def claim(target: Span): Session.Claim = synchronized {
      // a statement whose typing a cancellation unwound is neither typed nor
      // skipped until a write replaces it: it is not "typed" (finding I56,
      // a read of a unit without the statement, an empty answer)
      if pendingAbandons.exists(_.stat.span.touches(target)) then Session.Claim.Abandoned
      else if typing.keySet.iterator().asScala.exists(_.stat.span.touches(target)) then Session.Claim.Wait
      else skipped.filter(_.stat.span.touches(target)) match
        case Nil => Session.Claim.Typed
        case sks =>
          skipped = skipped.filterNot(sk => sks.exists(_ eq sk))
          sks.foreach(typing.put(_, Thread.currentThread))
          Session.Claim.Claimed(sks)
    }

    /** Wait until no reader is typing the statement at `target`, polling the
     *  cancellation of this request (a pending writer's or its own).
     */
    def awaitTyping(target: Span): Unit = synchronized {
      val t0 = System.nanoTime()
      try awaitTyping0(target)
      finally reader.statementWaitNanos += System.nanoTime() - t0
    }

    private def awaitTyping0(target: Span): Unit = {
      val since = System.nanoTime()
      while typing.keySet.iterator().asScala.exists(_.stat.span.touches(target)) do
        if reader.cancelChecker() then
          reader.cancelledByChecker = true
          throw CompilationUnit.CancelledCompilation()
        if writerPending then throw CompilationUnit.CancelledCompilation()
        giveUpOnLeakedClaims(_.stat.span.touches(target), since)
        wait(20)
    }

    /** The longest a reader waits for a statement another reader is typing
     *  before it takes the claim for leaked (a backstop: a claim's owner that
     *  is alive releases it when it is done, however it ends).
     */
    private val claimWaitLimitNanos = 60_000_000_000L

    /** A claim on a statement matching `which` whose owner thread is no
     *  longer alive, or that has been waited on for longer than
     *  `claimWaitLimitNanos`, was leaked: no reader will ever release it. The
     *  statement is in neither the typed tree nor `skipped`, so the session
     *  is poisoned and the waiting request is retried, which starts a fresh
     *  run (I61: a request that waited on such a claim waited for ever,
     *  holding the session's monitor). Called under the session's monitor.
     */
    private def giveUpOnLeakedClaims(which: Run.SkippedStat => Boolean, waitingSince: Long): Unit =
      val overdue = System.nanoTime() - waitingSince > claimWaitLimitNanos
      val leaked = typing.entrySet.iterator().asScala.filter(e => which(e.getKey) && (overdue || !e.getValue.isAlive)).map(_.getKey).toList
      if leaked.nonEmpty then
        leaked.foreach(typing.remove)
        poisoned = true
        notifyAll()
        throw CompilationUnit.CancelledCompilation()

    /** A claimed statement whose typing ended in an exception other than a
     *  cancellation (a type error escaping, an assertion, a stack overflow):
     *  its state is unknown, so it is not given back as skipped. The claim
     *  is released, so that no reader waits on it, and the session poisoned:
     *  the next request starts a fresh run (I61).
     */
    private def releaseFailed(sk: Run.SkippedStat): Unit = synchronized {
      typing.remove(sk)
      poisoned = true
      notifyAll()
    }

    /** Give a claimed statement back, skipped again. */
    def unclaim(sk: Run.SkippedStat): Unit = synchronized {
      typing.remove(sk)
      skipped = sk :: skipped
      notifyAll()
    }

    /** The skipped statements that `target`, an offset or a range, touches. */
    def skippedTouching(target: Span): List[Run.SkippedStat] = synchronized(skipped.filter(_.stat.span.touches(target)))

    /** Is this the session of `uri` on the content of `source`? */
    def isOn(uri: URI, source: SourceFile): Boolean =
      this.uri == uri && this.source.textContent() == source.textContent()

    /** A run that had a completion unwound by an exception of unknown effect
     *  (not a cycle, not a cancellation) holds state nothing should be served
     *  or resumed from, and a poisoned session holds a statement that is
     *  neither typed nor skipped: the request starts a fresh run.
     */
    def isResumable: Boolean = !run.isTainted && !poisoned

    /** Move the statements the reader skipped since into the session's (under the monitor). */
    private def collectSkipped(): Unit =
      skipped = skipped ++ reader.skippedStats.toList
      reader.skippedStats.clear()

    /** A new stretch of typing in this run starts: forget a cancellation of
     *  the previous request and the completions it unwound.
     */
    private def beginTyping(): Unit =
      myCancelled = false
      reader.cancelledCompletions.clear()

    /** The typing of `sk` was aborted by a cancellation: the statement can
     *  not be typed a second time (typing consumes the attachments of the
     *  untyped trees and the nested typers of the methods). Whatever was
     *  typed before the abort stays typed; the statements skipped inside the
     *  aborted one are its own and go with it. Replace it, or poison the
     *  session. A re-index is a write: the reader that was cancelled records
     *  the statement (`deferAbandon`), and `abandonPending` runs as a writer.
     *  The cancelled completions it replaces are the cancelled reader's
     *  (`Reader.cancelledCompletions`), so the writer is that reader's thread.
     */
    private def abandon(sk: Run.SkippedStat): Unit =
      // what the aborted typing and the unwound completions reported goes:
      // the statement is replaced and typed again (a block statement's are
      // its member's, and stay)
      if !sk.inBlock then
        given Context = sk.ctx
        val keys = mutable.Set.empty[reporting.Provenance]
        ctx.typer.foreachIndexedDef(sk.stat)((_, sym) => keys ++= List(reporting.Provenance.Stat(sym), reporting.Provenance.Completion(sym)))
        reporter.drop(unit.source, keys.toSet)
      if !replacePoisoned(sk) || !replaceUnwound(sk.stat.span)(using sk.ctx) then poisoned = true

    private def deferAbandon(sk: Run.SkippedStat): Unit = synchronized {
      typing.remove(sk)
      pendingAbandons = sk :: pendingAbandons
      notifyAll()
    }

    def abandonPending(): Unit =
      val sks = synchronized { val r = pendingAbandons; pendingAbandons = Nil; r }
      sks.foreach(abandon)

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
    def complete(visible: Span = NoSpan): List[Diagnostic] =
      beginTyping()
      reader.clearTargeting() // no target: each statement typed whole
      // In the serialised mode (`serialised`) a statement is claimed and typed
      // under the completion lock as one unit: a pass that claimed a statement
      // and then waited for the lock deadlocked with a request that held the
      // lock and waited for that statement (finding I51).
      val passWhat = CompletionLock.What("pass types the statement at", CompletionLock.Label(() => lineOf(currentlyTyping)))
      var done = false
      while !done && !poisoned do
        if !serialised(passWhat)(claimAndType(visible)) then
          // nothing left to claim: wait for the statements other readers are
          // typing, outside the lock
          synchronized {
            val since = System.nanoTime()
            while skipped.isEmpty && !typing.isEmpty do
              if reader.cancelChecker() then
                reader.cancelledByChecker = true
                throw CompilationUnit.CancelledCompilation()
              if writerPending then throw CompilationUnit.CancelledCompilation()
              giveUpOnLeakedClaims(_ => true, since)
              wait(20)
            done = skipped.isEmpty
          }
      currentDiagnostics

    /** Claim the next skipped statement (those overlapping `visible` first,
     *  the editor's viewport, then in source order) and type it. One at a time,
     *  so that a request landing in a statement this pass has not reached
     *  claims and types it itself, concurrently, instead of waiting for the
     *  whole pass; one landing in the statement being typed waits for it.
     *  False when there was nothing to claim.
     */
    private def claimAndType(visible: Span): Boolean =
      val next: Run.SkippedStat | Null = synchronized {
        if skipped.isEmpty then null
        else
          def rank(sk: Run.SkippedStat) = if visible.exists && sk.stat.span.overlaps(visible) then 0 else 1
          val sk = skipped.minBy(sk => (rank(sk), sk.stat.span.start))
          skipped = skipped.filterNot(_ eq sk)
          typing.put(sk, Thread.currentThread)
          sk
      }
      if next == null then false
      else
        val sk = next
        assertOwnSource(sk)
        liveContext(sk.ctx) match
          case None =>
            // no typer state left to type it in: it stays skipped, and the
            // next request starts fresh
            synchronized { skipped = sk :: skipped; typing.remove(sk); notifyAll() }
            poisoned = true
          case Some(ctx0) =>
            // in its own typer state, committed once typed (see `resumeIn`)
            val ctx1 = ctx0.fresh.setNewTyperState()
            var released = false
            try
              currentlyTyping = sk.stat.span
              val stats = ctx1.typer.typedStats(sk.stat :: Nil, sk.owner)(using ctx1)._1
              synchronized {
                endStatementState(ctx1, ctx0)
                retyped(sk, stats)(using ctx1)
                spliceAll(List((sk, stats)))(using sk.ctx)
                collectSkipped()
                typing.remove(sk)
                completedByPass += 1
                notifyAll()
              }
              released = true
            catch case ex: CompilationUnit.CancelledCompilation =>
              // the ones typed so far are kept; the aborted one is replaced
              // or poisons the session, as in a resume
              deferAbandon(sk)
              released = true
              throw ex
            finally
              currentlyTyping = NoSpan
              // any other exception: the claim is not left behind
              if !released then releaseFailed(sk)
        true

    /** The member statement `sk` is typed (as `stats`): the stale
     *  diagnostics of its definitions go, its own replace them (§12).
     */
    private def retyped(sk: Run.SkippedStat, stats: List[Tree])(using Context): Unit =
      if !sk.inBlock then stats.foreach {
        case t: MemberDef if t.symbol.exists => reporter.retyped(reporting.Provenance.Stat(t.symbol))
        case _ =>
      }

    private def lineOf(sp: Span): String =
      if !sp.exists then "-" else s"line ${source.offsetToLine(sp.start) + 1} (${sp.end - sp.start} chars)"

    /** For the x-ray: the statement a pass is typing now, and how many it has
     *  typed (introspection, not used by the compiler).
     */
    @volatile var currentlyTyping: Span = NoSpan
    @volatile var completedByPass: Int = 0
    def typingNow: List[Span] = synchronized(typing.keySet.iterator().asScala.map(_.stat.span).toList)

    /** A skipped statement is a statement of the session's own source: one
     *  from an inlined macro's source would splice at the wrong offsets.
     */
    private def assertOwnSource(sk: Run.SkippedStat): Unit =
      assert(sk.ctx.source eq unit.source, s"skipped statement at ${sk.stat.span} is from ${sk.ctx.source}, not the session's ${unit.source}")

    /** Type the claimed skipped statements `sks` (the one a point target
     *  lands in, or all those a range target touches) in the run and contexts
     *  they were skipped in, with `target` as the target for the statements
     *  nested in them, and splice the results into the unit's typed tree in
     *  one pass. `None` when one has no typer state left to resume in: the
     *  claims are given back and the caller starts a fresh run.
     */
    def resume(sks: List[Run.SkippedStat], target: Span): Option[List[Diagnostic]] =
      val inOrder = sks.sortBy(_.stat.span.start)
      val live = inOrder.map(sk => liveContext(sk.ctx))
      if live.exists(_.isEmpty) then { inOrder.foreach(unclaim); None }
      else Some(resumeIn(inOrder.zip(live.map(_.get)), target))

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

    private def resumeIn(batch: List[(Run.SkippedStat, Context)], target: Span): List[Diagnostic] =
      beginTyping()
      batch.foreach((sk, _) => assertOwnSource(sk))
      val typed = List.newBuilder[(Run.SkippedStat, List[Tree])]
      // the statements of the batch not yet settled: typed and spliced, given
      // back, abandoned, or released by a failure (I61)
      var unsettled = batch.map(_._1)
      try
        for (sk, ctx0) <- batch do
          reader.clearTargeting()
          reader.typingTarget = target
          try
            // typed in its own typer state, committed into the session's only once
            // the statement is typed: a cancellation drops its constraints with it
            val ctx1 = ctx0.fresh.setNewTyperState()
            reader.targetNextStats(sk.owner, sk.inBlock)(using ctx1)
            // a resumed block statement continues the targeting of its block
            reader.targetingTyperState = ctx1.typerState
            val (stats, _) = ctx1.typer.typedStats(sk.stat :: Nil, sk.owner)(using ctx1)
            synchronized {
              endStatementState(ctx1, ctx0)
              retyped(sk, stats)(using ctx1)
              collectSkipped()
            }
            typed += ((sk, stats))
          catch case ex: CompilationUnit.CancelledCompilation =>
            // the ones typed so far are kept (spliced below); the aborted one
            // is replaced or poisons the session; the rest stay skipped
            deferAbandon(sk)
            unsettled = unsettled.filterNot(s => (s eq sk) || typed.result().exists(_._1 eq s))
            unsettled.foreach(unclaim)
            unsettled = Nil
            throw ex
        synchronized {
          assert(!skipped.exists(_.stat.span.touches(target)), s"target $target is still skipped in $uri after the resume of ${batch.map(_._1.stat.span).mkString(", ")}")
        }
        currentDiagnostics
      finally
        reader.clearTargeting()
        val entries = typed.result()
        synchronized {
          if entries.nonEmpty then spliceAll(entries)(using batch.head._2)
          entries.foreach((sk, _) => typing.remove(sk))
          notifyAll()
        }
        unsettled = unsettled.filterNot(s => entries.exists(_._1 eq s))
        // any other exception: no claim is left behind
        unsettled.foreach(releaseFailed)

    /** Does `owner` define `name` in this file: the companion of a statement
     *  to replace. A companion is not replaced alone: the synthetic members a
     *  case class gives its object (`apply`, `unapply`) were merged into the
     *  object's tree by the class's expansion, and a fresh parse of the object
     *  alone lacks them.
     */
    private def hasCompanionHere(owner: Symbol, name: Name)(using Context): Boolean =
      val cls = if owner.isClass then owner else owner.owner
      cls.isClass && {
        val sym = cls.asClass.classDenot.info.decls.lookup(name)
        sym.exists && isFileOf(sym.associatedFile)
      }

    /** Replace the skipped statement `sk`, whose typing was aborted, by a
     *  fresh parse of its source range, indexed afresh in the same run and
     *  context: its symbols are unlinked from the owner's scope and re-created,
     *  and it is skipped again. References to the old symbols typed earlier
     *  keep them. Only definitions are replaced (not the synthetic
     *  `<file>$package` object of top-level definitions, nor a nested package
     *  clause, nor a statement that is an expression).
     */
    private def replacePoisoned(sk: Run.SkippedStat): Boolean =
      given Context = sk.ctx
      val head = sk.stat
      val replaceable = !sk.inBlock && (head match
        case mdef: untpd.ModuleDef => !mdef.rawMods.is(Flags.Synthetic) && !hasCompanionHere(sk.owner, mdef.name.toTypeName)
        case tdef: untpd.TypeDef => !hasCompanionHere(sk.owner, tdef.name.toTermName)
        case _: untpd.DefDef | _: untpd.ValDef => true
        case _ => false)
      if !replaceable then return false
      val parsed = new parsing.Parsers.Parser(source).parse()
      val fresh = new untpd.UntypedTreeAccumulator[Option[untpd.Tree]]:
        def apply(x: Option[untpd.Tree], tree: untpd.Tree)(using Context): Option[untpd.Tree] =
          if x.isDefined then x
          else if tree.span == head.span && tree.getClass == head.getClass then Some(tree)
          else if tree.span.contains(head.span) then foldOver(x, tree)
          else x
      fresh(None, parsed) match
        case Some(stat) =>
          val cls = (if sk.owner.isClass then sk.owner else sk.owner.owner).asClass.classDenot
          val old = cls.info.decls.toList.filter(sym => sym.span.exists && stat.span.contains(sym.span) && definesHere(sym))
          // A given or implicit is held by the `ContextualImplicits` cached on
          // the session's contexts (and by the run's implicit scopes), by
          // symbol: a fresh symbol would not be found, and the old one is
          // what is being replaced. The session is poisoned instead.
          if old.exists(definesImplicit) then return false
          old.foreach(cls.delete)
          ctx.typer.index(stat :: Nil)
          synchronized { skipped = Run.SkippedStat(stat, sk.ctx, sk.owner) :: skipped }
          true
        case None =>
          false

    /** Is `sym` a given or implicit, or a class or object whose indexed
     *  members (nested ones included) hold one.
     */
    private def definesImplicit(sym: Symbol)(using Context): Boolean =
      sym.isOneOf(Flags.GivenOrImplicit)
      || sym.is(Flags.ModuleClass) && sym.sourceModule.isOneOf(Flags.GivenOrImplicit)
      || sym.isClass && sym.asClass.unforcedDecls.exists(definesImplicit)

    /** The symbols whose completion the cancellation unwound through
     *  (`Reader.cancelledCompletions`): a Namer completer that was unwound can
     *  not be run again (a method's had entered its parameters in the nested
     *  typer's scope; a class's had set the temporary info, and the class is
     *  `isCompleted` without parents), and the statement that defines each
     *  one is replaced like the aborted statement itself (`replacePoisoned`),
     *  or the session is poisoned: a symbol of this file in no skipped
     *  statement (a local of a block, a member of a companion). The symbols
     *  of other files (the classpath's loaders leave their roots loadable
     *  again; a sourcepath file's completers are left alone) are not touched.
     *  `replaced` is the span of the aborted statement, replaced already.
     *  Returns false when the session is to be poisoned.
     */
    private def replaceUnwound(replaced: Span)(using Context): Boolean =
      val syms = reader.cancelledCompletions.toList
      reader.cancelledCompletions.clear()
      var done = List(replaced)
      var ok = true
      for sym <- syms if ok && sym.exists && definesHere(sym) do
        if !sym.span.exists then ok = false
        else if !done.exists(_.contains(sym.span)) then
          skippedAt(sym.span) match
            case Some(sk) =>
              synchronized { skipped = skipped.filterNot(_ eq sk) }
              if replacePoisoned(sk) then done = sk.stat.span :: done
              else ok = false
            case None => ok = false
      ok

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
      reader.cancelledCompletions.clear()
      try skippedNow.foreach(sk => ctx.typer.foreachIndexedDef(sk.stat)((_, sym) => sym.ensureCompleted()))
      catch case ex: CompilationUnit.CancelledCompilation =>
        // the session is kept (the walk runs again when a file is next left),
        // with the statements of the unwound completers replaced
        if !replaceUnwound(NoSpan) then poisoned = true
        throw ex

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
      install(t)
      entries.foreach(_._2.foreach(cleanup(_, errorOutCompleters = false)))

    /** `t` is the unit's new typed tree. */
    private def install(t: Tree)(using Context): Unit =
      unit.tpdTree = t
      // What `SetRootTree` would have done had the statements been typed in the phase
      inContext(ctx.fresh.setCompilationUnit(unit)):
        new transform.SetRootTree().run
      myOpenedTrees.synchronized { myOpenedTrees(uri) = topLevelTrees(t, source) }

    // ---- An edit inside a body (`-Yinteractive-body-edits`, docs §12)

    /** A parse of the content of `source` that no Namer has touched: the
     *  Namer replaces the modifiers of what it indexes, so the unit's
     *  untyped tree is not comparable. Made on the first edit, then the
     *  parse of each accepted edit's new content.
     */
    @guardedBy("writer") private var pristine: untpd.Tree | Null = null

    /** The syntax errors of `pristine`. */
    @guardedBy("writer") private var pristineErrors: List[Diagnostic] = Nil

    /** A parse of `src` in a unit of its own, whose fresh names (`_$1`)
     *  are numbered as a fresh run's parse would number them.
     */
    private def parse(src: SourceFile, reporter: Reporter): untpd.Tree =
      given Context = run.runContext.fresh.setReporter(reporter)
      inContext(ctx.fresh.setCompilationUnit(CompilationUnit(src, mustExistIfNotNull = false))):
        new parsing.Parsers.Parser(src).parse()

    /** Carry the session over to the content of `now`, if the edit from the
     *  session's content is inside the body of one definition with a declared
     *  type (it is then re-indexed and skipped, to be typed when a request
     *  needs it) or touches no tree at all. Everything else the session holds
     *  is kept, with its spans shifted, and the unit's source holds the new
     *  content. Returns false, with the session as it was, otherwise.
     */
    def adopt(now: SourceFile): Boolean =
      given Context = run.runContext.fresh.setCompilationUnit(unit)
      val src = unit.source
      val oldText = src.textContent()
      val newText = now.textContent()
      val old = pristine match
        case null =>
          val oldErrors = new StoreReporter(null)
          val p = parse(SourceFile.virtual(src.path, oldText), oldErrors)
          pristineErrors = oldErrors.removeBufferedMessages
          p
        case p: untpd.Tree => p
      val t0 = System.nanoTime()
      val r = BodyEdit.region(oldText, newText)
      src.replaceContent(newText)
      val parseErrors = new StoreReporter(null)
      val parsed = parse(src, parseErrors)
      val t1 = System.nanoTime()
      var tCompared = t1
      def took = f"; parse ${(t1 - t0) / 1e6}%.1f ms, compare ${(tCompared - t1) / 1e6}%.1f ms, shift ${shiftNanos / 1e6}%.1f ms (diagnostics ${diagnosticsNanos / 1e6}%.2f ms), the rest ${(System.nanoTime() - tCompared) / 1e6 - shiftNanos / 1e6}%.1f ms"
      def refuse(why: String): Boolean =
        src.replaceContent(oldText)
        myLastEdit = s"edit refused ($why)"
        false
      val errors = parseErrors.removeBufferedMessages
      /** The syntax errors outside `now` are those outside `old`, moved: a
       *  file that has one elsewhere still keeps its session.
       */
      def sameErrorsOutside(old: Span, now: Span, anchor: Span): Boolean =
        def key(dia: Diagnostic, sp: Span) = (sp, dia.msg.message)
        val before = pristineErrors.filterNot(e => old.contains(e.pos.span)).map(e => key(e, r.span(e.pos.span, anchor)))
        val after = errors.filterNot(e => now.contains(e.pos.span)).map(e => key(e, e.pos.span))
        before.sortBy(_.toString) == after.sortBy(_.toString)
      val kind = BodyEdit.classify(old, parsed, r)
      tCompared = System.nanoTime()
      kind match
        case BodyEdit.Kind.Fresh(why) => refuse(why)
        case BodyEdit.Kind.Layout if !sameErrorsOutside(NoSpan, NoSpan, r.regionSpan) => refuse("the syntax errors differ")
        case BodyEdit.Kind.Layout =>
          val shift = BodyEdit.Shift(r, r.regionSpan, src)
          shiftAll(shift, r, r.regionSpan, immutable.Set.empty, immutable.Set.empty, errors)
          pristineErrors = errors
          pristine = parsed
          unit.untpdTree = parsed
          myLastEdit = s"layout edit, shifted ${shift.trees} trees ${shift.symbols} symbols$took"
          true
        case BodyEdit.Kind.Body(d, d1) =>
          if !sameErrorsOutside(d.span, d1.span, d.span) then refuse("the syntax errors outside the edited body differ")
          else bodyEdit(d, d1, parsed, r, errors) match
            case null =>
              pristineErrors = errors
              myLastEdit += took
              true
            case why: String => refuse(why)

    /** The member-level typed definitions (statements of a package or a
     *  template) whose spans lie in `span`.
     */
    private def typedMembersIn(span: Span)(using Context): List[Tree] =
      val found = List.newBuilder[Tree]
      def stats(ts: List[Tree]): Unit = ts.foreach {
        case t: PackageDef => stats(t.stats)
        case t @ TypeDef(_, impl: Template) if t.span.contains(span) || !t.span.exists => stats(impl.body)
        case t: ValOrDefDef if (t.source eq unit.source) && span.contains(t.span) => found += t
        case _ =>
      }
      stats(unit.tpdTree :: Nil)
      found.result()

    /** The body edit of `d` (old) to `d1` (new); null when done, or why not,
     *  before anything is changed.
     */
    private def bodyEdit(d: untpd.ValOrDefDef, d1: untpd.ValOrDefDef, parsed: untpd.Tree, r: BodyEdit.Region,
        parseErrors: List[Diagnostic])(using Context): String | Null =
      val src = unit.source
      val typedHere = typedMembersIn(d.span)
      val main = typedHere.find(t => t.span == d.span && t.symbol.name == d.name)
      val skippedHere = synchronized(skipped.find(sk => !sk.inBlock && sk.stat.span == d.span))
      val (ctx0, owner) = main match
        case Some(t) =>
          recorded match
            case null => return "no recorded contexts"
            case rec: java.util.IdentityHashMap[Symbol, (Context, Symbol)] @unchecked =>
              rec.get(t.symbol) match
                case null => return "no recorded context"
                case co => co
        case None => skippedHere match
          case Some(sk) => (sk.ctx, sk.owner)
          case None => return "inside a statement skipped whole"
      val ctx1 = liveContext(ctx0) match
        case Some(c) => c
        case None => return "no live typer state"
      // a local subclass of a sealed class registered itself as a child
      if main.exists(_.existsSubTree {
        case td: TypeDef => td.isClassDef && td.symbol.asClass.parentSyms.exists(_.is(Flags.Sealed))
        case _ => false
      }) then return "a local child of a sealed class"
      val cls = (if owner.isClass then owner else owner.owner).asClass
      // An inline accessor is made by typing an inline method that refers to
      // the private member it accesses, positioned at the reference: not
      // the Namer's, not re-indexed, kept.
      def isInlineAccessor(sym: Symbol) =
        val n = sym.name.unexpandedName.toTermName
        n.is(NameKinds.InlineAccessorName) || n.isSetterName && n.getterName.is(NameKinds.InlineAccessorName)
      def ofD(sym: Symbol, span: Span) =
        definesHere(sym) && !sym.isConstructor && !isInlineAccessor(sym) && sym.span.exists && span.contains(sym.span)
      val olds = cls.unforcedDecls.toList.filter(ofD(_, d.span))
      if !main.forall(t => olds.contains(t.symbol)) then return "the definition's symbol is not a member"
      val oldInfos = olds.filter(_.isCompleted).map(sym => sym -> sym.info)

      // From here on the session changes: a failure poisons it.
      var done = false
      try
        if typedHere.nonEmpty then
          val gone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Tree, java.lang.Boolean])
          typedHere.foreach(gone.add)
          val mapper = new TreeMap:
            override def transform(tree: Tree)(using Context): Tree = tree match
              case t: PackageDef => super.transform(cpy.PackageDef(t)(t.pid, t.stats.filterNot(gone.contains)))
              case t: Template => super.transform(cpy.Template(t)(t.constr, t.parents, t.derived, t.self, t.body.filterNot(gone.contains)))
              case t: ValOrDefDef => t // members are not inside bodies
              case _ => super.transform(tree)
          install(mapper.transform(unit.tpdTree))
        // the statement, or what the old body's blocks skipped: they go with it
        synchronized { skipped = skipped.filterNot(sk => d.span.contains(sk.stat.span)) }
        // what D's statements and completions reported goes with them, wherever
        // it is positioned; its statements' is stale until D' is typed (§12)
        val stats = olds.map(reporting.Provenance.Stat(_)).toSet[reporting.Provenance]
        val shift = BodyEdit.Shift(r, d.span, src)
        shiftAll(shift, r, d.span, stats ++ olds.map(reporting.Provenance.Completion(_)), stats, parseErrors)
        // the old symbols are refined by the re-index: unlinked first, so that
        // the Namer finds no conflict, and entered again by it
        olds.foreach { sym =>
          cls.delete(sym)
          ctx1.typer.nestedTyper.remove(sym)
          sym.defTree = EmptyTree
        }
        val stat = BodyEdit.copy(d1, src)
        val refined = mutable.Set.empty[Symbol]
        var active = true
        val refine: (Name, Span) => Symbol = (name, span) =>
          if !active then NoSymbol
          else olds.find(sym => sym.name == name && sym.span.point == span.point && !refined(sym)) match
            case Some(sym) => refined += sym; sym
            case None => NoSymbol
        // what the re-index reports is D's
        val dsym = olds.find(_.name == d.name).getOrElse(NoSymbol)
        try
          if dsym.exists then reader.withProvenance(reporting.Provenance.Stat(dsym))(ctx1.typer.index(stat :: Nil)(using ctx1.fresh.setProperty(typer.Typer.RefinedSymbols, refine)))
          else ctx1.typer.index(stat :: Nil)(using ctx1.fresh.setProperty(typer.Typer.RefinedSymbols, refine))
        finally active = false
        synchronized { skipped = Run.SkippedStat(stat, ctx0, owner) :: skipped }
        pristine = parsed
        unit.untpdTree = parsed
        val news = cls.unforcedDecls.toList.filter(ofD(_, d1.span))
        if refined.size != olds.size || news.size != olds.size then
          poisoned = true
          myLastEdit = s"body edit of ${d.name} poisoned: refined ${refined.map(_.name).mkString(",")} of ${olds.map(_.name).mkString(",")}, now ${news.map(_.name).mkString(",")}"
        else
          // the backdating: the re-indexed signature gives the info it gave
          val changed = oldInfos.filter((sym, info) => !(sym.info =:= info)(using ctx1))
          if changed.nonEmpty then
            poisoned = true
            myLastEdit = s"body edit of ${d.name} poisoned: info of ${changed.map(_._1.name).mkString(", ")} changed"
          else
            myLastEdit = s"body edit of ${d.name}${if main.isDefined then " (typed)" else " (skipped)"}, shifted ${shift.trees} trees ${shift.symbols} symbols"
        done = true
        null
      finally if !done then poisoned = true

    /** The time the last shift took, for the log, and of it the diagnostics' and suppressions'. */
    @guardedBy("writer") private var shiftNanos = 0L
    @guardedBy("writer") private var diagnosticsNanos = 0L

    /** Shift everything the session keeps of its source (§12), with the
     *  diagnostics and suppressions of `dropped` dropped (those of `toStale`
     *  kept stale) and `parseErrors` the syntax errors of the new parse.
     */
    private def shiftAll(shift: BodyEdit.Shift, r: BodyEdit.Region, anchor: Span, dropped: immutable.Set[reporting.Provenance],
        toStale: immutable.Set[reporting.Provenance], parseErrors: List[Diagnostic])(using Context): Unit =
      val t0 = System.nanoTime()
      val src = unit.source
      shift.tree(unit.tpdTree)
      synchronized(skipped).foreach(sk => shift.tree(sk.stat))
      // the file's packages: their members of this file, and what they hold
      unit.tpdTree.foreachSubTree {
        case p: PackageDef => p.symbol.moduleClass.asClass.unforcedDecls.foreach(shift.symbol)
        case _ =>
      }
      val t1 = System.nanoTime()
      reporter.edit(src, dropped, toStale, pristineErrors, parseErrors, r.touches, r.span(_, anchor))
      run.suppressions.editSuppressions(src) { sup =>
        if sup.provenance != null && dropped.contains(sup.provenance.nn) then null
        else
          val sp = r.span(Span(sup.start, sup.end), anchor)
          sup.moved(sup.annotPos.withSpan(r.span(sup.annotPos.span, anchor)), sp.start, sp.end)
      }
      diagnosticsNanos = System.nanoTime() - t1
      ctx.property(Comments.ContextDoc).foreach { docs =>
        docs.docstrings.iterator.toList.foreach { (sym, c) =>
          if definesHere(sym) then docs.addDocstring(sym, Some(c.copy(span = r.span(c.span, anchor))))
        }
      }
      shiftNanos = System.nanoTime() - t0
  end Session

  private object Session:
    enum Claim:
      case Typed, Wait, Abandoned
      case Claimed(sks: List[Run.SkippedStat])

  // written only by write actions (a run), read by anyone
  @volatile private var mySession: Session | Null = null

  /** With several files in one run (`openAll`), the session of each; empty
   *  otherwise. Replaced, like `mySession`, by any run.
   */
  @treeState @publishedBy("volatile") @volatile private var myOtherSessions: Map[URI, Session] = Map.empty

  private def sessionOf(uri: URI): Session | Null = mySession match
    case s: Session if s.uri == uri => s
    case _ => myOtherSessions.getOrElse(uri, null)

  private def isCurrent(s: Session): Boolean =
    (mySession eq s) || myOtherSessions.get(s.uri).exists(_ eq s)

  /** An edit carried over to the session (§12) changes what every reader of
   *  the session reads: a write action, before the request's read.
   */
  private def adoptEditWriting(uri: URI, source: SourceFile): Unit = sessionOf(uri) match
    case s: Session if bodyEdits && !s.isOn(uri, source) => writing(adoptEdit(uri, source))
    case _ => adoptEdit(uri, source)

  /** Carry the session of `uri` over to the content of `source` if it is
   *  an edit inside a body, or layout (`-Yinteractive-body-edits`, §12).
   */
  private def adoptEdit(uri: URI, source: SourceFile): Boolean = sessionOf(uri) match
    case s: Session if bodyEdits && s.isResumable && !s.isOn(uri, source) =>
      myCancelled = false
      // a write action: `adoptEditWriting` may have chosen to run without one, on a session
      // that a writer replaced since, so check again under the write lock
      writing(isCurrent(s) && s.adopt(source))
    case s: Session =>
      myLastEdit = if bodyEdits && !s.isOn(uri, source) then s"no edit: the session is ${if s.run.isTainted then "tainted" else "poisoned"}" else ""
      false
    case null =>
      myLastEdit = ""
      false

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

  /** Is the targeted run of `uri` tainted: did an exception of unknown effect
   *  unwind through a completion (see `Run.isTainted`)? The next request on
   *  it starts a fresh run.
   */
  def isTainted(uri: URI): Boolean = sessionOf(uri) match
    case s: Session => s.run.isTainted
    case null => false

  /** The spans of the statements the targeted run of `uri` has not typed. */
  def untypedSpans(uri: URI): List[Span] = sessionOf(uri) match
    case s: Session => s.skippedNow.map(_.stat.span)
    case null => Nil

  /** The statements of `uri` being typed right now, by any reader, and how
   *  many a completing pass has typed (for the x-ray).
   */
  def typingSpans(uri: URI): List[Span] = sessionOf(uri) match
    case s: Session => s.typingNow
    case null => Nil
  def completedByPass(uri: URI): Int = sessionOf(uri) match
    case s: Session => s.completedByPass
    case null => 0

  /** Has the targeted run of `uri` typed all of its statements? */
  def isFullyTyped(uri: URI): Boolean = sessionOf(uri) match
    case s: Session => !s.hasSkipped
    case null => false

  /** For the x-ray: the diagnostics of the session on `uri` that are stale,
   *  an edited definition's old ones, published until it is typed again (§12).
   */
  def staleDiagnostics(uri: URI): List[Diagnostic] = sessionOf(uri) match
    case s: Session => s.reporter.staleDiagnostics
    case null => Nil

  /** For the x-ray and the tests: the session's diagnostics, each with the
   *  fact that reported it (§12, "Diagnostics").
   */
  def diagnosticsByProvenance(uri: URI): List[(String, Diagnostic)] = sessionOf(uri) match
    case s: Session => s.reporter.byProvenance
    case null => Nil

  /** What the last request on `uri` did with an edit (§12), or "". */
  def lastEdit(uri: URI): String = if sessionOf(uri) == null then "" else myLastEdit

  /** What the last request did with an edit, for the log and the tests. Written by the edit's request (a write action
   *  when it changes the session, else a plain clear), read by the log from any thread.
   */
  @scratch @publishedBy("volatile") @volatile private var myLastEdit: String = ""

  /** The id of the run that typed `uri`, or -1. */
  def runIdOf(uri: URI): Int = sessionOf(uri) match
    case s: Session => s.run.runContext.runId
    case null => -1

  /** `-Yinteractive-log`: one line per request on stderr (the Metals log), with
   *  what the run did, so that the session can be observed from an editor.
   */
  val logRequests: Boolean = myInitCtx.settings.YinteractiveLog.value(using myInitCtx)

  /** The last segment of `uri`, for the log; a `jar:` URI (a source in a
   *  sources jar, opened by a jump into the library) has no path.
   */
  private def fileNameOf(uri: URI): String =
    val p = uri.getPath
    (if p == null then uri.toString else p).split('/').last

  private def showTarget(target: Span): String =
    if target.isZeroExtent then s"${target.start}" else s"${target.start}-${target.end}"

  /** What the session of `uri` did, for the log. */
  private def sessionSummary(uri: URI): String =
    val trees = sessionOf(uri) match
      case s: Session => s" trees=${base.reader.typedTrees}"
      case null => ""
    s"run=${runIdOf(uri)} typedTop=${typedTopLevelCount(uri)} skipped=${untypedSpans(uri).size}$trees"

  private def logged[T](what: String)(body: => T)(outcome: => String): T =
    if !logRequests then body
    else
      val t0 = System.nanoTime()
      // the presentation-compiler provider that made the request, for the log
      val by = Thread.currentThread.getStackTrace.iterator.map(_.getClassName.nn)
        .find(c => c.startsWith("dotty.tools.pc.") && !c.contains("CachingDriver") && !c.contains("CompilerAccess") && !c.contains("CompilerWrapper"))
        .map(c => " by " + c.stripPrefix("dotty.tools.pc.").takeWhile(_ != '$')).getOrElse("")
      try body
      catch case scala.util.control.NonFatal(ex) =>
        // the editor's report keeps the message only: the stack goes to the log
        System.err.println(s"[dotc-session] $what failed$by: $ex\n" + ex.getStackTrace.nn.take(24).map("    at " + _).mkString("\n"))
        throw ex
      finally System.err.println(f"[dotc-session] $what ${outcome}$by ${(System.nanoTime() - t0) / 1e6}%.1f ms")

  /** Under `-Yinteractive-log`, the time a whole request took in the caller
   *  (a presentation-compiler provider), the driver's own work included: what
   *  the editor waits for once the request is handed over.
   */
  def timedRequest[T](what: String)(body: => T): T =
    if !logRequests then body
    else
      val t0 = System.nanoTime()
      try body
      finally System.err.println(f"[dotc-session] $what: whole request ${(System.nanoTime() - t0) / 1e6}%.1f ms in the presentation compiler")

  /** Type `source` only along the path to `target`: of the statements of
   *  the outermost package and of each template on the path, only the one
   *  containing the target, as scalac's `typedStats` does for its target
   *  position. A target that is a range (a visible range, for inlay hints and
   *  semantic tokens) types every statement it touches, at each level. Or
   *  resume the previous targeted run of `uri` if it was on the
   *  same content: a target within what is typed is served without typing
   *  anything, a target within skipped statements types those statements in
   *  the same run and context, so that the denotations and completers of
   *  everything else stay valid. Any other content starts a fresh targeted
   *  run. The compilation unit of `uri` then holds the typed part of the
   *  tree, without the skipped statements; providers that need the whole
   *  tree use `run` instead.
   */
  def runUpTo(uri: URI, source: SourceFile, target: Span): List[Diagnostic] =
    readUpTo(uri, source, target)((_, _, diags) => diags)

  /** Type the path to `target` (`runUpTo`) and apply `read` to the typed
   *  unit, under the read lock: a reader of the session, so that no writer
   *  (a run on other content, a re-index) replaces the unit or its run before
   *  `read` has seen it. Providers that read the typed tree after a request
   *  on a driver shared between threads use this; `runUpTo` is the same with
   *  the diagnostics as the result.
   */
  def readUpTo[T](uri: URI, source: SourceFile, target: Span)(read: (Context, CompilationUnit, List[Diagnostic]) => T): T =
    if !targetedRequests then return readAll(uri, source)(read)
    requestReader()
    val before = sessionOf(uri)
    val skippedBefore = if before == null then -1 else before.skippedNow.size
    logged(s"runUpTo ${fileNameOf(uri)}@${showTarget(target)}")(readUpTo0(uri, source, target)(read)) {
      val s = sessionOf(uri)
      val how =
        if s eq null then "whole-file"
        else if !(s eq before) then "fresh"
        else if s.skippedNow.size != skippedBefore then "resumed"
        else "served"
      val edit = if myLastEdit.nonEmpty then s" [$myLastEdit]" else ""
      s"$how ${sessionSummary(uri)}$edit"
    }

  private def readUpTo0[T](uri: URI, source: SourceFile, target: Span)(read: (Context, CompilationUnit, List[Diagnostic]) => T): T =
    // A request that needs a fresh run reads its result inside the same
    // write action (`writtenAndRead`), whatever state the run left the
    // session in: a tainted run is not resumed by the *next* request, but it
    // answers this one (the first version ran again and again on a session
    // tainted by its own run, finding I40).
    adoptEditWriting(uri, source)
    while true do
      // the read action: served from the session, or a resume into a skipped
      // statement; `None` when a write is needed first
      val served: Option[T] = reading {
        sessionOf(uri) match
          // Nothing is served or resumed from a tainted or poisoned session
          // (a request in a poisoned statement's range would be served from the
          // tree without it): the request starts a fresh run, which is cheap
          // for a targeted run.
          case s: Session if s.isOn(uri, source) && s.isResumable && !s.hasPendingAbandons =>
            var result: Option[T] = None
            var done = false
            while !done do
              s.claim(target) match
                case Session.Claim.Typed =>
                  result = Some(read(readCtx, s.unit, s.currentDiagnostics))
                  done = true
                case Session.Claim.Wait =>
                  // another reader is typing the statement: its result serves this request too
                  s.awaitTyping(target)
                case Session.Claim.Abandoned =>
                  // a write first (the re-index of the statement), then again
                  result = None
                  done = true
                case Session.Claim.Claimed(sks) =>
                  // A statement skipped inside a block left a stub in the typed
                  // `Block`; the resume replaces the stub by the typed statement.
                  result = s.resume(sks, target) match
                    case Some(diags) => Some(read(readCtx, s.unit, diags))
                    case None => None // no typer state left to resume in: a fresh run
                  done = true
            result
          case _ => None
      }(afterCancel = () => { val s = sessionOf(uri); if s != null then abandonPending(s) })
      served match
        case Some(t) => return t
        case None =>
          val s = sessionOf(uri)
          if s != null && s.hasPendingAbandons then abandonPending(s)
          else writtenAndRead(uri, run(uri, source, target), read) match
            case Some(t) => return t
            case None => // the run failed fatally (no unit): as before, again
    throw new IllegalStateException("unreachable")

  /** Type the statement that defines `sym` if it is in the file of `uri` and
   *  the session skipped it: a resume into it (or a fresh run for a local
   *  definition), after which providers that read the typed tree of a
   *  definition (`defTree`, a name position) find it. Returns whether
   *  anything was typed, in which case the unit's tree is new.
   */
  def typeDefinitionOf(uri: URI, sym: Symbol): Boolean =
    given Context = myCtx
    sessionOf(uri) match
      case s: Session if sym.exists && sym.span.exists && s.isFileOf(sym.source.file)
          && s.skippedAt(Span(sym.span.point)).isDefined =>
        runUpTo(uri, s.source, Span(sym.span.point))
        true
      case _ => false

  /** A write action for the callers that change what readers read outside
   *  the driver (tests that plant state on the session's symbols).
   */
  def asWriter[T](body: => T): T = writing(body)

  /** The end of a statement's typing (a resume or a pass): its typer state is
   *  committed into the one it was skipped in, under the completion lock; with
   *  parallel readers it is private, settled (its type variables
   *  instantiated, its messages issued) and dropped, and the run's root state
   *  stays read-only (I76: constraints shared through the root state, a
   *  conversion's `A` met bound to another reader's type).
   */
  private def endStatementState(ctx1: Context, ctx0: Context): Unit =
    Sharing.endState(ctx1, ctx0)

  /** Several files in one run, a session for each (the throughput
   *  experiment of the multi-reader notes): every top-level statement of
   *  every file is skipped (with `typeAll`, none is), and each file gets a
   *  session of its own on the shared run, from which any reader serves,
   *  resumes or completes requests on that file. No editing: a run of one
   *  file, or a request on other content, replaces them all. The
   *  diagnostics of each file so far.
   */
  def openAll(files: List[(URI, SourceFile)], typeAll: Boolean = false): Map[URI, List[Diagnostic]] =
    requestReader()
    writing(openAll0(files, typeAll))

  private def openAll0(files: List[(URI, SourceFile)], typeAll: Boolean): Map[URI, List[Diagnostic]] =
    import typer.ImportInfo.*
    val previousCtx = myCtx
    mySession = null
    myOtherSessions = Map.empty
    val reporter = SessionReporter()
    myCancelled = false
    val reader = base.reader
    try
      val run = compiler.newRun(using myInitCtx.fresh.setReporter(reporter).setProgressCallback(myProgressCallback))
      myCtx = run.runContext.withRootImports
      given Context = myCtx
      myOpenedFiles.synchronized { files.foreach((u, src) => myOpenedFiles(u) = src) }
      // a point past the end of every file: every top-level statement is skipped
      val target = if typeAll then NoSpan else Span(Spans.MaxOffset - 1)
      reader.typingTarget = target
      reader.runsStarted += 1
      if typeAll then reader.wholeFileRunsStarted += 1
      run.isTargeted = target.exists
      val tOpen = System.nanoTime()
      val skipped =
        try
          run.compileSources(files.map(_._2))
          reader.skippedStats.toList
        finally reader.clearTargeting()
      val tCompiled = System.nanoTime()
      if myCancelled || reader.cancelChecker() then throw CompilationUnit.CancelledCompilation()
      val sessions = ctx.run.nn.units.zipWithIndex.map { (unit, i) =>
        val uri = files.collectFirst { case (u, src) if src eq unit.source => u }.getOrElse(URI.create("file:///" + unit.source.path).nn)
        val own = skipped.filter(_.ctx.source eq unit.source)
        val t = unit.tpdTree
        cleanup(t, errorOutCompleters = own.isEmpty)
        myOpenedTrees.synchronized { myOpenedTrees(uri) = topLevelTrees(t, unit.source) }
        myCompilationUnits.synchronized { myCompilationUnits(uri) = unit }
        // the diagnostics without a position go to the first file's
        val s = Session(uri, unit.source, run, reporter, unit, null, shared = true, takesNopos = i == 0)
        s.setInitial(own)
        uri -> s
      }
      myCtx = myCtx.fresh.setPhase(myInitCtx.base.typerPhase)
      // from here on, readers settle their own typer states (I76)
      if parallelReaders then myCtx.typerState.readOnlyRoot = true
      myOtherSessions = sessions.toMap
      if OpenTiming.on then
        OpenTiming.log(f"open: ${files.size} files, compileSources ${(tCompiled - tOpen) / 1e6}%.1f ms, sessions ${(System.nanoTime() - tCompiled) / 1e6}%.1f ms")
      sessions.map((u, s) => u -> s.currentDiagnostics).toMap
    catch case ex: (CompilationUnit.CancelledCompilation | FatalError) =>
      myCtx = previousCtx
      throw ex

  /** A fresh run (a writer: called under the write lock). */
  private def run(uri: URI, source: SourceFile, target: Span): List[Diagnostic] =
    logged(s"run ${fileNameOf(uri)}${if target.exists then s"@${showTarget(target)}" else " (whole file)"}")(run0(uri, source, target)) {
      sessionSummary(uri)
    }

  private def run0(uri: URI, source: SourceFile, target: Span): List[Diagnostic] = {
    import typer.ImportInfo.*

    assert(regime.writeLock.isHeldByCurrentThread, "a run is a write action")
    val previousCtx = myCtx
    val previousSource = myOpenedFiles.synchronized(myOpenedFiles.get(uri))
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
    myOtherSessions.values.foreach(s => if s.uri != uri then s.completeSymbols())
    // Any new run resets the caches of the context base: a previous targeted
    // run can no longer be resumed.
    mySession = null
    myOtherSessions = Map.empty
    var startedRun: Run | Null = null
    try {
      // the session's reporter is written by every reader (a completer
      // reports through its creation context): one report at a time
      val reporter = SessionReporter()

      myCancelled = false
      val reader = base.reader
      val recorded = if bodyEdits then new SyncIdentityHashMap[Symbol, (Context, Symbol)] else null
      val runCtx = myInitCtx.fresh.setReporter(reporter).setProgressCallback(myProgressCallback)
      if recorded != null then runCtx.setProperty(typer.Typer.RecordedDefContexts, recorded)
      val run = compiler.newRun(using runCtx)
      startedRun = run
      myCtx = run.runContext.withRootImports

      given Context = myCtx

      myOpenedFiles.synchronized { myOpenedFiles(uri) = source }

      reader.typingTarget = target
      reader.runsStarted += 1
      if !target.exists then reader.wholeFileRunsStarted += 1
      run.isTargeted = target.exists
      val skipped =
        try
          run.compileSources(List(source))
          reader.skippedStats.toList
        finally reader.clearTargeting()
      // A cancellation noticed before the typer skips the units instead of
      // unwinding (see `Run.didEnterUnit`); discard that run too.
      if myCancelled || reader.cancelChecker() then throw CompilationUnit.CancelledCompilation()
      reader.withProvenance(reporting.Provenance.Summary)(run.printSummary())
      val ctxRun = ctx.run.nn
      val unit = if ctxRun.units.nonEmpty then ctxRun.units.head else ctxRun.suspendedUnits.head
      val t = unit.tpdTree
      val partial = target.exists && skipped.nonEmpty
      cleanup(t, errorOutCompleters = !partial)
      myOpenedTrees.synchronized { myOpenedTrees(uri) = topLevelTrees(t, source) }
      myCompilationUnits.synchronized { myCompilationUnits(uri) = unit }
      myCtx = myCtx.fresh.setPhase(myInitCtx.base.typerPhase)
      // from here on, readers settle their own typer states (I76)
      if parallelReaders then myCtx.typerState.readOnlyRoot = true

      val diagnostics = reporter.runResult
      // A whole-file run leaves a session too, with nothing skipped: the
      // requests after it on the same content are served from it, instead
      // of each running again (I58). Without targeting no request reads a
      // session.
      if target.exists || targetedRequests then
        val s = Session(uri, source, run, reporter, unit, recorded)
        s.setInitial(skipped)
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
        // context would find them "invalid in run".
        startedRun match
          case r: Run => myCtx = r.runContext.withRootImports.fresh.setPhase(myInitCtx.base.typerPhase)
          case null => myCtx = previousCtx
        myOpenedFiles.synchronized {
          previousSource match
            case Some(s) => myOpenedFiles(uri) = s
            case None => myOpenedFiles.remove(uri)
        }
        throw ex
    }
  }

  def close(uri: URI): Unit = {
    myOpenedFiles.synchronized(myOpenedFiles.remove(uri))
    myOpenedTrees.synchronized(myOpenedTrees.remove(uri))
    myCompilationUnits.synchronized(myCompilationUnits.remove(uri))
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
          // Only the file's own Namer completers, which capture this run's
          // context: a classpath symbol whose loading another reader has in
          // progress is not completed either, and erroring it out would
          // overwrite a shared symbol for good (finding I45: a false
          // "unspecified error" on `new VCInlineMethods` in Compiler.scala
          // after the x-ray's background pass). Under the completion lock, so
          // that no completion is half done while the infos are looked at.
          if errorOutCompleters && !t.symbol.isCompleted then completionLocked(CompletionLock.cleanup) {
            if t.symbol.infoOrCompleter.isInstanceOf[typer.Namer#Completer] then t.symbol.info = UnspecifiedErrorType
          }
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
  private def initialize(): Unit = writing {
    val run = compiler.newRun(using myInitCtx.fresh)
    myCtx = run.runContext
    run.compileUnits(Nil, myCtx)
  }
}

/** `-Ddotc.openTiming`: where `openAll`'s open goes, step by step, on stderr. A measurement aid. */
object OpenTiming:
  val on: Boolean = System.getProperty("dotc.openTiming") != null
  def log(msg: String): Unit = System.err.println("[open] " + msg)
  inline def timed[T](what: String)(inline op: T): T =
    if !on then op
    else
      val t0 = System.nanoTime()
      try op finally log(f"$what ${(System.nanoTime() - t0) / 1e6}%.1f ms")
