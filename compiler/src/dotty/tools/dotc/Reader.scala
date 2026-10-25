package dotty.tools
package dotc

import core.*
import Contexts.*
import Symbols.*
import Types.Type
import Names.Name
import Decorators.*
import util.Spans
import Spans.Span

import scala.collection.mutable
import mutable.ListBuffer

/** The per-request scratch of the compiler: what one thread of typing writes
 *  and reads for its own use while it holds nothing another thread of typing
 *  needs. A `Reader` is reached through `Context.reader`, which is
 *  `ContextBase.reader`.
 *
 *  In batch compilation there is one reader per `ContextBase`
 *  (`ContextState.initialReader`, a field): the fields below used to live on
 *  `ContextState` and `Run`, and the batch compiler's data flow is as before
 *  but for one more load (`ctx.base.reader.comparers` for
 *  `ctx.base.comparers`). In interactive mode (`InteractiveContextBase`) the
 *  reader is the one bound to the current thread, so two requests on one
 *  session do not share a comparer stack, a context pool, a target or a
 *  cancellation checker. It is bound to the thread and not carried by the
 *  context because contexts are captured and outlive the request that made
 *  them: a completer's creation context, an import's `ContextualImplicits`,
 *  a skipped statement's context all run typing later, under whichever
 *  request forces them, and that request's reader is the one they must use.
 *
 *  Design notes: `docs/_docs/internals/interactive-multi-reader.md`, §3 (kind
 *  A) and §8 step 2.
 */
class Reader(maxFuel: Int, enrichErrorMessages: Boolean, val cancelChecker: () => Boolean):

  /** The thread this reader was created for, for assertions in tests and the
   *  x-ray; the compiler does not read it.
   */
  val thread: Thread = Thread.currentThread()

  // ---- Context pools and the type comparer stack (`Contexts.explore`,
  // `withFreshTyperState`, `runWithOwner`, `comparer`) ----

  val exploreContextPool: ExploreContextPool = ExploreContextPool()
  val freshTSContextPool: FreshTSContextPool = FreshTSContextPool()
  val generalContextPool: ContextPool = ContextPool()

  private[dotc] val comparers = new mutable.ArrayBuffer[TypeComparer]
  private[dotc] var comparersInUse: Int = 0

  // ---- Fuel for recursive operations (`Context.handleRecursive`) ----

  // `recursiveOperations` is preallocated with `maxFuel` slots, so that
  // `handleRecursive` allocates nothing; `-Xno-enrich-error-messages` sets
  // the depth to `Int.MinValue` so that the overflow check never fires.
  private[dotc] var recursiveDepth: Int = if enrichErrorMessages then 0 else Int.MinValue
  private[dotc] var recursiveOperations: Array[RecursiveOperation] = Array.fill(maxFuel)(RecursiveOperation.blank())

  /** Re-size the fuel once the settings are known (`ContextBase.initialize`). */
  private[dotc] def initFuel(maxFuel: Int, enrichErrorMessages: Boolean): Unit =
    recursiveOperations = Array.fill(maxFuel)(RecursiveOperation.blank())
    recursiveDepth = if enrichErrorMessages then 0 else Int.MinValue

  // ---- The recursion guards of `Type.findMember` and `NamedType.underlying` ----

  /** Number of findMember calls on stack */
  private[dotc] var findMemberCount: Int = 0

  /** List of names which have a findMemberCall on stack,
   *  after Config.LogPendingFindMemberThreshold is reached.
   */
  private[dotc] var pendingMemberSearches: List[Name] = Nil

  /** The number of recursive invocation of underlying on a NamedType
   *  during a controlled operation.
   */
  private[dotc] var underlyingRecursions: Int = 0

  /** The set of named types on which a currently active invocation
   *  of underlying during a controlled operation exists. */
  private[dotc] val pendingUnderlying: util.HashSet[Type] = util.HashSet[Type]()

  /** The types whose base type for a class is being computed
   *  (`ClassDenotation.baseTypeOf`) on this reader, with several readers: the
   *  in-progress mark that the single-reader cache keeps as a `NoPrefix`
   *  sentinel, per class denotation as that cache is.
   */
  private val pendingBaseTypes: util.EqHashMap[AnyRef, util.EqHashSet[Type]] = util.EqHashMap()
  def pendingBaseTypesOf(cls: AnyRef): util.EqHashSet[Type] =
    pendingBaseTypes.getOrElseUpdate(cls, util.EqHashSet[Type]())

  // ---- The typer's name-resolution scratch (`Typer.findRef`) ----

  /** The set of all root import symbols that have been encountered as a
   *  qualifier of an import so far, during one lookup (saved and restored
   *  around `Typer.findRef`).
   */
  var unimported: Set[Symbol] = Set()

  /** Temporary data item for a single call to `typedIdent`: the symbol that
   *  would be found under Scala2 mode but is not in dotty.
   */
  var foundUnderScala2: Type = Types.NoType

  // ---- Targeted typing (interactive mode, see `InteractiveDriver.runUpTo`) ----
  //
  // When `typingTarget` exists, `Typer.typedStats` types, of the statements of
  // a package, a template or a block on the path to the target, only those
  // whose span contains the target (see `Targeting`); the others are recorded
  // in `skippedStats` with the context they would have been typed in and the
  // statement owner, for the driver to type later in the same run when a
  // request lands in one of them. Their symbols exist (the Namer indexed them)
  // and complete lazily as usual. Batch compilation never sets any of this.

  /** The offset the reader types towards; `NoSpan` for one that types everything. */
  var typingTarget: Span = Spans.NoSpan

  /** The statements `typedStats` skipped, innermost lists last. */
  val skippedStats: ListBuffer[Run.SkippedStat] = ListBuffer.empty

  /** The owner of the statement list about to be typed by `Typer.typedStats`
   *  that is subject to targeting (the package class, the template's local
   *  dummy, or the owner of a block), set by `targetNextStats` right before
   *  the call and consumed by it.
   */
  var targetedStatsOwner: Symbol = NoSymbol

  /** Set with `targetedStatsOwner` for the statements of a block: skipped
   *  ones leave a stub in the typed `Block`, and are recorded as block
   *  statements, which the driver splices back in place (`spliceAll`).
   */
  var targetedStatsInBlock: Boolean = false

  /** Mark the next `typedStats` list, owned by `owner`, as subject to
   *  targeting. Call only when `typingTarget.exists`.
   */
  def targetNextStats(owner: Symbol, inBlock: Boolean = false)(using Context): Unit =
    assert(!targetedStatsOwner.exists, i"targeting of $targetedStatsOwner not consumed before $owner")
    targetedStatsOwner = owner
    targetedStatsInBlock = inBlock
    if !inBlock then targetingTyperState = ctx.typerState

  /** The typer state of the statement list targeting started from: a package
   *  or class body, or the recorded context of a resumed statement. A block
   *  is targeted only when it is typed in this state: a block typed under a
   *  nested state (a closure argument, typed in the state `tryEither` makes
   *  for the application) or an explore state (an overload or implicit
   *  candidate) may be typed again or thrown away, and what it skipped would
   *  be recorded in a context that does not last. Such blocks are typed whole.
   */
  var targetingTyperState: TyperState | Null = null

  /** Back to the defaults: no target, no pending mark, nothing skipped. */
  def clearTargeting(): Unit =
    typingTarget = Spans.NoSpan
    targetedStatsOwner = NoSymbol
    targetedStatsInBlock = false
    targetingTyperState = null
    skippedStats.clear()

  // ---- Completion bookkeeping (interactive mode, see `SymDenotation.completeFrom`) ----

  /** The symbols whose completion was prevented as a cyclic reference
   *  (`SymDenotation.completeFrom` found `Touched` set), and the completions
   *  an exception other than a cyclic reference unwound through. A request
   *  that met either has a degraded result somewhere (an error type, a
   *  dropped item), and what was computed on top of it must not outlive the
   *  request: `InteractiveDriver.runUpTo` starts a fresh run instead of
   *  resuming a run that recorded one (the run is tainted, `Run.isTainted`).
   *  IntelliJ's `RecursionManager` has the same rule (`markStack` /
   *  `mayCacheNow`). Of the three lists only `completionsUnwound` drives
   *  behaviour (through the run's taint); `cyclesPrevented` and `leaksHealed`
   *  are for tests and the x-ray, and not used by the compiler.
   */
  val cyclesPrevented: ListBuffer[Symbol] = ListBuffer.empty
  val completionsUnwound: ListBuffer[(Symbol, Throwable)] = ListBuffer.empty
  val leaksHealed: ListBuffer[Symbol] = ListBuffer.empty

  /** The symbols whose completion a `CancelledCompilation` unwound through
   *  (recorded by `SymDenotation.completeFrom`), innermost first. A Namer
   *  completer that was unwound can not be run again: a method's completer
   *  had entered its parameters in the nested typer's scope and typed some
   *  ahead (a retry enters `T$1` next to `T` and the signature mixes the
   *  two); a class completer had set the class's temporary info, so the
   *  class is `isCompleted` without parents and is never retried. The
   *  driver replaces the statement that defines each one in the session, or
   *  poisons the session (`InteractiveDriver.replaceUnwound`).
   */
  val cancelledCompletions: ListBuffer[Symbol] = ListBuffer.empty

  /** The symbols whose completion is in progress on this reader (interactive
   *  mode only): a `Touched` flag on a symbol not in this set is a leak from
   *  an earlier unwind, not a cycle, and `completeFrom` clears it instead of
   *  reporting a cyclic reference.
   */
  val completing: mutable.HashSet[Symbol] = mutable.HashSet.empty

  def recordCyclePrevented(sym: Symbol)(using Context): Unit =
    cyclesPrevented += sym
    Run.CycleStats.prevented(sym)

  def recordLeakHealed(sym: Symbol)(using Context): Unit =
    leaksHealed += sym
    Run.CycleStats.healed(sym)

  /** Record a completion of unknown state; the run is tainted (`Run.isTainted`). */
  def recordCompletionUnwound(sym: Symbol, ex: Throwable)(using Context): Unit =
    completionsUnwound += ((sym, ex))
    val run = ctx.run
    if run != null then run.markTainted()
    Run.CycleStats.unwound(sym, ex)

  // ---- Waits on the completion lock (several readers, see `CompletionLock`) ----

  /** The completion root this reader is waiting for, if any (parallel
   *  readers, `CompletionRoots`): the edge of the wait-for graph that the
   *  cycle check follows.
   */
  @volatile var waitingOn: core.CompletionRoots.Claim | Null = null

  /** The waits this reader backed off from because they would have closed a
   *  wait-for cycle (`CompletionRoots.backOff`), for tests and the x-ray.
   */
  @volatile var waitCyclesBackedOff: Int = 0

  /** How many Namer completions this reader is inside now (parallel
   *  readers): a reader inside one never backs off from a wait-for cycle
   *  (I74), since a Namer completion cannot run twice.
   */
  @volatile var namerCompletionDepth: Int = 0

  /** The wait-for cycles between Namer completions this reader reported as a
   *  `CyclicReference` (the single-reader answer, I74). */
  @volatile var cyclesRaised: Int = 0

  /** Time this reader spent waiting for a statement another reader was
   *  typing (`InteractiveDriver`'s claims), in nanoseconds.
   */
  @volatile var statementWaitNanos: Long = 0L

  /** The runs this reader started (interactive mode), and of them those
   *  on the whole file, for tools (the x-ray's replay).
   */
  @volatile var runsStarted: Int = 0
  @volatile var wholeFileRunsStarted: Int = 0

  /** Time this reader spent blocked on the completion lock, in nanoseconds. */
  @volatile var lockWaitNanos: Long = 0L
  @volatile var lockWaitCount: Int = 0

  /** The waits of at least `Reader.recordedWaitNanos`, with what the lock
   *  holder was doing when the wait began: its root (the outermost
   *  acquisition) and its innermost, and the holder's thread.
   */
  val lockWaits: java.util.concurrent.ConcurrentLinkedQueue[Reader.LockWait] = new java.util.concurrent.ConcurrentLinkedQueue()

  def recordLockWait(nanos: Long, root: AnyRef | Null, current: AnyRef | Null, holder: String): Unit =
    lockWaitNanos += nanos
    lockWaitCount += 1
    if nanos >= Reader.recordedWaitNanos then
      lockWaits.add(Reader.LockWait(nanos, String.valueOf(root), String.valueOf(current), holder))

  // ---- The cancellation checkpoint (`Typer.typed`) ----

  private var typedTreeCount = 0

  /** The typer polls for cancellation every `checkpointMask + 1` typed trees;
   *  the mask is read from `Run.typerCheckpointMask` once, when the reader is
   *  created, so the per-tree test reads no volatile.
   */
  private val checkpointMask: Int = Run.typerCheckpointMask

  /** The number of trees typed under this reader so far (every `Typer.typed`).
   *  For tests and the x-ray; not used by the compiler.
   */
  def typedTrees: Int = typedTreeCount

  /** Called by `Typer.typed` for every tree: an increment and a mask test;
   *  the poll itself is out of line. Every 1024 trees, in interactive mode,
   *  polls the run's progress callback (the driver's sticky cancellation) and
   *  this reader's own cancellation checker, and aborts the request with a
   *  `CancelledCompilation` if either reports cancellation. Batch compilation
   *  is unaffected: there cancellation keeps skipping the remaining units
   *  instead (see `Run.didEnterUnit`), so that sbt sees a run that ends
   *  normally. (Not `inline`: the tests recognise the typer's poll by this
   *  frame.)
   */
  def checkCancelledTyping()(using Context): Unit =
    typedTreeCount += 1
    if (typedTreeCount & checkpointMask) == 0 then pollCancellation()

  private def pollCancellation()(using Context): Unit =
    if ctx.mode.is(Mode.Interactive) then
      if cancelChecker() then
        cancelledByChecker = true
        throw CompilationUnit.CancelledCompilation()
      val run = ctx.run
      if run != null && !run.canProgress() then
        throw CompilationUnit.CancelledCompilation()

  /** Did the last cancellation of this reader come from its own checker (the
   *  client's), as opposed to the driver's sticky flag (a writer pre-empting
   *  the reader, which the driver retries; see `InteractiveDriver.reading`)?
   */
  var cancelledByChecker: Boolean = false

  /** Forget the type comparers (`ContextState.reset`, at the start of a run):
   *  a comparer caches what depends on the run's settings and definitions.
   */
  private[dotc] def reset(): Unit =
    comparers.clear()
    comparersInUse = 0

object Reader:
  /** A wait on the completion lock and what the holder was doing. */
  final case class LockWait(nanos: Long, root: String, current: String, holder: String)

  /** Waits shorter than this are counted but not itemised. */
  val recordedWaitNanos: Long = 200_000L

  private[dotc] val neverCancelled: () => Boolean = () => false

  /** The reader of a `ContextBase` before its settings are known: a few fuel
   *  slots so that `handleRecursive` works for trivial things during set-up
   *  (`ContextBase.initialize` re-sizes them).
   */
  private[dotc] def initial(): Reader = new Reader(initialFuel, enrichErrorMessages = true, neverCancelled)

  private[dotc] val initialFuel = 10
