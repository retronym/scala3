---
layout: doc-page
title: "Signatures in parallel: design notes"
---

# Signatures in parallel: design notes

Status: design, 29 October 2026. Builds on the `-Yparallel-typer` spike (branch `claude/poc-batch-compiler-tasty-b826e7`, on [retronym/scala3#84](https://github.com/retronym/scala3/pull/84)) and its multi-reader base ([interactive-multi-reader.md](interactive-multi-reader.md)). The toy is in `tools/sig-toy/`.

## Summary for a busy reader

The spike types bodies in parallel only after it has completed every member's signature on one thread. On the compiler's own 618 sources that serial pass is 3.9 s of a 6.2 s typer (class headers 1.0 s, member signatures 2.9 s), against 2.1 s for the bodies on 18 threads. It is the limit on what the parallel typer can gain. It is serial because signatures completed on several threads met in wait-for cycles: false `CyclicReference`s between Namer completions (I68) and aborted readers that left a classpath completion unwound for the rest of the run (I74).

A toy of signature completion, run against random programs under a deterministic scheduler, says the cycles are a symptom. The cause is that dotc's lazy completion is order dependent. A completion that re-enters one in progress on its own stack reads intermediate state (a `TempClassInfo`'s missing parents, a member's `Touched` flag), and what it reads depends on where the cycle was entered. The toy's findings:

- **Without such an "unstable read" the result is unique.** Every run, on any number of workers and any schedule, computes the same result, and it is the serial one (no counterexample in thousands of runs).
- **A class's decls must be a fact of their own.** dotc completes a whole class header to read its decls. That nesting turns a cycle through decls, which is harmless, into an unstable read, and makes stability depend on the schedule. With decls as a stage of their own, whether a program needs an unstable read is a property of the program, the same on one worker as on sixteen. Programs without genuine cycles then meet no wait-for cycle at all, and the toy shows 3.4–3.8× on 4 workers and 7–12× on 16.
- **What remains is detectable.** Cycles through parent constructor arguments still need unstable reads whatever the staging, and their result does depend on the schedule. Because stability is a property of the program, a parallel run that meets one can be abandoned and the run redone serially, deterministically.

Measured in dotc, with a probe of every read of intermediate completion state (parents of a `TempClassInfo`, `CyclicReference`s made, re-entries, `isCompleting` peeks), on the compiler's 618 sources and the standard library's 722, to the end of the typer: almost every event is a completion looking at itself, or a check's own structural cycle, both the same in every order. What is left are 34 `derivesFrom` answers given while a class's parents were unknown: 2 in the compiler, inside `checkNonCyclic`'s pruning, and 32 in the library, choosing a type parameter's default lower bound under capture checking. Asked again once the classes are complete, all 34 answers stand. Nothing came from implicit search.

The design:

1. **Stages.** A class's decls and type parameters are entered as a stage of their own, eagerly in the serial index, before any typing. Readers that need only decls read them without completing the header.
2. **Signatures in parallel**, a unit per task, with the claims the spike already has, after every class header completed serially (done, §5.7): headers on several threads meet in wait-for cycles that are not cycles serially.
3. **Validate, then fall back.** At the few sites where a completion reads intermediate state, record the question and the answer (for `derivesFrom`: the class, the base, the answer). Once signatures are complete, ask each again: if every answer stands, the run was as good as a stable one. Otherwise abandon the run and compile serially. On both corpora nothing would fall back.
4. **`checkNonCyclic`'s pruning made order independent** (done): a prefix whose class has a base in progress is taken as interesting instead of asking `derivesFrom` of it. Its two reads on the compiler are gone, and its output is unchanged.
5. **Determinism repairs**: fresh names drawn per definition, not per unit; `Child` annotations stored without losing a concurrent one (done, §5.4); names hashed by content (done in the spike).

No claim lending (letting a cycle's closer borrow the claim it waits for, as a serial nesting would): the toy shows it buys nothing once decls are staged.

## 1. Where the time goes

`-Yparallel-typer` on the compiler's sources, cold JVM, 18 threads (one run each; `-Ddotc.parallelTyper.classesFirst` splits the serial pass):

| step | time |
|---|---|
| parse (parallel) | 0.5 s |
| index (serial) | 0.4 s |
| class headers (serial) | 1.0 s |
| member signatures (serial) | 2.9 s |
| bodies (parallel) | 2.1 s (6.2 s on one thread) |

The classpath share of the serial pass is not measured: `-verbose`'s load timings nest and interleave with the markers.

## 2. Why the spike made signatures serial

With signatures completed lazily by the body-typing readers, the first runs failed in three ways, differently from run to run:

- `Cyclic reference involving module class tpd$` on `import tpd.*` in `Symbols.scala` and `Types.scala`: a wait-for cycle between Namer completions, reported as a cycle (I68).
- A reader aborted as the victim of a wait-for cycle inside the TASTy completion of `MapOps`, which then refuses to complete for the rest of the run (`UnwoundCompletion`). Thirteen further units aborted, then the run hung.
- A unit whose reader was aborted cannot be typed again in the same run: typing consumes what the Namer attached to the untyped trees (an import's symbol).

Interactive mode survives all three by retrying on a fresh run. Batch has no fresh run to retry on, short of compiling everything again.

## 3. The toy

`tools/sig-toy/Toy.scala` keeps what matters from dotc's completion and drops the rest:

- A class completes in stages: its decls are entered (the temporary info), then its parents are typed, and a parent can have a constructor argument `extends P(O.m)` that needs a member's signature. A member's signature is declared, or inferred from another member's.
- Re-entry on one thread: a class in progress reads as its temporary info; a member in progress is a cyclic reference (an error, and an error type).
- A claim per completion root, some coarse (several classes per root, as a TASTy file), waits, and wait-for cycle detection.
- The threads are real, but a scheduler runs one at a time, the one with the earliest virtual clock (ties by a seeded random), so a run is reproducible from its seed and its makespan is the largest clock.
- Strategies on a wait-for cycle: abort (the PoC's), or lend (the waiter that closes the cycle borrows the claim, as a serial nesting would). Option `staged`: a class's decls are a fact with a claim of their own. Option `lookupNeedsParents`: a member lookup needs its owner's parents, as `findMember` does for inherited members.
- The specification is one worker completing, unit by unit, each class's header and then its members' signatures: the spike's serial order.

An **unstable read** is one whose answer depends on the order: the bases of a class in progress, a lookup in a class whose parents are in progress, or a member in progress (a cycle). The program families: mostly declared signatures; mostly inferred; inferred with coarse roots; inferred with genuine cycles between members. Run with `scala-cli run tools/sig-toy --main-class Canon -- 100 [staged] [lookup]`, `--main-class Staged`, `--main-class Main`.

### 3.1 What it showed

**Serial completion is order dependent, but only through unstable reads.** Compiling the units in reverse order changes the result in 12–59% of random programs, and in none of the programs where neither order made an unstable read.

**Without an unstable read the result is unique.** Over 100 programs per family × 24 schedules (2, 4, 8, 16 workers × 6 seeds), every program with at least one run free of unstable reads had exactly one result among those runs, equal to the serial one when serial was also free of them.

**Unstaged, stability depends on the schedule.** With dotc's completion as it is (reading decls completes the whole header), 9–30% of programs were stable in some schedules and not others. A thread that needs a class's decls nests the class's whole header completion, which reads the bases of the class below it on the stack: a cycle through decls only, which is harmless, becomes an unstable read. Two threads with lending avoid it (one waits for the other's final bases), so the parallel run is *less* order dependent than the serial one, but not reliably.

**Staged, stability is a property of the program.** With decls as their own fact:

| family | stable in every schedule | in none | in some | serial = parallel |
|---|---|---|---|---|
| mostly declared | 100 | 0 | 0 | 100 |
| mostly inferred | 100 | 0 | 0 | 100 |
| inferred, coarse roots | 100 | 0 | 0 | 100 |
| genuine member cycles | 34 | 66 | 0 | 34 of 34 stable |

and no wait-for cycle at all in the first three: abort never fired (500 runs each, 4 and 16 workers), so lending and abort give the same results there. Speedup over one worker, by virtual makespan: 3.4–3.8× on 4 workers, 7.0–7.8× on 16, 12.3× on 16 for larger programs (160 classes).

**Lookups that need parents leave unstable cycles that staging cannot remove.** With `lookupNeedsParents`, a cycle through parent constructor arguments (C's parents need `O.x`, the lookup in O needs O's parents, which need `C.y`, whose lookup needs C's parents) needs a partial lookup whatever the staging: 30% of the mostly-declared programs, 81% of the inferred ones (the generator gives a parent an argument 10–30% of the time, from anywhere, so this is surely far above real code). Their results vary by schedule in about two thirds of them. But stability stayed a property of the program in every family (0 "in some"), so a parallel run that meets an unstable read can abandon itself and the serial result is reproduced.

### 3.2 What the toy does not model

Implicit search, which catches `CyclicReference` and drops the candidate (an order-dependent outcome that is not an error); exports, whose forwarders are entered by typing a path; overloading; inline and macros; suspension; the cost of anything (work units are made up). Member lookup in the toy is by own decls only unless `lookupNeedsParents`.

## 4. Unstable reads in dotc

The probe `-Ddotc.unstableReads` (`core.UnstableReads`; `=stacks` prints a stack for each new site on a member) records four kinds of event, each keyed by the symbol, whether it is local (reached only from its own unit, so the same in any parallel schedule), whether it happened under `checkNonCyclic` (`Mode.CheckCyclic`), the first compiler frame outside `core`, and, for reads and peeks, where the symbol is among the thread's completions in progress: the innermost (`self`, a completion looking at itself, the same in every order), an enclosing one (`outer`, a re-entry, whose answer depends on where the cycle was entered), or neither.

- `parents`: a class's parents read while its info is a `TempClassInfo` (`ClassDenotation.parentTypes`, through which `baseData`, `derivesFrom` and inherited lookup go). dotc documents the hazard: `derivesFrom` "may return a false negative when `this.info.isInstanceOf[TempClassInfo]`".
- `cycle`: a `CyclicReference` made, with the frame that threw it. In a valid program each is caught and handled: a check skipped, a `LazyRef`, an implicit candidate dropped.
- `reentry`: a completion entered again on its own thread (the cycle `lockAndComplete0` throws for).
- `peek`: `isCompleting` answering true, with its caller.

At the end of the typer, every `derivesFrom` answered from a class with an unknown base (`TempClassInfo` among its base classes) is asked again.

Serially, to the end of the typer:

| event | compiler (618 files) | library (722 files) | the same in every order? |
|---|---|---|---|
| `peek/self`: Namer's inherited-type lookup (`Namer.scala:2507`) finding the member being completed | 10,316 | 3,048 | yes |
| `cycle` in `CheckNonCyclicMap.apply:373`: the check's own `locked` set | 99 | 93 | yes: structural |
| `reentry/self` under `checkNonCyclic`, each also a `cycle` thrown by `lockAndComplete0`: a checked bound reaching the symbol being completed (an F-bound, so a `LazyRef`) | 14 | 8 | yes: the innermost completion |
| `peek/outer`: a local symbol (compiler); an `<import>` symbol, `Typer.scala:594` (library) | 1 | 1 | yes: reached only from its own unit |
| `parents/self`: local classes in `cc/Setup.scala` | 2 | 0 | yes |
| `parents/outer` on members | 2 | 32 | no |
| `derivesFrom` answers that changed once complete | 0 of 2 | 0 of 32 | |

The `parents/outer` reads:

- Compiler: `object Types`, in `Checking.checkNonCyclic` for a type alias in `Types`, asked while a parent type of a class nested in `Types` is typed (`ClassCompleter.typedParentType` → `preCheckKinds` → `dealias` → the alias's completion → `CheckNonCyclicMap.isInteresting` → `derivesFrom`). The answer decides whether to explore a prefix for a cycle. Gone after §5.6's rework.
- Library: `Typer.typedTypeBoundsTree`, which under capture checking asks `hi.tpe.derivesFromCapSet` to choose a type parameter's default lower bound, for bounds that are collection traits in progress (`MapOps`, `SortedSetOps`, `LinearSeq`, …), reached from `TypeDefCompleter.typeSig` of their own type members (`CC`). No collection trait derives from `CapSet`, so the answer is false whether the parents are known or not.

No `CyclicReference` was caught in implicit search, in either corpus.

## 5. Design

### 5.1 Decls as a stage

`Namer.ClassCompleter.completeInCreationContext` does two things in one completion: it indexes the class body (enters the members, the type parameters) and sets a `TempClassInfo`, then types the parents and the self type and finalizes. Split it: the indexing half runs for every source class in the serial index step, so every class's decls and type parameters exist before any typing, as top-level symbols already do. The typing half stays the class's completion.

Readers that need only decls must then not complete the class. dotc has the precedent: `ClassDenotation.typeParams` asks the completer (`completerTypeParams`) instead of completing, and `unforcedDecls` reads decls without completing. Member lookup is the hard part: `findMember` searches inherited members too, so it needs the parents, as the toy's `lookupNeedsParents` does. What staging removes is the nesting where a read of decls alone completed a header; what is left is the parents a lookup needs, which §5.3 handles.

What cannot move to the index: export forwarders, which are entered by typing the export's path, and anything else entered by typing. They stay in the completion, and are the next candidates for a stage of their own if the probe finds them on cycles.

### 5.2 Signatures in parallel

After the serial index (now with class bodies indexed), complete signatures on the pool, a unit per task: each class's header, then its members' signatures, under the claims the spike already uses (a claim per completion root). Then bodies, as now. Signatures and bodies can be one task per unit, or two passes with a barrier between them. The unstaged toy prefers one: there a barrier between headers and members cost 10–15% of makespan, for nothing.

### 5.3 Validate, then fall back

Every site that reads intermediate state of a completion records what it asked and what it was told. For `derivesFrom` from a class with an unknown base, the class, the base and the answer: §4's probe does exactly this. For a `CyclicReference` caught and handled, or an `isCompleting` peek that decides something, the symbol and the outcome. Events on local symbols, on the innermost completion (`self`), and a check's structural cycles are the same in every order and are not recorded.

Once all signatures are complete, every recorded question is asked again. If every answer stands, the run computed what a run without unstable reads would have, which the toy says is unique. If one does not, the run is abandoned and the compilation redone from the start on one thread (a fresh base, the spike's code path without the flag). Nothing has been written by then: the typer runs before the pickler, the backend and sbt's callbacks. A wait-for cycle that `CompletionRoots` would abort is a fallback too.

Plain detection (fall back on any unstable read) would have fallen back on both corpora: the compiler for `checkNonCyclic`'s two reads (gone since §5.6), the library for its 32. Validation keeps both in parallel.

A recorded answer may already have been built on before it is validated (a lower bound chosen, a prefix not explored). That is fine: if it stands, what was built on it is what a stable run builds; if it does not, the whole run is thrown away.

### 5.4 Determinism repairs

The spike's bodies are deterministic because each unit's fresh names, symbols and `Child` annotations are made on that unit's thread. Signatures in parallel make them on whichever thread completes them:

- **Fresh names.** `ctx.compilationUnit.freshNames` is a counter per unit; a completion of unit A's member on another thread draws from it in an order that depends on the schedule. Draw from a counter per definition being completed (its owner chain), so the order between definitions does not matter. This changes names in the serial compiler too, reproducibly.
- **`Child` annotations** (done). `Namer.addChild` inserts a child into the parent's annotations by position (sealed children share the parent's file, so the order is total) with a read-modify-write that two threads can lose; it did, once in four runs, for `enum SourceVersion`. With parallel readers the new list is now computed outside any lock (it may evaluate a `Child` annotation, which can wait on a claim) and stored under the parent's monitor only if no other reader stored one meanwhile, else computed again. Writers of the parent's annotations other than `addChild` do not take the monitor; a child is registered after it has typed its parents, which completes the parent first, so none was seen racing with it.
- **Hashes.** Names hash by content (done in the spike). Symbols hash by id, and ids are drawn from one counter by every thread; hashed collections of symbols iterated into output are an audit item, and the spike's byte-identical runs say none matter for bodies.

### 5.5 Not taken: lending

The toy's lending (a waiter that closes a wait-for cycle borrows the claim it waits for, reading the owner's intermediate state as a serial nesting would, while the owner is blocked) is sound in the sense that the run corresponds to a serial nesting, and it never deadlocks. But it does not make the result order independent; it picks a nesting by timing. Once decls are staged, the toy meets no wait-for cycle in programs without genuine cycles, so lending has nothing to do, and the detector of §5.3 must catch what remains anyway.

### 5.6 `checkNonCyclic`

`Checking.checkNonCyclic` runs inside a type member's completion (`TypeDefCompleter.typeSig`) and the unpicklers, and has two jobs. It reports illegal cycles, and it rewrites the info it is given: its result becomes `sym.info`, with a `LazyRef` wherever an F-bounded cycle is legal, and an error type where a cycle is illegal. Both are what make the published info safe to traverse, so neither can move out of the completion: an alias left cyclic until a later pass (`type A = B; type B = A`) would make every `dealias` before that pass loop. Which member of an illegal cycle gets the error depends on where the cycle was entered, but only erroneous programs have one.

What depended on order in valid programs was the pruning. `isInteresting` decides whether a reference's prefix might reach `sym`, and for a named prefix asks `!sym.is(Private) && prefix.derivesFrom(sym.owner)`, then a test that is fixed once symbols are entered (a static owner defined in this run, or else the prefix's own prefix). `derivesFrom` from a class whose parents are in progress answers from the ones known so far. On the compiler's sources it did so twice, for `object Types`.

The rework (`CheckNonCyclicMap.isInteresting`) changes only that answer. If the prefix's class, or a base class of it, has parents not known yet (`ClassDenotation.hasUnknownBase`: a `TempClassInfo` among the infos already there, read without completing anything), the prefix is interesting: it may derive from `sym.owner` once they are known. Otherwise `derivesFrom` is asked as before, and forces what it forced before. With parallel readers, another reader's completion in progress is not a `TempClassInfo` to this one (it reads as a lazy type), so `hasUnknownBase` answers no and `derivesFrom` waits for it.

A first version also put the stable test before `derivesFrom`, which is equivalent as a boolean. It moved the error of `tests/neg/i8984.scala` (an illegal cycle between two opaque types): `derivesFrom` forces completions, and in an erroneous program what is forced first decides where a cycle is found. The version kept leaves the order of evaluation alone.

Measured, serially, against the old pruning on the same sources:

- the compiler's 618 sources to bytecode: byte-identical output but for `Checking.scala`'s own classes, whose source changed; the same 36 warnings. The library's 722 sources to TASTy: byte-identical;
- the probe: the two `parents/outer` reads under `checkNonCyclic` are gone (0 `derivesFrom` answers from unknown bases in the compiler, from 2); every other count is the same (70 member and 60 local cycles over the whole compile, 7 and 8 re-entries);
- `testCompilation` passes. `tests/neg/i8984.scala` failed before this change on this branch (4 errors where 5 were expected, also with the names-hash commit reverted), and its expectations are updated to the errors given now, on lines 4, 6, 8 and 9.

What it leaves: re-entry into an enclosing completion while exploring (`checkInfo(tp.info)` forcing an alias whose completion encloses this one) still turns into a `LazyRef`, an error, or no change, by where the cycle was entered. The probe found none on either corpus; it stays a recorded event for §5.3.

### 5.7 Class headers first

Signatures a unit per task, on 4 threads and more, gave thousands of errors on the compiler's sources, the same set each run; on 1 or 2 threads, none. The cause was one wait-for cycle between two class headers that is not a cycle serially:

- `#35` completes `object tpd`'s header, through `tpd.scala`'s imports (`import Symbols.*, …`), and waits for `Symbols$`.
- `#39` completes `Symbols$`'s header, through `Symbols.scala`'s `import tpd.*`, and waits for `tpd$`.

On one thread, `Symbols$`'s completion nests inside `tpd$`'s, and the wildcard import's lookup in `tpd` reads `tpd$`'s decls from its `TempClassInfo` (`ownView`): no re-entry. On two, the second reader meets an `InProgress` it must wait for. Both readers are inside Namer completions, which may not abort (I74), so `CompletionRoots.acquire` gives the single-reader answer, a `CyclicReference` (`SymDenotation.cyclicWait`). The import fails, so `tpd`'s parents (`Trees.Instance[Types.Type]`, `TypedTreeInfo`) do not resolve and `tpd` has no inherited members; the same in `Types.scala`. Everything else followed: `no pattern match extractor named closureDef / ValDefs / Template`, extension methods not found, values typed `Nothing`, and same-thread re-entries of class-level pattern temporaries (`$N$`), whose binders, with the extractor gone, resolved to the class's members `$N$._1`. Rendering those errors ran import suggestions inside the failing completion, which completed much of the package tree and multiplied the errors (9,252 at 8 threads; 1,576 with `-Ximport-suggestion-timeout 0`).

This is the toy's finding in dotc (§3.1): a class's header reads other classes' decls, and a cycle through decls alone is harmless on one stack and a wait-for cycle on two. I68's assumption, that a wait-for cycle between Namer completions is a dependency cycle, does not hold for class headers, whose partial state nested work may read.

What is done (`TyperPhase.typeCheckInParallel`): with `-Ddotc.parallelTyper.parallelSignatures`, every source class header is completed serially before the member signatures run a unit per task. On the compiler's 618 sources, cold single runs, to bytecode: 36 warnings, as serially; headers 1.0–1.2 s serially, member signatures 1.0–1.1 s on 18 threads (3.5 s on one), bodies 3.1–3.2 s (9.7 s on one); four runs on 18 threads byte-identical to one thread in the same order (headers, then signatures, then bodies), in TASTy and in classfiles.

What is not: a member signature can still meet a wait-for cycle between Namer completions, and `cyclicWait` still answers it with a `CyclicReference` that a serial run would not give, a wrong result rather than a retry. None occurred with headers first. The fixes are §5.1's stages (a header that reads another class's decls should not wait for that class's header) or letting the waiter read the owner's decls, as a serial nesting does (§5.5's lending, restricted to decls); either is the way to take the serial header pass away.

Also: the batch reporter renders a message as it is reported (`ParallelBatchReporter.report`), so a reader holding claims runs import suggestions, which complete symbols. Rendering should not happen under a completion.

### 5.8 Shared structures under parallel readers

Measured with the timeline (`ParallelBatch.Timeline`, the x-ray's Batch tab), which records per span the thread's CPU time and its time blocked on monitors and parked, and with JFR's contended monitor entries.

- **Names.** The name table is a `ConcurrentHashMap` (#84), and parse never blocks on a monitor: not a bottleneck.
- **Type interners** (`Uniques`). #84's concurrent sets find a hit without a lock but take the set's monitor on a miss, which is every new type: 4.8 s blocked over three runs of the compiler's sources, most of it in bodies. They are now striped by default on a concurrent base: 64 sets, one per stripe of the hash (the high bits of a multiplicative mix, independent of the bucket index), each with its own monitor; a type is interned in exactly one, so interning stays unique. Blocked time in bodies 0.93–1.07 s to 0.32–0.38 s a run; output byte-identical. `-Ddotc.uniques.stripes=<n>`, 1 for one set.
- **Classpath completions.** A claim per classpath file deadlocked: `collection.SortedSet` and `collection.SortedSetFactoryDefaults` refer to each other, one nests in the other on one stack, and two readers entering from either end each held one file's claim and waited for the other's, inside Namer completions where no reader aborts. Every classpath completion (loader, TASTy unpickler and its lazy reads, a classpath module's completer) now takes one reentrant claim, `SymbolLoaders.ClasspathRoot`: a classpath completion never waits for a source definition's, so it cannot close a cycle with the Namer's roots. Cost: classpath loading is serial, which matters cold (the cold run took 10.3 s, as with a claim per file). `-Ddotc.parallelTyper.classpathRoots=file` for a claim per file.
- **Left:** parallel index's lock in `Namer.createPackageSymbol` (2.5 s blocked over three runs), and `SyncEqHashMap` (0.27 s).

## 6. Plan

0. **Toy** (done): `tools/sig-toy/`, the findings of §3.
1. **Probe, parents** (done): `-Ddotc.unstableReads`, §4.
2. **Probe, the rest** (done for the compiler and the library): re-entries, `CyclicReference`s made and where, `isCompleting` peeks, and `derivesFrom` answers asked again at the end of the typer (§4). Left: export forwarders, `LazyRef`s in progress, and a slice of the community build.
2a. **`checkNonCyclic`** (done): the pruning treats a prefix with a base in progress as interesting instead of asking `derivesFrom` (§5.6). Reporting stays in the completion: the error type is what keeps an illegal cycle from looping.
3. **Stage decls**: split `ClassCompleter`, index class bodies in the serial index step; the probe's counts before and after; `testCompilation` serially, since this changes the serial compiler.
4. **Parallel signatures** (done, with class headers serial first, §5.7): in `TyperPhase.typeCheckInParallel`; `-Ddotc.parallelTyper.threads` for the pool's size, `-Ddotc.parallelTyper.oneThread` for the same order on one thread. Left: the detector and whole-run fallback (§5.3), and the headers in parallel (§5.1, §5.7).
5. **Determinism repairs**: fresh names per definition; `addChild` (done, §5.4); the spike's harness (several parallel runs, byte-for-byte) on the compiler and the library.
6. **Measure**: typer time on the compiler and the library, paired with `-Ddotc.parallelTyper.oneThread`, and the fallback rate.

## 7. Prior art

- Kotlin's K2 Analysis API resolves each declaration lazily to a phase (`RAW_FIR`, `SUPER_TYPES`, `TYPES`, `IMPLICIT_TYPES_BODY_RESOLVE`, …) under a lock per declaration: what §5.1's stages are a first step towards.
- rustc's parallel front end runs queries on several threads; a deadlock handler finds query cycles among blocked threads and reports a cycle error. Its queries are pure, so a cycle is a real one; dotc's completions are not, which is why §5.3 detects reads, not just cycles.
- GHC's `--make` compiles modules in parallel along an import graph it knows before typing; Scala has no such graph, since a file's dependencies are found by typing it.
- Scala 2's `-Youtline` (for build pipelining) types signatures without bodies, except where a signature is inferred.

## 8. Open questions

- How common are unstable reads beyond the compiler and the library, especially `CyclicReference`s caught in implicit search (none in either)? If answers that change are common, whole-run fallback is not viable, and the sites must be staged away or the reads made to wait.
- Validation asks `derivesFrom` again; the other recorded kinds (a caught `CyclicReference`, a peek that decides) need their own way to be asked again, or to fall back when they occur.
- Can member lookup in a class whose parents are in progress wait for them instead of reading its own decls, when the waiter is another thread? The toy says the cycles that need it are rare in programs without genuine cycles; dotc's sources need measuring.
- Is the order of the spike (all signatures, then bodies) worth keeping as the serial compiler's order, so that serial and parallel agree byte for byte? Today they differ in 75 TASTy files, by fresh-name numbers and shared trees, deterministically.
