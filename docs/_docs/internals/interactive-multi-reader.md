---
layout: doc-page
title: "Interactive: several readers over one session (design notes)"
---

# Several readers over one session: design notes

Status: exploration, 23 October 2026. Builds on the session of [interactive-session.md](interactive-session.md) (targeting, one run per content, cancellation that unwinds) and on Track I's models in `retronym/jvm-lang-models` (`packages/scala/docs/track-i*.md`, `Query/Lazy.lean`, `Query/Interrupt.lean`, `ScalaTypes/ToyTree.lean`, the Java toy in `packages/scala/probes/interrupt`). No code yet.

## Summary for a busy reader

The session answers one request at a time: Metals' `CompilerAccess` queues requests on one thread, and the driver's run, typer state and caches assume a single caller. The question here is what it takes for two requests on the same session (a hover while the session is being completed in the background, two files' requests over one classpath symbol table) to run at the same time, the way Kotlin's K2 Analysis API and IntelliJ's PSI serve concurrent read actions.

The answer this note proposes:

- **A read/write regime, not snapshots.** A write (new content: a fresh run, a re-index) is exclusive; it raises the session's sticky cancellation flag, waits for the readers to unwind, and then mutates. Readers are `runUpTo` requests at the typer phase. Roslyn-style immutable snapshots, where an old reader finishes against the old world while the new one is built, are out of reach while a run brings shared classpath denotations forward to its own run id (I14): the symbol table's clock does not roll back, so two runs cannot be read at once.
- **What a reader may write, by K2's four rules.** A reader writes only what is (1) derived and disposable by the next write, (2) published once and only when consistent, (3) idempotent, so whichever reader wins writes the same value, and (4) left unpublished if the reader is cancelled. dotc's typer state sorts into five kinds against those rules (§3); each kind has one treatment.
- **Completion becomes K2's lazy resolve.** A denotation's completion gets an owner: the thread completing it sees its intermediate state (a `TempClassInfo`, a re-entrant `Touched`), every other thread waits on a barrier until the completer publishes or unwinds, and an unwound completion leaves the symbol completable for the next reader. This is `FirInProcessOfResolvingToPhaseStateWithBarrier` in LL FIR and the black hole two evaluators meet in a parallel Haskell runtime.
- **Concurrency adds three hazards that abort-and-restart did not have** (§4). A *peek* at an in-progress completion (`isCompleting`, thirteen sites) becomes a race whose outcome depends on timing, so a peek must mean "in progress on this thread" and otherwise wait. A *wait-for cycle* between two threads is a dependency cycle and must be reported as `CyclicReference` in the thread that closes it, not deadlock. And *side effects of forcing* land on whichever reader forced: a completer's diagnostics go to that reader's reporter, so a hover that forced a signature swallows the error a diagnostics pass needed; they have to be stored with the fact.
- **Skipped statements are facts too.** A resume into a skipped statement is a lazy computation over a shared value; it gets the same owner/barrier treatment, and the unit's typed tree becomes a persistent value replaced by compare-and-set rather than spliced in place.
- **The batch compiler pays nothing it can measure.** The concurrent implementations (interning, caches that are maps, the completion barrier) are chosen when the `ContextBase` is built in interactive mode; the shared fast paths change only where a field's publication must be ordered, and that is measured with perflab before it is kept.
- **Model first, as Track I did.** Two forcers over one `Query.Lazy` store with owned black holes: an interleaving that respects the barrier agrees with a sequential `forceAll`, a wait-for cycle implies a dependency cycle, and a peek at another thread's black hole is the counterexample. The Java toy gets a concurrent dotc kernel and a deterministic scheduler over its safepoints.

Whether to build it at all is §1's question, and step 0 of the plan measures it.

## 1. What a second reader buys

With targeting and sticky cancellation, one reader at a time already gives most of the keystroke latency: a completion made obsolete by the next keystroke unwinds within 1,024 typed trees, and the next one types only its path. What one reader cannot do:

- **Background work beside foreground requests.** IntelliJ's daemon highlights a file while completion and hover run; K2's highlighting pass and the completion contributor are concurrent read actions on the same FIR. The dotc analogue is completing the session (typing every skipped statement, which document highlight, semantic tokens and diagnostics want) while hovers are served from what is already typed. Today the whole-file `run` and the hover queue behind each other.
- **Several open files over one symbol table.** The session design's future work, a session per open file, wants the files' readers to share classpath symbols (loaded once, completed once) and not to serialise.
- **Range requests in parallel.** Semantic tokens and inlay hints over the visible lines are independent ranges of one file.

What it costs is the rest of this note. The measurement that decides it is the queueing delay in a Metals request trace (how long requests wait behind another request in `CompilerAccess`), next to I12's finding that most of a completion's time is spent outside the compiler. If queueing is small once targeting is in, the cheaper move is cancel-the-older-reader, which exists.

## 2. The regime: read actions and write actions

**Readers.** A reader is one request (`runUpTo` to a target, a resume, a range) at the typer phase of the session's run. Readers run at the same phase, so no denotation transformer runs during reading and the only movement of a denotation ring is a classpath denotation brought forward to the session's run id, which is a completion like any other (§3, kind B).

**Writers.** A writer is anything that changes the source of truth: new content for a file (a fresh run), re-parsing a poisoned statement and re-indexing it, `completeSymbolsOf` before a file is left, and the end of a run. A writer raises the sticky flag that `Run.checkCancelledTyping` polls, waits for every reader to unwind to the request boundary, and runs alone. This is IntelliJ's pending write action cancelling read actions at their next `checkCanceled`, and K2's modification events invalidating `KaSession`s. A cancelled reader is restarted by its caller (`ReadAction.nonBlocking`; in Metals, the next request).

The latency of a write is the time to the slowest reader's next checkpoint. The typer polls every 1,024 trees; implicit search does not poll (I11), so a long implicit search delays the writer, as it delays cancellation today.

**Not snapshots.** Roslyn's `Compilation` is immutable: an edit makes a new compilation that shares what the edit did not touch, and a reader on the old one is never disturbed. dotc cannot do that while runs share mutable denotations: a run brings the classpath denotations it touches forward to its run id, and a query under the previous run then finds them invalid (I14). Snapshot readers across an edit need facts that outlive a run (stage 3 of the cancellation design: completers as functions of node and owner, revisions re-validated by trace), and are future work here.

## 3. What a reader writes, by kind

The four rules, stated for dotc:

1. **Disposable.** Everything a reader writes is thrown away by the next writer, or re-validated by it. A run is already this: an edit discards it.
2. **Published once, consistent.** Another reader sees either "not computed" or the finished value, never a partial one.
3. **Idempotent.** Any reader computing it would write the same value, so a race to publish needs no lock, only safe publication.
4. **Cancellation leaves it unpublished.** A reader unwound midway leaves the fact as it found it; the next reader recomputes.

The typer's state sorts into five kinds:

| kind | dotc instances | rules it meets today | treatment |
|---|---|---|---|
| A. per-request scratch, wrongly shared | `ContextBase`: the context pools and `ContextPool.inUse`, the `comparers` stack, `findMemberCount`, `pendingUnderlying`, `recursiveDepth`, `errorsToBeReported`; one `Typer` per run (`Run.scala`, `setTyper(new Typer)`) with its `unimported`; the session's slots on `Run` (`typingTarget`, `skippedStats`, `targetedStatsOwner`, `targetingTyperState`, `completing`, `cancelledCompletions`, the checkpoint counter); the driver's single `mySession` and `myCancelled` | 1, but shared between concurrent readers | move to a thread-confined `Reader` (step 2, done: `Reader.scala`, one per request thread; `errorsToBeReported` turned out to be per run and went to `Run`) |
| B. lazy facts with a black hole | `completeFrom`'s `Touched` in `myFlags` (an unsynchronised read-modify-write); `InheritedCache.locked` for base data and member names (a second black hole); `Denotation.current`, which writes validity and the `nextInRun` ring on the first read in a run; symbol loaders and TASTy completers, which share a per-file `TreeUnpickler` (`symAtAddr`, `treeAtAddr`) and enter into shared `MutableScope`s that rehash on `enter`; lazy annotations | 1, 4 (since the session's unwinding); not 2 | owner + barrier per root (§3.1) |
| C. pure caches of derived values | `Symbol.lastDenot`/`checkedPeriod` (written in that order the wrong way round for a reader: the period first); `NamedType`'s `lastDenotation`/`lastSymbol`/`checkedPeriod`/`mySignature`; period- or run-stamped memos on `ClassDenotation` (`myMemberCache`, `myBaseTypeCache`, mutable maps filled on read) and on types (`myBaseClasses`, `myReduced`, …); `Definitions.PerRun`; `ImplicitRunInfo.implicitScopeCache`; `errorTypeMsg`; `ContextBase.sources`; 634 `@threadUnsafe lazy val`s | 1, 3; 2 only if one field and not a mutable map | one-field publication; multi-field caches packed into one immutable value; maps concurrent in interactive mode |
| D. interning and counters | `Uniques`, `NamedTypeUniques`, `AppliedUniques` (unsynchronised `WeakHashSet`s on the hottest path of type creation); `_nextSymId += 1`; `NamedType.setDenot` rewriting `designator`, part of the interned key. Names are already safe (`Names.enterIfNew` and `derivedNames` are `synchronized`) | 3 only if linearizable | a concurrent interner in interactive mode; atomic counters |
| E. trees | `SymOfTree`, `TypedAhead`, `ExpandedTree` removed when typed (I8); `nestedTyper.remove(sym).get`; `setMods` on the untyped original; `withTypeUnchecked` assigning `myTpe` in place; `span_=`; the session's splice into the unit's `tpdTree` | not 2, not 4 (I8) | skipped statements as facts; the typed tree as a persistent value (§3.2) |

dotc has no precedent for concurrent readers of symbols or types. Parallel pickling and `-Ybackend-parallelism` both keep every access to the symbol table on the main thread and hand workers only context-free work (`computePickled`, ASM post-processing). `ContextBase.checkSingleThreaded`, asserted at the start of a run, records the assumption.

### 3.1 Completion as lazy resolve

Today a denotation's completion is a two-state black hole with one owner implied: `Touched` is set in `lockAndComplete`, the completer runs, `Touched` is cleared on the way out, and a re-entry while it is set is a `CyclicReference`. The session added `Run.completing`, the set of completions in progress, so that a `Touched` left by an unwound completion can be told from a cycle (`healLeakedTouched`).

With several readers the state needs its owner:

- **Not completed.** The info is a `LazyType`.
- **In progress, owned by reader R.** R is completing it. R sees what the completer has set so far (a class's `TempClassInfo`, a module's partial info) and a re-entry from R is a cycle, as today. Any other reader that needs the info waits on the barrier.
- **Completed.** The info is published; nobody waits.

An unwind (cancellation, a cycle, an exception) restores "not completed", releases the barrier, and a waiter takes ownership and completes it itself. This is LL FIR's resolve state per declaration and phase, where `FirInProcessOfResolvingToPhaseStateWithBarrier` carries the latch that other threads wait on, and `Run.completing` becomes the owner field. It meets rule 2 because nobody but the owner sees the intermediate state, and rule 4 because an unwind restores the lazy type.

What a completer reads must not be per-reader. Today a Namer completer runs in its creation context (`creationContext`, the namer's `ictx`): its typer state, its reporter, the `nestedTyper` map of the completer, `knownTypeSig`. Two readers completing two symbols of one file both write the namer's typer state. Each completion gets a fresh committable typer state of its own, whose result is fully defined when it is published (completers already call `fullyDefinedType` on inferred types), and its own reporter (§4.3).

Groups: a symbol loader completes a class and its module together, and the TASTy completers of one file share one `TreeUnpickler`'s address maps. The barrier is per *root* (the pair, or the file), not per symbol. A completion that enters into a scope another reader may be looking up in (a package's declarations) needs that scope to be safe to read during an `enter`, or the enter to happen under the scope owner's barrier.

`InheritedCache.locked` (base data, member names) is a second black hole with the same shape and gets the same treatment.

### 3.2 Trees

The session's tree state is mutable in three ways: the unit's `tpdTree` is spliced into on a resume, the skipped list is updated, and typing consumes the untyped tree's attachments (I8). A resume is the tree-level lazy fact: a skipped statement is "not typed", "being typed by R", or "typed, with this tree". It gets the same owner and barrier, and a reader that finds it in progress waits for it rather than typing it again. The typed tree of the unit becomes a persistent value: a reader takes the current one, a resume publishes a new one with its statement spliced in, by compare-and-set against the one it started from (two resumes into different statements both succeed, one after the other).

I8 matters more here than before. Today a statement unwound by a cancellation is re-parsed and re-indexed, which is a write; with several readers, a cancelled owner would hand its waiters a consumed tree. Either the re-parse is promoted to a write action (cancel every reader, re-index), or the consuming sites become non-destructive first, which is the first step of stage 3 anyway (fourteen sites, `getAttachment` for `removeAttachment`).

## 4. What concurrency adds

Abort-and-restart had one rule: every invariant holds at every checkpoint, so an unwind leaves nothing wrong. Concurrency keeps that rule and adds three.

### 4.1 A peek becomes a race

Thirteen sites ask `isCompleting` and choose a different path when a completion is in progress rather than forcing it: an import being completed is not used to resolve a name (`Typer.scala:384`, `:572`), a parent object being completed is skipped (`Typer.scala:3771`, `SymDenotations.scala:2692`), a class completer is not asked for its desugared tree (`Desugar.scala:123`). Track L calls this the peeking consumer (`forcePeek` in `Query/Lazy.lean`): its answer depends on the order of evaluation, and is deterministic only because the order is. With two readers, "in progress" can mean "on the other thread", and the answer then depends on timing.

The rule: `isCompleting` means "in progress on this reader". In progress on another reader, the peek waits for it like any other read, and then sees a completed symbol. A peek that waits can close a wait-for cycle, which is §4.2.

### 4.2 A wait-for cycle is a dependency cycle

Reader 1 completes A, whose signature needs B; reader 2 completes B, whose signature needs A. Each waits on the other's barrier. Single-threaded, the same pair is a `CyclicReference`, raised wherever the evaluation order closes the loop. So a wait that would close a cycle in the wait-for graph (each reader waits on at most one barrier; following owners from the barrier back to the waiting reader is the check, and it is cheap since waits are rare) raises `CyclicReference` in the reader that would close it, and that reader unwinds its completion as §3.1 says. The other reader's wait then succeeds.

Which reader gets the cycle depends on timing, as which symbol gets it depends on order today; Track L measured that dotc's cycles are already order sensitive, and the session's degradation (a cycle costs one symbol for one request, and nothing computed under it is cached) is what makes the answer recoverable on the next request. The guarantee to state is the session's: no answer differs from a fresh whole-file run except where a sequential evaluation in some order would also have reported the cycle.

Salsa detects cross-thread query cycles the same way (each blocked query records the runtime it waits on). IntelliJ avoids the problem by not sharing in-progress state at all: `RecursionManager` is per thread, two threads may compute the same value, and the cache takes whichever finishes (rule 3).

### 4.3 Side effects of forcing land on the forcer

Whoever forces a fact today gets its side effects:

- **Diagnostics.** A completer reports through its creation context's reporter, or through the reader's. With several readers, an error in a signature goes to whichever reader forced it first; if that was a hover, which discards its diagnostics, the diagnostics pass never sees it. The cure is the one stage 3 needs for reuse across runs: a child `StoreReporter` per completion, its diagnostics stored with the fact and re-issued to every reader that reads the fact (deduplicated by position).
- **Facts registered on other symbols.** A completer registers its symbol as a child of a sealed parent (`registerIfChildInCreationContext`), and desugaring merges a case class's synthetics into its companion's tree (I13, I22). These are writes to another symbol's state from inside a completion; under the owner rule they need the target's barrier, or (better, and what I13's fix already moved toward) to be computed by the target's own completer.
- **Fresh names and ids.** Symbol ids and a unit's fresh-name counters are allocated in evaluation order. Targeting already broke "in source order" (a resume into object 200 before object 5), so an oracle comparing with a whole-file run must already compare up to fresh names; concurrency makes the order nondeterministic as well. Counters become atomic; nothing should depend on their order.

## 5. The batch compiler pays nothing it can measure

Interning (kind D) and the caches that are maps (kind C, implicit scopes, error messages) get concurrent implementations chosen when an interactive `ContextBase` is built; the batch compiler keeps its own. The completion barrier lives on the interactive path that `lockAndComplete` already has (batch pays one mode test today). What cannot be chosen per mode are fields read on hot paths of the shared code, a `NamedType`'s cached denotation and the period it was checked in: these need one-field publication (pack the pair into one immutable value, or order the writes with release/acquire), and on Apple silicon release/acquire are `stlr`/`ldar`, not plain stores. Each such change is benchmarked with perflab (paired A/B, the compiler labs) before it is kept.

`@sharable` and `-Ycheck-reentrant` are the existing inventory of global mutable state; a checker for "written during typer by a reader, not of kinds B–E" is the tool that keeps the inventory current.

**What was measured** (steps 2 and 3, 24 October 2026). `perflab trees` over three builds of the branch, each with the library-stamps commit (220512ecb6) on top: `base` (c0d12b07d6, the session before the `Reader`), `step2` (6af4fd8b82, the `Reader`: one more dependent load on `comparer` and `handleRecursive`), `step3` (89c63a31e2: the structures chosen per base, the completion lock, the acquire/release `checkedPeriod` through `util.Publish`, `isCompleting(using Context)`). `CompileBenchmark` in memory, dottyUtil (`-wi 10 -i 12 -w 3 -r 3`) and re2s (`-wi 8 -i 10 -w 4 -r 4`), xctrace `ipc` on the benchmark thread, JDK 21.0.12 (SDKMAN, `-jvm`), `-Xms4G -Xmx4G -Xss2M`, 5 rounds, the variants interleaved per round, behind the load gate (1.5 cores). Not paired: the variants are different builds, and none of the costs is behind a per-run setting. Cells and dashboard: `~/code/.worktrees/jvm-perf-lab/mr-tree-step3/labs/scala3/out/ab/multi-reader` (served at http://127.0.0.1:8762/ab/index.html while `perflab serve` runs there).

| corpus | variant | ms/op, median of rounds (range) | Δ | instructions/op, median (range) | Δ |
|---|---|---|---|---|---|
| dottyUtil | base | 140.9 (139.7–204) | | 1.900G (1.803–1.909) | |
| dottyUtil | step2 | 141.4 (139.2–204.4) | +0.4% | 1.895G (1.787–1.908) | −0.3% |
| dottyUtil | step3 | 141.3 (139.4–191.6) | +0.3% | 1.919G (1.808–1.925) | +1.0% |
| re2s | base | 240.4 (235.8–243.3) | | 4.793G (4.725–4.816) | |
| re2s | step2 | 237.5 (236.4–290.5) | −1.2% | 4.711G (4.680–4.781) | −1.7% |
| re2s | step3 | 237.6 (235.3–343.4) | −1.2% | 4.744G (4.564–4.749) | −1.0% |

Medians, not means: the machine was shared, and every variant has one round that ran with 1.1–1.7 cores of other load (the outliers of each range; the load gate reran a dozen cells and still admitted these). The spread between rounds of one build, 1–2% in instructions, is as large as the differences between builds, so this campaign says only that steps 2 and 3 cost the batch compiler nothing above about 2%. Below that it does not resolve: the +1.0% instructions of step 3 on dottyUtil and the −1% to −1.7% on re2s are inside the noise. To decide at the 0.5% level that §5 asks for: a quiet machine, more rounds, and ideally the step 3 costs behind a per-run `-Y` switch so that they can be compared within one fork (perflab's paired mode), as the Positioned work did.

## 6. Prior art

- **Kotlin K2, the Analysis API's low-level FIR.** FIR declarations are resolved lazily to a phase, in place, under read access; a per-declaration lock with a barrier makes one thread advance a declaration while the others wait; a phase is published only once the declaration is consistent and does not advance on cancellation; "jumping" phases, whose resolution of one declaration can need another's, are where cross-thread cycles are handled. An edit inside a body with an explicit type invalidates that body alone (in-block modification). `KaSession` and its lifetime token make an analysis result unusable after the read action that produced it.
- **IntelliJ PSI.** Read and write actions under one lock, with a pending write cancelling readers at `checkCanceled`; everything but the parse a pure function of PSI cached under a modification count (`CachedValue`), computed by whoever asks first; `RecursionManager` per thread, with `mayCacheNow` refusing to cache a value computed under a prevented recursion. The Scala plugin computes types this way over immutable PSI.
- **Roslyn.** Immutable compilations; symbols complete in parts (`SymbolCompletionState`), any thread may complete a part, and the losers of a race wait for or discard their result; lazily computed fields published with compare-and-exchange; diagnostics added to the compilation's bag once, by the thread that completed the part. `concurrentBuild` binds method bodies in parallel over one symbol table, which is the shape of §1's background reader.
- **Salsa.** Parallel queries over one database, blocking on a query in progress on another thread, cross-thread cycle detection, cancellation by unwinding when the revision moves.
- **Parallel lazy evaluation.** Two evaluators meeting one thunk under evaluation: one blocks on the other's black hole (Harris, Marlow and Peyton Jones 2005), already Track I's reference for `Touched`.

## 7. Model first

Track I's method is to state the property over `Query.Lazy`, find the counterexamples in the Java toy, and then build. The statements:

- **Linearizability.** Two forcers over one store, black holes tagged with their owner, a forcer that meets another's black hole waiting: every interleaving agrees with a sequential `forceAll` of the two demand lists (the concurrent counterpart of `forceAll_ok`).
- **Cycles.** A wait-for cycle between forcers implies a cycle in the rules' dependency graph.
- **Peek.** A peek at another forcer's black hole gives two interleavings with different answers; a peek restricted to the forcer's own black holes gives none.
- **Abort.** An owner unwound while others wait: the waiter that takes over reaches the same store as if the owner had never started (the `abort_*` theorems of `Query/Interrupt.lean`, with a waiter).

The Java toy gets a `dotc-concurrent` kernel and a deterministic scheduler that interleaves two requests at every pair of safepoints, the way the Track I harness injects one request at every safepoint. On dotc itself, Lincheck (model checking of JVM concurrent code) for the completion state machine and the interner, and the soak and oracle suites run with two readers.

## 8. Plan

- [ ] **0. Measure.** Queueing delay in a Metals request trace on the compiler's own sources, with the session; the share of a reader's time inside completers versus body typing (JFR), which says whether a coarse completion lock (step 3) leaves enough concurrency.
- [ ] **1. Model.** §7's four statements in Lean; the concurrent kernel and scheduler in the Java toy.
- [x] **2. A `Reader`.** Move kind A (per-request scratch) off `Run` and `ContextBase` into a thread-confined reader: the target, the skipped-statement bookkeeping, `completing`, the cancellation checker, the comparers and context pools. Still one reader at a time; batch unchanged. *Done* (`Reader.scala`; `Context.reader` is `ContextBase.reader`). Found: the reader cannot be a field of the context, because contexts are captured and outlive the request that made them. The first version copied a reader field along the context chain, and `cancel-inside-a-given-class-completer` failed: the given's completion was forced through the `ContextualImplicits` of request 1's `import Givens.given`, whose contexts carried request 1's reader, so request 2's checkpoints counted on a dead reader and its checker was never polled. Completer creation contexts, import contexts and skipped statements' contexts all have this shape, so the reader is bound to the thread on `InteractiveContextBase` (a `ThreadLocal`, read in interactive mode only) and the batch `ContextBase` keeps a field; batch pays one more dependent load on `comparer` and `handleRecursive` (`ctx.base.reader.x` for `ctx.base.x`), measured with step 3's publication changes. `errorsToBeReported` is a fact about the run (an earlier request's error can leave a tree inconsistent for a later one), not the reader, and moved to `Run`. The typed-tree counter is per request now, which simplified the x-ray's accounting.
- [x] **3. Several readers, one completion lock.** The read/write regime in `InteractiveDriver`; kinds C and D made safe; every completion and every resume under one reentrant lock, so concurrency is in body typing only (K2 started from global locks too). Soak and oracle suites with two readers. *Done.* `InteractiveContextBase` answers `concurrentReaders`, and `ContextState` then chooses the synchronised interners (`ConcurrentUniques` and friends), an atomic symbol id, synchronised `EqHashMap`s for the member, base-type, implicit-scope, eligible-implicit and static-reference caches (`newEqHashMap`), a `TrieMap` for the typer's `nestedTyper` and the error messages; `Definitions.PerRun` publishes its pair as one object. One `completionLock` on the base covers every completion (`lockAndComplete`), every bring-forward to a new run (`Denotation.current`), the inherited caches' computations (base data, member names) and the lazy TASTy reads. The driver's regime: `readUpTo` and `readAll` are read actions that run the provider's read of the typed unit inside the read lock; a writer (a fresh run, a re-index after a cancellation, `completeSymbols` before leaving a file) raises `writerPending`, which every reader polls at its checkpoints and unwinds from, takes the write lock and runs alone; a pre-empted reader is retried by the driver, so its caller sees only its own checker's cancellation. The session's bookkeeping (what is skipped, claimed, typed; the splice; the diagnostics) is under the session's monitor, and a request landing in a statement another reader is typing waits for it. What the two-reader soak and oracle modes found, each fixed (the I-series continues Track I's):
  - I34: the base-type cache's `NoPrefix` sentinel (the in-progress mark of `ClassDenotation.baseTypeOf`) is a black hole the other reader meets as a cycle ("Cyclic reference involving class Any"). The mark is now on the reader (`Reader.pendingBaseTypes`, an identity set: a structural set made a structurally equal type in progress look cyclic too), cleared on every unwind including a cancellation, which the cache version leaked.
  - I35: the unpickler's address maps (`symAtAddr`, `treeAtAddr`) are one file's shared state, filled by completions under the lock and by lazy tree reads outside it ("key not found: Addr(417)" in `Predef.tasty`): lazy reads take the lock.
  - I36: `NamedType.stableInRunSymbol` saw a current `checkedPeriod` and a null `lastSymbol`: the period publishes the denotation fields and must be stored after them with release semantics and read with acquire (`util.Publish`, a Java class so that the `VarHandle`s are `static final` and folded; `Symbol.checkedPeriod` likewise, where the writes were in the wrong order).
  - I37: a statement whose typing a writer's pre-emption unwound was abandoned (re-indexed) on a session another writer had already replaced ("denotation class M10 invalid in run 3"): abandons are deferred to a write action and dropped when the session is no longer current.
  - I38: a pooled typer state (`withFreshTyperState`) stayed uncommittable when the merge in `commit` threw; it is restored in `finally`.
  - I39: a pre-empted reader rethrew instead of retrying when the writer's flag was already down by the time it checked; the reader records whether its own checker cancelled it (`Reader.cancelledByChecker`).
  - I40: a request whose own fresh run left the session tainted ran again and again; it now reads the result of the run it made.
  - I41: `isCompleting` (the thirteen peeks) meant "in progress on any reader": a peek outside the lock took another reader's completion for a cycle and skipped the symbol (the step 1 model's `peek_any_races`). It now means "on this reader": the lock holder's own, and another reader's is waited for (the lock) and looked at again. The two precondition assertions keep the raw test.
  - I42: a completer writes type variables and constraints into its creation context's typer state (the run's), under the completion lock, while a resume committed into the same state under the session's monitor: an inconsistent `OrderingConstraint` that `instantiateTypeVars` looped over until the heap ran out. Commits take the completion lock too.
  - I43: a livelock. Two readers on different content each made a fresh run and read its result afterwards under the read lock; the other's run replaced it in between, and the two ran forever (run ids past 65,535, wrapping to 0: "denotation class Enum invalid in run 0"). A fresh run and the read of its result are one write action (`writtenAndRead`).
  - I44: a reader waiting on the lock to complete a symbol had captured its completer before the wait; the owner had meanwhile installed the unpickler's placeholder `NoCompleter` (or finished), and running the captured one threw "UnsupportedOperationException: complete". After the wait the symbol's current info is looked at.
  - I45: `cleanup` after a whole-file run errored out every symbol of the typed tree that was not completed, meant for the file's own Namer completers; a classpath symbol another reader was loading was overwritten for good (a false "unspecified error" on `new VCInlineMethods` in Compiler.scala in the x-ray). It is restricted to Namer completers, under the lock.
  - I46: `MemberNamesImpl` read its cache twice (check, then use) while another reader could invalidate it in between.
  - I47: the nesting depth of `withCancelChecker` scopes was one field for all threads: one thread's open scope kept the other on its previous reader, whose checker belonged to an earlier, cancelled request, and a cancellation reached a caller that had none.
  - I48, open: what is left is completions racing readers. A completer publishes intermediate infos (`TempClassInfo` during a class's completion, `TypeBounds.empty` and a placeholder completer while a TASTy type is read) that a reader outside the lock takes as complete, computes from and caches; and a resume's typer state is a child of the run's, which completers on the other thread commit into and garbage-collect (a type variable whose owning state is gone: "Inconsistent state in TS[…]", a null `owningState`). With `-Dinteractive.readUnderCompletionLock` (every read action under the completion lock) the suites pass, 50 of 50; without it, 46 of 50, and all four failures are of this kind. So step 3's premise, concurrency in body typing only, does not hold in dotc as it is: body typing reads what completions are in the middle of writing. The cure is §3.1's: a fresh committable typer state per completion, and an owner and barrier that hide a completion's intermediate state from other readers, which means a check on the hot `Denotation.info` path (to be measured). This is §9's second question, and step 4.
  - I49, open, single reader: the session's diagnostics for `Typer.scala` miss one that a whole-file run reports, a scaladoc warning ("Variable $x undefined in comment for method decomposeProtoFunction"), with or without a second reader. Comments are cooked once per symbol and cached (`ContextDocstrings.cookedDocComment`); the targeted session first cooks this one under a typer state that is thrown away, so the warning goes with it, and the cache keeps it from being reported again. §4.3's rule (a side effect of forcing lands on whoever forced first) with one reader.

  **The lock-wait milestone** (the x-ray, `presentation-compiler/test/dotty/tools/pc/xray/lockwait.py`; JDK 21.0.12, warm JVM, 5 passes per file). The background pass is one reader on its own thread: a targeted request at the top of a random 60-line viewport, then the session's completion, which claims one skipped statement at a time, those overlapping the viewport first, and types it; meanwhile hovers (`readUpTo` and the type at a random identifier, no x-ray bookkeeping) are sent back to back from the server's request thread, a second reader. Each hover's wall time, its time blocked on the completion lock (`CompletionLock`, which records what the holder was doing) and its time waiting for a statement the pass was typing are recorded separately. Correctness check: the pass's diagnostics against a whole-file run in a fresh driver.

  | | `core/Types.scala` | `typer/Typer.scala` |
  |---|---|---|
  | skipped statements at the start of a pass | 196–383 | 131–151 |
  | visible lines typed | 125–146 ms (332 ms on the first, cold pass) | 155–203 ms |
  | whole pass | 543–765 ms (2.3 s cold) | 488–766 ms |
  | diagnostics, pass = whole-file run | 55 = 55, all passes | 108 vs 109, all passes (I49) |
  | hovers beside a pass / of which waited on the lock | 285 / 65 | 170 / 46 |
  | hover wall time, median / p90 / max | 1.0 / 7.9 / 497 ms | 0.7 / 11.5 / 213 ms |
  | hover lock wait, median / p90 / max | 0.0 / 0.5 / 12.8 ms | 0.0 / 1.4 / 14.5 ms |
  | hover wait for a statement the pass is typing, max | 61.5 ms | 18.5 ms |
  | the rest of a hover (its own resume or typing), p90 / max | 6.5 / 485 ms | 8.9 / 208 ms |

  Where the itemised lock waits (0.2 ms or more) were, by the holder's root work: Namer signatures (completions that type inferred signatures ahead, e.g. `typedSelectWithAdapt.tryType`): 39 waits, 42 ms on Types.scala, 78 waits, 69 ms on Typer.scala, the longest 7–8 ms; TASTy unpickling 11 and 7 waits, the longest 3.3 ms; classpath loads, inherited caches and bring-forwards a few each, under 5 ms. The long hovers are not waits: the longest hovers claimed a statement the pass had not reached and typed it themselves (a resume, as a single reader would). So the coarse lock is not where a hover's time goes: the median hover never waits, and at p90 a hover waits about a millisecond. What step 4 would buy is correctness (I48), not latency. Caveat: these runs are on the coarse lock without `-Dinteractive.readUnderCompletionLock`, so answers may be wrong in the I48 ways while the times are right; serialising the reads would make a hover wait for the whole statement the pass is typing.

  **The serialised mode** (`-Dinteractive.readUnderCompletionLock`, the mode in which the suites pass 50 of 50), the same runs. A request's read action is one unit under the completion lock, and so is each statement of the pass: claimed and typed under the lock together. The first version claimed a statement and then took the lock to type it, and deadlocked with a hover that held the lock and waited for that statement (finding I51). The pass's own times are unchanged; the hovers are not:

  | serialised mode | `core/Types.scala` | `typer/Typer.scala` |
  |---|---|---|
  | hovers beside a pass / of which waited on the lock | 20 / 15 | 18 / 13 |
  | hover wall time, median / p90 / max | 141 / 457 / 740 ms | 158 / 279 / 369 ms |
  | hover lock wait, median / p90 / max | 12 / 401 / 737 ms | 12 / 278 / 368 ms |
  | the longest waits, behind the pass typing | line 3413 (1,461 chars) 737 ms; line 821 (10,117 chars) 457 ms; line 3549 401 ms | line 204 (95 chars) 368 ms; line 837 (8,924 chars) 335 ms; line 1933 (10,860 chars) 278 ms |

  So serialised hovers are not a ship point at these numbers: a hover waits for whatever statement the pass is typing, a median of 140–160 ms and up to 0.7 s. The size of the statement does not predict the wait (a 95-character statement held the lock for 368 ms): what the time goes into is the completions the statement forces (type-ahead of inferred signatures, which can type another member's whole body, and classpath loads), so smaller claims would not bound it. What would is a pass that yields the lock at its typer checkpoints while it is in no completion (the lock's hold count is the statement's), with a hover that lands in the pass's claimed statement releasing the lock while it waits for it; that is cooperative scheduling over the serialised mode, an alternative to step 4 for latency but not for I48 (a yield inside body typing is safe only between completions, which is where the serialised mode already is). With I49 the Typer.scala count matched in some passes and not in others: the lost warning depends on the order in which the comment is first cooked.

  Step 1's I33 (a cancelled owner must reset `Touched` in its unwinding path, or a waiter is stuck) holds already: `onUnwind` clears the flag for a cancellation in any mode. Step 0 measured that the Metals trace's queueing is behind whole-file work, not targeted requests, and did not justify this step by itself; what it buys is the background pass of the x-ray (the session completed by a second reader while hovers are served).
- [ ] **4. Completion as lazy resolve.** The owner and barrier per root (§3.1), wait-for cycle detection (§4.2), peeks restricted to the reader (§4.1), a typer state and a reporter per completion, diagnostics stored with the fact (§4.3).
- [ ] **5. Trees.** Skipped statements as facts with a barrier, the typed tree as a persistent value (§3.2), after non-destructive attachments (I8).
- Future work: snapshot readers across an edit, which needs facts that outlive a run (stage 3).

## 9. Open questions

- Does any completer read state that is per reader and not per run, besides the typer state and the reporter (the target, the cancellation checker, `NotNullInfo`s)?
- Is a fresh typer state per completion semantics-preserving? A completer's type-ahead today can leave constraints in the namer's typer state; whether anything relies on that is to check.
- What does a waiter do when the owner is cancelled by a *writer*? It is cancelled too; the barrier only needs to release, not hand over. Hand-over is for owners unwound by their own failure.
- The checkpoint gap in implicit search (I11) bounds write latency; is a checkpoint there a prerequisite?
