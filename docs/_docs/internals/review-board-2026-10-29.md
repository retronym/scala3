---
layout: doc-page
title: "Review board: the multi-reader session and the parallel-typer spike (29 October 2026)"
---

# Review board: retronym/scala3#84 (multi-reader session, `-Yparallel-typer` spike)

Reviewed at `a084128416` (52 commits over `track-i/base`, 209 files, +21k/−1.7k over `track-i/dotc-session`); the branch has since gained dcfb659ec2 (`Published` handles as `@static` vals), which this review does not cover. Written with an LLM (Claude) from the PR, its design notes and the code, as a review for the author; a second pass over the concurrency model follows. Read as a PoC. Nothing was built or run for this review; every "byte-identical" and timing claim below is the PR's own unless marked otherwise. Sections: quick wins, the concurrency model (gaps and unstated assumptions), the value thesis.

One housekeeping point first: the PR description stops at the interactive work and the 24 October checkpoint. The tip has 22 further commits that turn the branch into a batch parallel-typer spike (`-Yparallel-typer`, parallel signatures, PostTyper/inline-traits/Pickler a unit per task, the denotation-ring claim). A reader of the PR page does not know that. The description needs a "since the checkpoint" section or the batch work needs its own PR on top.

## 1. Quick wins

Candidates that stand without any experimental flag. "Week" means isolated, tested, small; "month" means extraction or an A/B is needed first.

| # | Change | Where | Size | Bucket | Notes |
|---|---|---|---|---|---|
| 1 | Parser: a `.` ending a line does not swallow the next line | 68c8c6c687, `Parsers.scala`, `tests/neg/dangling-select-eol.scala` | +34/−2 | week | Error recovery only. Has a neg test. A parser reviewer may want more cases (`x.` before `}`, before `case`, in a template body). |
| 2 | `CheckShadowing` root-import lookup by name | the CheckShadowing hunk of 73c59487a3, `tests/warn/synthetic-scala-members-deterministic*.scala` | +6/−3 | week | A stock nondeterminism: `-Wshadow:type-parameter-shadow` depended on whether `FunctionN` (N>22) or `ContextFunctionN` had been mentioned earlier in the run. Two warn tests. Take only the CheckShadowing hunk; the Definitions/Scopes rework in the same commit belongs to the sharing work. Not in the author's own §11 list. |
| 3 | `withSharedCompiler` holds the driver monitor per job (I50) | 6af4fd8b18, `Scala3CompilerAccess.scala`, `CompilerAccessResetSuite`, `QueueReplaySuite` | +8 logic | week | Fixes `denotation ... invalid in run N` after an mtags timeout. The mtags side (old executor only `shutdown()`, backlog keeps running) still needs a Metals issue. |
| 4 | Explicit type args on parents that inferred them | 69254fa8ff, `Trees.scala`, `Applications.scala` | ±10 | week | Hygiene in the compiler's own sources. |
| 5 | `cleanup` strips annotation trees of the file's own symbols only (I100) | 48c30873bf, `InteractiveDriver.scala` | 2-line port | week | On stock it is wasted-work saving, not a race fix. The §11 claim "with a test" is not borne out: no test exercises `cleanup`. |
| 6 | `SingleDenotation.initial` on a replaced version loops forever | a084128416, `Denotations.scala` | +7 | week, defensive | `replaceWith` sets `validFor = Nowhere` on stock too, so the loop is real on stock; no stock caller reaches it serially. A one-line guard; argue it as defence against a hang rather than a fix of a reproduced bug. |
| 7 | Cyclic-reference messages do not re-run the cycle | 8899fc4dbb, `TypeErrors.scala`, `Message.scala`, `messages.scala`, `PlainPrinter.scala` | +52/−25 | month | Mechanism is sound (`flagsUNSAFE` via `Symbol.denot` re-ran the transform that cycled). But it changes message text paths (`cycleName` imitates the printer) and adds a rendering try/catch. Needs a stock `tests/neg` repro and a full checkfile run before proposing. |
| 8 | Names hash by content, not identity | dcca16aa7a, `Names.scala`, `NameKinds.scala` | +13 | month | A reproducibility win across JVMs (export-forwarder order, `memberNames`), but it changes serial output and the hash cost is unmeasured. Needs a perflab A/B and a bytecode/TASTy diff over `testCompilation`. Pitch it upstream as "reproducible across JVMs", not as parallel-typer support. |
| 9 | Lazy annotation thunk restored after any exception | part of 4e71084244, `Annotations.scala` | +20/−6 | month | Stock leaves a `LazyBodyAnnotation` "being evaluated" after a non-cancellation exception, so the next inline of that method fails with "re-entered the evaluation". Needs its own test and the parallel-only println removed. Check that a deterministically failing thunk re-run on each access does not repeat side effects (error reports). |
| 10 | Shape-3 (check-then-act) hygiene: `SourceFile.offsetToLine`, `ImportInfo.featureImported`, `SelectorInfo` as one value | 73c59487a3 | ~75 | month | Behaviour-neutral serially; strip the `@cache`/`@benignRace` annotations (they come from the PR's `library/.../internal` additions) before upstreaming. |
| 11 | Cancellation that reaches the typer (I11) | session branch: `Run.checkCancelledTyping`, one hook in `Typer.typed`, non-consuming `InteractiveDriver.isCancelled` | small core, large driver | month | Separable in principle (sticky flag plus the checkpoint); today it is entangled with `CancelledCompilation` and the progress callback. Still no checkpoint inside implicit search. |

Not quick wins, despite being listed as "upstreamable now" in §11 of the design notes:

- **I10 (unguarded 16-bit run id).** No fix and no test exist in the PR or the session branch. `MaxPossibleRunId` is still an unused `inline val`. It is a TODO; the fix is a one-line assertion plus a driver reset, but it is not written.
- **`Reader` refactor.** Hygiene the batch compiler could take, but it carries targeting slots, cancellation and stamps, and the measurement that covers it (§5, cross-build, ±2%) does not resolve the 0.5% the design asked for. Needs the paired in-fork A/B first.
- **`addChild` lost update.** The fix is gated on `ctx.base.parallel`; serially there is no bug to cite.
- **`checkNonCyclic.isInteresting` rework** (a5ebba4138). The mechanism is right, but the edited expectations of `tests/neg/i8984.scala` are not explained by it: the commit says the test already failed on this branch (4 errors where 5 were expected) with or without the names-hash commit, and the file was edited to match. That is an unbisected regression somewhere on the branch (candidates: the cycle-degradation commits of the session branch, or the message changes of 8899fc4dbb). Bisect against `track-i/dotc-session` before any of the cycle-related commits goes upstream. This is the one item on the branch I would call a correctness red flag for upstreaming.

Everything else (Stamped caches, `Published`, `Once`, the striped interners, `SyncEqHashMap`, per-unit pickler, `inParallel`) depends on the vendored `sharing` module or the parallel base and is "later": its upstream path is the module as a whole, and that is a design discussion, not a quick win.

## 2. The concurrency model: what is missed, what is assumed

The model (owner and barrier per completion root, `InProgress` seen through only by its owner, private typer states settled at the end, stamped caches as immutable holders, release/acquire on `checkedPeriod`, one writer per denotation ring) is the right shape, and the Lean result that the barrier alone is the correctness argument is the part worth keeping whatever happens to the code. What follows is what the model does not cover, found by reading the batch spike's driver and the shared-state inventory. Severity is for the batch spike unless stated; the interactive mode has a fresh run to retry on, batch does not.

### 2.1 Reachability: the flag cannot run where the thesis lives

`-Yparallel-typer` is honoured only by `Driver.process(args, reporter, callback)`, which sniffs the raw args (`ParallelBatch.requested`) to pick a `ParallelBatchContextBase` before settings are parsed. The `ScalaSettings.YparallelTyper` setting is declared and never read. The sbt bridge (`CompilerBridgeDriver`) builds `initCtx().fresh()` and calls `setup` and `doCompile` itself, so from sbt, Zinc, Bloop and Mill the flag is a no-op. The build-server case the value thesis rests on has not been run once through the path that would run it. The fix is small (the bridge sniffs too, or `initCtx` becomes a factory that reads the flag), but every measurement so far is from `Driver.process` and the x-ray's in-memory classpath, not from a bridge compile with an `IncrementalCallback` attached.

### 2.2 Unit-owned state reached through creation contexts

The design's kind A says per-request scratch moves to the `Reader`; the batch spike adds an implicit kind, "per unit, written by the unit's thread". That assumption is false: a completer runs in its creation context, whose `compilationUnit` is the defining unit, so thread A forcing a member of unit B writes B's unit-owned state while B's own thread is writing it. Covered: `FreshNameCreator` (now `synchronized`). Not covered that I could find:

- `DependencyRecorder` (`sbt/ExtractDependencies.scala`): `_names` is dotc's `util.HashMap`, `_classes` an `EqHashMap`, `lastFoundCache` a plain field. Written during the typer by `Synthesizer` (mirror synthesis, from implicit search) and by `Typer` (`_responsibleForImports`). An implicit search inside a signature completion (a `given` with an inferred type, a `summon` default) runs on the forcing thread in the defining unit's context. Rare, silent, and invisible to the byte-identical check: it corrupts Zinc's dependency set, not TASTy.
- `CompilationUnit` flags written by typing (`needsInlining`, `needsStaging`, `needsQuotePickling`, macro-annotation flags): the design names them for the statement-level unit of work (§5.9), but inlining B's inline method into A on A's thread already evaluates B's `LazyBodyAnnotation` in B's context today. Check whether the body's typing sets flags on B.
- Any `Property.Key` store on the unit's context, and `unit.source`-level caches, by the same route.

The general fix is one of two: make unit-owned state a claim (`Holds[unit.type]`) like the TASTy unpickler's, or make completers run in a context whose unit is the forcing unit for the state that is bookkeeping (dependencies, flags) and the defining unit for the state that is semantic (fresh names, owner). That split is a design decision the notes have not made.

### 2.3 Macro expansion is not modelled and not exercised

Both corpora (the compiler's 618 sources, the library's 722) expand almost no macros. Under `-Yparallel-typer`, macro expansion happens on workers during body typing, and three pieces of state are shared with no protection:

- `QuotesCache`: a `collection.mutable.Map` per run, written by every quote unpickling, untouched by the PR.
- `MacroClassLoader`: one URLClassLoader per run, so user macro implementations' statics (`object` caches, counters) are shared across worker threads. The PR's correctness story cannot speak for user code; Scala 2 never had this exposure because one run was one thread. This is the assumption most likely to break in the wild: Magnolia-style derivation libraries cache in objects.
- `Interpreter`/`Splicer` reflective calls and `report` from macro code (buffered reporter, fine) and `Expr.summon`/`Symbol.info` from reflection, which force completions and so take claims from inside user code, where a wait-for cycle's victim choice has no idea whether the frame can be unwound.

Suspension (`unit.suspend` from a macro that needs a class compiled in the same run) is caught per unit in `eachUnit` and in `typeCheck`, and `Run.suspension` is a `Sharing.Guard` (monitor evidence), so the set itself is covered. What is not examined is the second run over the suspended units: it starts on a base whose symbol table was filled by a parallel run, and the claims that protected it are gone; the ring claim covers denotations, but nothing in the notes says the hand-over from a parallel run to the serial retry run has been exercised with a macro that actually suspends.

### 2.4 Failure handling in the spike

`Phases.inParallel` submits every unit, then `get()`s in source order and rethrows the first `ExecutionException`'s cause. It calls `pool.shutdown()`, not `shutdownNow()`, so the remaining tasks run to completion after the first failure, holding claims and reporting into a reporter whose run is already unwinding. A `StackOverflowError` or `FatalError` in one unit therefore does not stop the others; `unbindThread` runs per pool thread, which is right, but the main thread's `unbindThread` is only in `Driver.doCompile`'s `finally`, so the bridge's path (which does not go through `doCompile`? verify) and the REPL's would leak the reader ThreadLocal across runs if they ever used this base.

An aborted unit (victim of a wait-for cycle) is a compile error by design ("cannot be typed again in this run"). The design's answer is whole-run fallback to serial (§5.3 of the parallel-signatures notes), which is designed but not implemented. Until it is, the spike's failure mode on an unlucky schedule is a spurious error, and the error names no cause a user could act on. For a build tool this is the difference between "slow sometimes" and "flaky".

### 2.5 Liveness

- Waiting is in 5 ms slices with a cycle check at each slice. At eight readers that is cheap; at a build server's 32 to 64 threads with coarse roots it is a busy-wait on the scheduler plus an O(waiters × chain) graph walk each slice. The classpath is now one reentrant claim (`SymbolLoaders.ClasspathRoot`), so classpath loading is serial; cold runs took 10.3 s either way, and the author notes it. On a build server the classpath is warm, so this is a cold-start cost, but it also means any body-typing reader that needs a not-yet-loaded classpath symbol queues behind every other loader, and the first run of a project in a fresh Bloop JVM is a serial classpath load behind one lock.
- The 10 s coarse-root timeout and the 15 s watchdog are tuning constants with no derivation. A legitimate 12 s TASTy load of a huge dependency under a coarse root would be aborted as a cycle.
- JVM monitors (scope `synchronized`, striped interner monitors, the reporter monitor, `FreshNameCreator`) and Scala `lazy val` latches are invisible to the wait-for graph. The author found one (`OfTypeImplicits.eligible`); the design has no inventory of `lazy val`s on shared objects reachable from readers (the compiler has hundreds of `@threadUnsafe lazy val`s and a few plain ones on `Definitions`, `Run`, `ContextBase`). A `lazy val` that forces a completion while holding its latch is a lock the detector cannot see, and with Scala 3's `lazy val` implementation a second thread parks on it.

### 2.6 Unstated assumptions, as one-liners

1. A `CompilationUnit`'s mutable state is written only by the thread typing that unit (false through creation contexts, §2.2).
2. User code run at compile time (macros, inline interpretation) is thread-safe when run concurrently in one JVM (never true before; §2.3).
3. The corpus that found the races (dotc sources, library) is representative. It has almost no macros, no Java sources beyond `-Xjava-tasty`, no Scala.js, no `-Ycheck`, no `-from-tasty`, no compiler plugins, no `-Werror` with error-count short-circuit between phases.
4. A build server is warm, so cold numbers can be discounted. True for Bloop and sbt server; false for CI runners and for Bazel non-persistent workers, where the cold 28.4 → 24.6 s is the whole story.
5. The byte-identical check (TASTy and classfiles) proves correctness. It proves determinism of the output and nothing about Zinc's analysis, diagnostics content beyond count, or `-Xprint` output; a lost update in a `DependencyRecorder` or a `Child` annotation that happens to be re-derived later passes it.
6. Signatures are all complete before any body is typed. True for source symbols after the serial pass; not for anything completed lazily later (typed-ahead trees, default getters, export forwarders entered by typing, `LazyBodyAnnotation`s, inherited caches), which is where the interactive findings I60 to I81 came from and where the spike's bodies still meet each other.
7. The number of threads is `availableProcessors`. On an 18-core laptop with 6 performance cores that is where the CPU inflation comes from; on a build server shared with other compiles it is wrong by construction (§3).
8. Wait-for cycles between Namer roots are either lock orders or dependency cycles and the victim rule can tell. I68 is still open at k=8; the toy's answer is to stage decls, which is designed, not built.
9. `ParallelBatchReporter` buffering and sorting by file and position reproduces the serial order. It does for positioned diagnostics; nopos diagnostics, the error-count short-circuit that `Run.compileUnits` checks between phases, and `-Werror` interplay were not examined.

### 2.7 What the model gets right

- Measuring the Metals queue before building concurrency, and letting it reorder the plan (body edits and range requests first), is the best decision in the PR.
- Owner and barrier as the whole correctness argument, with the Lean `run_agree` result behind it, is a stronger foundation than any of the prior art the notes cite except Roslyn's.
- `Holds[key.type]` evidence as a compile-time `REQUIRES` is cheap and found real bugs; it is the piece of the sharing library most worth keeping even if the rest is rewritten.
- `Stamped` as an immutable holder is correct under JLS §17.5 final-field semantics provided the class's fields are JVM-final and `this` does not escape the constructor; both hold for a Scala `final case class` with `val` fields. It costs nothing in batch and removed fields.
- The state map (every mutable field with a kind and a protection, checked against TASTy) is the only way a change of this size stays reviewable; it should ship with whatever lands.
- Serial class headers first, then parallel member signatures, then parallel bodies, with the toy's diagnosis that a cycle through decls is harmless on one stack and a wait-for cycle on two, is the right staging and matches K2's phases.

## 3. The value thesis, challenged

The thesis: parallel typechecking shortens the critical path of large build graphs, and lets a build server fill its cores with fewer concurrent projects, so with fewer symbol tables resident. Three parts, taken in turn, then what would settle it.

### 3.1 CPU efficiency decides whether it helps a saturated server at all

The throughput table in the PR is the most important table in it, and it argues against the second half of the thesis as stated:

| readers | wall | worker CPU | CPU per unit of wall saved |
|---|---|---|---|
| 1 | 5.7 s | 5.2 s | |
| 8 | 2.1 s | 10.2 s | 2× the CPU for 2.7× the speed |

The batch typer does the same 484 files in 4.1 s on one thread. So eight readers beat the batch typer by 2× while burning about 2.5× its CPU. On a build server whose cores are already full of project-level parallelism (which is close to 100% efficient: each project is an independent single-threaded compile), replacing one project-compile with eight threads of a parallel one lowers total throughput. The spike inherits part of this (the inline-traits group: 196 → 294 ms CPU at 6 threads; the interactive mode's statement-by-statement resumption is 1.4× batch CPU per file). Some of the inflation is the M-series efficiency cores and is an artefact of the laptop; how much is unknown because the batch spike's total CPU at k=1 versus k=8 is recorded by the timeline but not reported anywhere.

Consequence: the parallel typer is a critical-path tool, not a throughput tool. It pays when the DAG cannot fill the machine (the neck of a monorepo: one core module gating two hundred downstream, or the final link-like module), and costs when it can. That makes the thread count a scheduling decision of the build tool, taken from the number of idle cores at the moment the compile starts, which neither sbt (`Tags`), Bloop, Mill nor Zinc exposes today. Without that policy the flag is a footgun on CI runners with 4 vCPUs and a wide graph.

### 3.2 The critical path is gated by what stays serial

Cold, on the compiler's sources: typer 10.2 → 6.2 s, wall 28.4 → 24.6 s. The 14 s after the typer are untouched by the spike except PostTyper, inline traits and the pickler, and the backend already has `-Ybackend-parallelism`. Amdahl, project-level: the gain on a non-pipelined critical path is 13% at 18 threads, cold.

With build pipelining (`-Xearly-tasty-output`, sbt 1.9+ and Mill), downstream projects start at the pickler, so the segment that gates the DAG is parse to pickler. That is exactly the segment the spike parallelises: warm, 18 threads, 1.8 to 2.4 s to the pickler, against a serial warm figure the PR does not report (an estimate from its own parts is 6 to 7 s, so about 3×). This is the strongest version of the thesis and the PR does not make it: parallel typer plus pipelining compounds, and nothing else attacks the pipelined critical path once pipelining is on. The measurement to add is time-to-early-TASTy, serial versus parallel, warm, through the sbt bridge (§2.1), on a project with a real neck (the compiler itself is one: `compiler` gates `scaladoc`, `presentation-compiler`, `sbt-bridge`, `scala3-language-server`, the tests).

Within a project the bound is the serial steps: index, class headers, and the classpath load behind one claim. On dotc, headers alone are 1.0 to 1.2 s cold and the design to parallelise them (stage decls, §5.1) is the hardest remaining piece. The file as the unit of work does not bind at 18 threads but will at 36, or in a project with one 10k-line file, which large monorepo necks tend to have.

### 3.3 Memory: the thesis is roughly right, but a different cut of the same work attacks it more directly

A Bloop or sbt server compiling N projects concurrently holds N `ContextBase`s, each with its own classpath symbol table (scala-library plus common dependencies, loaded and completed per base), its own interners, and its own units' trees. Resident memory is close to linear in N, and the parallel typer lets N fall for the same core utilisation. Per compile, the parallel typer adds little: all units' typed trees are live until the end of the run in batch anyway, per-thread scratch is small, interners are striped not duplicated. So the memory claim holds to first order, with the caveat that the allocation rate rises with the thread count (more GC, larger young generation wanted), which the x-ray's 20 GB heap settings quietly acknowledge.

But the multi-reader base makes a more direct attack thinkable, and it is not in the PR's plan: one `ContextBase` per classpath, shared by the concurrent compiles that have that classpath prefix in common, with each compile's own symbols in a layer above. That removes the N× duplication of the classpath symbol table without changing the scheduling, keeps project-level parallelism's CPU efficiency, and is the shape Metals would want for its per-module presentation compilers too (the §1 "several open files over one symbol table" case, one level up). It needs the same owner-and-barrier discipline for classpath completions that the PR has already built, plus run-id and period handling across bases, which the notes say is out of reach (I14: a run brings classpath denotations forward to its run id). I would want that trade-off written down before committing to parallel typing as the memory lever.

### 3.4 Other challenges

- **Determinism across modes.** Serial and parallel outputs differ in 75 TASTy files (fresh-name numbers, a shared tree emitted once). Build caches key on inputs, so caching survives, but a reproducible-build check, a Zinc analysis compared across a developer's parallel compile and CI's serial one, or a `-Ytest-pickler` run would see the difference. Either the serial compiler adopts the spike's order (all signatures, then bodies) or the flag is documented as changing output.
- **Correctness tail.** I68 is open: at k=8 over 484 files, 7 of 20 × 484 runs diverged. A compiler that miscompiles or reports a false cycle one run in a thousand is unusable in a build server, however fast; the whole-run serial fallback (§5.3) is the safety valve and must land before any user sees the flag, with its fallback rate measured on a corpus that includes macros.
- **Where the time actually goes in real builds.** The compiler's sources are an unusually body-heavy, macro-light corpus with warm classpath. Typical application code is dominated by classpath loading (I12: most of a completion's time outside the compiler), given/implicit search, and macro expansion (derivation), none of which the spike speeds up and two of which it serialises or does not model. A measurement on three real projects (an Akka/Pekko module, a ZIO or cats service with heavy derivation, a Spark job) would say whether the 2 to 3× on dotc is representative or an upper bound.
- **The alternative the compiler already ships.** Pipelining gives the DAG-level win today with no concurrency risk. The honest framing of the parallel typer is as its complement for the pipelined critical path, not as a replacement for project-level parallelism.

### 3.5 What would settle it

1. Wire the flag through the sbt bridge and compile the scala3 build itself with `-Yparallel-typer` on `compiler`, pipelining on, and report wall time of the full build and of the longest path, serial versus parallel, warm server, with total CPU from the timeline.
2. Report the spike's own CPU at k=1, 4, 8 beside wall, on homogeneous cores (a Linux box), so the inflation is separated from the E-core artefact.
3. A macro-heavy corpus under the flag with the chaos switch on, before any claim about correctness.
4. A one-page scheduling policy for sbt or Bloop: threads = idle cores at start, bounded by a setting, with the serial fallback. Without this the value is not realisable by a user.

## 4. Verdict

As a PoC it has done what a PoC should: measured first, modelled the hard part, found the real hazards (every peek is a race; roots coarser than facts turn lock orders into cycles; completions are the time, not bodies), and left a map of the state. The upstreamable residue this week is small and real (items 1 to 6 of §1); the one red flag is the edited `i8984` expectations, which should be bisected before any cycle-related commit moves. The batch spike's numbers are promising for the pipelined critical path and unproven for everything else; the two things that decide its value, the bridge path and CPU efficiency on homogeneous cores, are both unmeasured, and the two things that decide its safety, macro expansion under concurrency and the serial fallback, are both unbuilt.
