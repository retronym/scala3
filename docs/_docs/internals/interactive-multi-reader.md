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
| A. per-request scratch, wrongly shared | `ContextBase`: the context pools and `ContextPool.inUse`, the `comparers` stack, `findMemberCount`, `pendingUnderlying`, `recursiveDepth`, `errorsToBeReported`; one `Typer` per run (`Run.scala`, `setTyper(new Typer)`) with its `unimported`; the session's slots on `Run` (`typingTarget`, `skippedStats`, `targetedStatsOwner`, `targetingTyperState`, `completing`, `cancelledCompletions`, the checkpoint counter); the driver's single `mySession` and `myCancelled` | 1, but shared between concurrent readers | move to a thread-confined `Reader` (step 2) |
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

- [x] **0. Measure.** Queueing delay in a Metals request trace on the compiler's own sources, with the session; the share of a reader's time inside completers versus body typing, which says whether a coarse completion lock (step 3) leaves enough concurrency. Done, §0 below.
- [ ] **1. Model.** §7's four statements in Lean; the concurrent kernel and scheduler in the Java toy.
- [ ] **2. A `Reader`.** Move kind A (per-request scratch) off `Run` and `ContextBase` into a thread-confined reader: the target, the skipped-statement bookkeeping, `completing`, the cancellation checker, the comparers and context pools. Still one reader at a time; batch unchanged.
- [ ] **3. Several readers, one completion lock.** The read/write regime in `InteractiveDriver`; kinds C and D made safe; every completion and every resume under one reentrant lock, so concurrency is in body typing only (K2 started from global locks too). Soak and oracle suites with two readers.
- [ ] **4. Completion as lazy resolve.** The owner and barrier per root (§3.1), wait-for cycle detection (§4.2), peeks restricted to the reader (§4.1), a typer state and a reporter per completion, diagnostics stored with the fact (§4.3).
- [ ] **5. Trees.** Skipped statements as facts with a barrier, the typed tree as a persistent value (§3.2), after non-destructive attachments (I8).
- Future work: snapshot readers across an edit, which needs facts that outlive a run (stage 3).

## 9. Open questions

- Does any completer read state that is per reader and not per run, besides the typer state and the reporter (the target, the cancellation checker, `NotNullInfo`s)?
- Is a fresh typer state per completion semantics-preserving? A completer's type-ahead today can leave constraints in the namer's typer state; whether anything relies on that is to check.
- What does a waiter do when the owner is cancelled by a *writer*? It is cancelled too; the barrier only needs to release, not hand over. Hand-over is for owners unwound by their own failure.
- The checkpoint gap in implicit search (I11) bounds write latency; is a checkpoint there a prerequisite?

## 0. Measured

23 October 2026, on the branch at `cb68840779` (the session, with targeting) plus one instrumentation commit: `Scala3CompilerAccess` prints a `[dotc-queue]` line per request under `-Yinteractive-log` (the time it waited in `CompilerJobQueue`, the request that was running when it was submitted), and `QueueReplaySuite` (presentation-compiler tests) replays an editor trace against `ScalaPresentationCompiler` with its real job queue. Logs, profiles, summaries and the scripts are under `/Users/jzaugg-virtus/code/scratch/mr-measure/` (`replay/`, `profile/`, `scripts/`); the scripts are also beside the other replay scripts in `jvm-lang-models/packages/scala/probes/interrupt/metals-demo/` (uncommitted there).

### The trace

The request trace is the VS Code session of §7 of [interactive-session.md](interactive-session.md), recorded in `~/code/scratch/dotc-session-dotc/.metals/lsp.trace.json` (Metals 1.6.9, the compiler's own sources as a project, semantic highlighting off in the settings but asked for by VS Code anyway): 46 files opened, 18 edits, and per cursor stop what VS Code sends, a document highlight, a hover, inlay hints for the visible range, semantic tokens after a change, definitions on ⌘-hover, 18 completions, and 42 `$/cancelRequest`s. `trace_to_events.py` turns it into 814 presentation-compiler events with the file's text at each moment and offsets instead of positions; requests that do not reach the presentation compiler (document symbol, code lens, folding, code actions) and sources opened from jars are dropped. The trace has whole-second timestamps, so a replay sends the requests of one second either back to back (`burst`, the upper bound on queueing) or spread evenly over the second (`spread`, the lower bound), and caps the pauses between seconds at one second: the 12 hours of the trace, most of it idle, replay in about four minutes, denser than the real session.

Three replays, each on JDK 21.0.12 with `-Yinteractive-log`:

- `burst` and `spread`: headless, `QueueReplaySuite` in the test JVM, the files typed against the test classpath (class directories).
- `jars`: the same, typed against the Metals project's classpath from its Bloop config (the published compiler and library jars), under async-profiler.
- `metals`: the same events through Metals 1.6.9 over stdio (`replay_trace_lsp.py`, on a copy of the project at `~/code/scratch/dotc-session-replay`), the instrumented presentation compiler installed into `~/.ivy2/local` as `3.10.2-RC1-bin-SNAPSHOT`; Metals adds its own requests to the queue (the references and definition fallbacks over other files, semanticdb for jar sources) and its 20 s timeout cancels what starves.

```bash
sbt -java-home ~/.sdkman/candidates/java/17.0.20-tem --client "; scala3-presentation-compiler/Test/compile ; export scala3-presentation-compiler/Test/fullClasspath"
java -Xss8m -Xmx4g -Dreplay.events=replay-events.json -Dreplay.spread=burst -Dreplay.idleCap=1000 [-Dreplay.classpath=metals-classpath.txt] [-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,interval=1ms,collapsed,file=replay-cpu.collapsed] -cp <test classpath> org.junit.runner.JUnitCore dotty.tools.pc.tests.completion.QueueReplaySuite
python3 -I replay_trace_lsp.py java -cp "$(coursier fetch -p org.scalameta:metals_2.13:1.6.9)" scala.meta.metals.Main -- ~/code/scratch/dotc-session-replay replay-events.json metals-burst.json 1000 burst
python3 -I analyze_queue.py <log>; python3 -I completer_share.py <collapsed>
```

### 1. Queueing delay

Per request, the time between its submission to `CompilerJobQueue` and the start of its job (`wait`), the job's own time (the provider and the driver), and the driver's time from the request log. Hover, definition and completion are targeted (`runUpTo`); inlay hints, semantic tokens and references type the whole file (`run`), and document highlight completes the session (§7 of the session notes).

| replay | requests | waited behind another | hover wait median / p90 / max | hovers waiting > 50 ms / > 200 ms | hover job median | hover driver median |
|---|---|---|---|---|---|---|
| burst (test classpath) | 617 | 426 | 6 / 142 / 9,207 ms | 31 / 10 of 128 | 7.9 ms | 0.1 ms |
| spread | 658 | 113 | 0.1 / 198 / 9,798 ms | 20 / 14 of 142 | 8.5 ms | 0.1 ms |
| jars | 607 | 436 | 7 / 533 / 4,096 ms | 35 / 19 of 126 | 8.1 ms | 0.1 ms |
| metals | 753 | 521 | 7 / 218 / 2,979 ms | 54 / 21 of 174 | 8.0 ms | 0.1 ms |

The hover itself costs nothing in the driver once the session holds the file (served, 0.1 ms) and about 8 ms in the provider around it; what the editor waits for is the queue. The queue is LIFO (the newest request runs first), so a request that arrives during a burst waits for the request that was running and for every newer one: in the Metals replay 54 of the 534 waiting requests saw ten or more newer requests run first, and the 20 s timeout cancelled the ones that starved (49 of 153 hovers answered by cancellation, most of them timeouts, in a replay denser than the real session).

Where the waiting time went, summed over all waiting requests, by what the jobs that ran in between did in the driver (`whole-file` is a `run` that typed the file; `cached-whole-file` is a provider over an already typed file, inlay hints or semantic tokens, which costs no driver time but 100 to 150 ms of provider time per request; `completes-session` is document highlight typing the session's skipped statements; `targeted-*` are `runUpTo` requests):

| ran in between | burst | spread | jars | metals |
|---|---|---|---|---|
| whole-file run | 50% | 50% | 38% | 46% |
| provider over a cached whole-file run | 40% | 33% | 30% | 35% |
| completes the session | 3% | 5% | 15% | 8% |
| targeted, fresh or resumed | 1% | 7% | 7% | 6% |
| targeted, served | 2% | 1% | 3% | 5% |

So 89 to 93 percent of all queueing is behind whole-file work: the run that the first inlay-hint, semantic-token or document-highlight request after an open or an edit costs (median 360 ms on these files, 1.7 s cold on `TypeErasure.scala`, 6.2 s cold on `Types.scala`), and the providers that walk the whole typed tree afterwards. Targeted requests behind targeted requests are 3 to 12 percent of the waiting, and a targeted request's job is 6 to 8 ms, so a hover queued behind a hover waits a frame, not a keystroke.

What a second reader would buy here is the ability to serve a hover beside the whole-file run or the inlay-hint walk of the same file. What cancel-the-older-reader buys is already in: the LIFO order, and the session's cancellation that unwinds a whole-file run at its next checkpoint (none of the cancellations in these replays hit a run mid-way, since Metals cancels on the client's `$/cancelRequest` and the timeout only).

### 2. Completer share

async-profiler, `event=cpu`, 1 ms, over the `jars` replay (the compiler's sources against the published jars, 116,181 samples) and over the `burst` replay (test classpath, 99,006 samples), and over `SessionOracleSuite` (cold JVM, one driver per test, mostly fresh runs and resumes: 28,165 samples). Stacks are split by the driver entry point, `runUpTo0` (a targeted reader) or `run0` (a whole-file run), and classified: `completer` if any frame is inside a completion (`completeFrom`, `lockAndComplete`, a Namer completer, a symbol loader, `TreeUnpickler`, `ClassfileParser`, a lazy annotation, `InheritedCache`), including the typing a completer does and the completions forced from typing; `typing` if `Typer.typed` is on the stack with no completion above or below it; `other` for parsing, indexing, the splice and the providers. Classpath IO (`stat`, `open`, directory listings, zip lookups, from the symbol loaders) is the part of the completer share that is file-system probing.

| profile | root | completers | of which Namer / TASTy / loaders | of which classpath IO | body typing | other |
|---|---|---|---|---|---|---|
| jars replay | `runUpTo0` (3,501 samples) | 79% | 39% / 27% / 12% | 38% | 6% | 15% |
| jars replay | `run0` (23,156) | 64% | 46% / 13% / 5% | 14% | 30% | 6% |
| burst replay (class dirs) | `runUpTo0` (1,421) | 78% | 55% / 5% / 18% | 56% | 5% | 17% |
| burst replay (class dirs) | `run0` (24,657) | 70% | 48% / 13% / 8% | 28% | 25% | 5% |
| oracle suite (cold) | `runUpTo0` (2,392) | 65% | 34% / 12% / 19% | 38% | 20% | 16% |
| oracle suite (cold) | `run0` (2,774) | 69% | 45% / 11% / 12% | 34% | 24% | 8% |

Two things the numbers say. A targeted reader spends 3 to 4 percent of the driver's CPU in a replay (3,501 of 26,657 driver samples in the `jars` run): with targeting, the readers are not where the time is; the whole-file runs are. And of a reader's own time, four fifths is inside completers, a fifth of it (two fifths on class directories) in the file system under the symbol loaders, and body typing is 5 to 20 percent; even a whole-file run is 64 to 70 percent completers. A coarse completion lock (step 3) would serialise four fifths of a reader and two thirds of a background run: two readers over one file would mostly wait for each other's completions, and the gain from concurrency would be bounded by the fifth to the third that is body typing. Flame graphs: `/Users/jzaugg-virtus/code/scratch/mr-measure/profile/replay-jars-cpu.html`, `replay-burst-cpu.html`, `oracle-cpu.html`; the collapsed stacks are beside them, gzipped.

### Verdict

Queueing delay after targeting is real at the tail (a hover's p90 wait is 140 to 530 ms in a replay denser than the editor session, its median 6 ms) but it is not the delay a second reader of §1 would remove: nine tenths of it is behind whole-file work, the first `run` after an open or an edit and the providers that walk the whole typed tree (inlay hints, semantic tokens), and a targeted request behind a targeted request waits a few milliseconds. The readers that would run concurrently are four fifths completers, so steps 3 and 4 would spend their complexity to overlap the remaining fifth. The cheaper moves come first: a session that survives an edit (the future work of the session notes), which removes the per-edit whole-file run from the queue; range forms of inlay hints and semantic tokens on the Metals side, which turn the two biggest provider walks into targeted requests; and a priority or a cancellation for the whole-file askers when a targeted request is waiting, since LIFO already prefers the newest request and the session's cancellation already unwinds a run. Cancel-the-older-reader is enough for the targeted requests; what is left is not a concurrency problem but a scheduling one. Re-measure once the whole-file askers are gone; until then, step 2 (the `Reader`, which is hygiene either way) can proceed and steps 3 to 5 are not justified by this trace.

Caveats: the trace has whole-second timestamps and its pauses were capped, so `burst` overstates and `spread` understates the real burstiness; the first request on each file in a replay is cold for that file, as in the editor; the profile is CPU samples on one thread, so file-system waits inside the loaders are undercounted; the class-directory classpath of the test JVM inflates classpath IO, which the `jars` run corrects. One finding on the side: on `Typer.scala`, 14 of the `burst` replay's jobs, 25 of `jars`' and 5 of the Metals replay's failed with `AssertionError: denotation module class Trees$ invalid in run 3. ValidFor: Period(4.1-5)` (a classpath denotation validated in a later run than the current one, reached from a whole-file run by the inlay-hint provider and then from hovers), after which Metals restarts the compiler; it did not occur in the VS Code session's log and is not investigated here.
