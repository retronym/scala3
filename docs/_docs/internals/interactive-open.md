---
layout: doc-page
title: "Opening many files for several readers"
---

# Opening many files for several readers: design notes

## The problem

`InteractiveDriver.openAll` opens a set of files in one run for several readers to type (the throughput experiment of [interactive-multi-reader.md](interactive-multi-reader.md), and the X-ray's Throughput tab). The open is serial, a write action, and every reader waits for it. On the 490 files of `compiler/src/dotty/tools/dotc`, typing with eight readers sped up about 7× over one, but the wall clock only about 5×, because of the open.

`-Ddotc.openTiming` split it, before the work below (warm runs, 490 files):

| step | before | with parallel indexing (17acd06be4) |
|---|---|---|
| parse | 31–65 ms, already parallel | same |
| index (`enterSyms`) | 150–160 ms | about 47 ms |
| typecheck with every statement skipped | 200–285 ms | about 150 ms |
| sessions | 6–22 ms | same |
| open | about 360–400 ms | about 250 ms |

Parallel indexing also made the skipping typecheck cheaper: much of its time was loading packages from the classpath, which indexing now does first, side by side. What remained was mostly the skipping typecheck; the sections after Tried say where its time went and what was done about it.

## What the skipping typecheck is for

`openAll` reuses the batch pipeline: `run.compileSources` with the target past the end of every file, so `TyperPhase` types each file with every top-level statement skipped. `Targeting` always typed package clauses and imports (it now types only the package clauses, below). The pass leaves:

1. **the skeleton of each file** (`unit.tpdTree`): typed package clauses and imports, with a place for each skipped statement, into which a resume splices its typed statement;
2. **the context of each skipped statement** (`SkippedStat.ctx`), built by `typedStats` as it walks the file: the owner and the chain of import contexts that statement is typed in later;
3. **the diagnostics of the imports**, reported at open.

None of these needs every file before the first reader starts. Name resolution does not depend on the pass: completers use the import contexts the Namer built at index time, and each import symbol's completer types its own qualifier.

## Tried

**Typing the files' imports in parallel, on workers inside the write action** (`local/spike-lazy-headers-and-parallel-imports.patch`, `-Ddotc.parallelOpen`). It cuts the pass from about 150 ms to about 142 ms once indexing is parallel, which is not worth what it costs:

- The workers share the run's root typer state (a type variable instantiated twice): each needs a private one (`Sharing.privately`).
- A worker that closes a wait-for cycle is aborted with `CancelledCompilation` (`CompletionRoots.acquire`); the open has no restart, so each worker retries its file. A retry is not idempotent: typing takes the Namer's `SymOfTree` off the import trees (`retrieveSym`), so the retry must put them back.
- A file's worker and another worker's completions touch the same untyped trees (an import symbol's completer types the qualifier ahead on the tree `typedImport` then takes). Tree state is `@confined` to "the completer of the definition, then the one reader that claims its statement"; a worker typing a file holds no claim.
- At full scale (490 files, not 130), a member of an anonymous class (`Inlining.scala` 1697, `YCheckPositions.scala` 825) is skipped in a typing whose tree is not the one the splice later looks in: "spliced 0 of 1 typed statements". Not explained.

**Typing each file's header lazily, on the first reader that needs its session** (`-Ddotc.lazyHeaders`, same patch). The open stops after indexing (92 ms on 490 files). `sessionOf` has the first reader type the file's package clauses and imports, record its skipped statements and build the skeleton. Every file's diagnostics equal the reference with round-robin readers, but with readers taking the largest files first 127–136 files gain errors in every round: extension methods not found (`hasAnonymousChild is not a member of Symbol`), signatures of the classpath's compiled compiler where the source's should be (`missing argument for parameter ignored of method inferImplicit`), `Not found: arg`. Names resolve to the wrong definitions, depending on which file's header is typed first. Explained since: the import symbols, completed lazily by readers, close wait-for cycles between readers (the same errors, by the same order of assignment, under "Where the time went" below).

## Where the time went, and what was done

A CPU profile of the open (`async-profiler`, the harness's cells only, not its whole-set reference run, which also goes through `openAll`) put the skipping typecheck at 88% of the open, and 62% of that in `typedClassDef` directly under the top-level statement walk: top-level statements were being typed. `Targeting.skip` never skipped a synthetic definition (the rule is for a closure's `$anonfun` and a pattern's temporaries, which the statement after them is built from), and a file's top-level definitions are desugared into a synthetic `<src>$package` object. Six files of dotc have one, and `cc/CaptureOps.scala`, 6,363 typed trees of extension methods, was typed whole at every open and again at every request on it. A synthetic definition at the package level, the `$package` object or a case class's companion, is now skipped like any other; a target in one of its members resumes into it, where its own members are targeted (`CompletionSessionSuite.top-level-definitions-are-skipped-too`).

With the loaders warm (a second open on the same driver) that halved the open. With a fresh driver, the harness's measurement, it did not: what typing `CaptureOps` had forced, loading packages and completing the objects the file imports, was forced instead by typing the imports of other files. The serial cost of the open is the completions and loads that typing the files' imports forces, wherever they are triggered first, not the typing of the import trees.

So the imports are no longer typed in the open either (`Targeting.skipImport`, under `Reader.deferImports`, which only `openAll` sets): a package-level import is recorded as a skipped statement with the context it would be typed in, and the statements after it are typed in the import context the Namer built from the untyped import and its symbol, `ctx.importContext(imp, sym)`, which is what `typedStats` builds from the typed import too (the same `ImportInfo`, from the same symbol and selectors). The first request on a file claims its skipped imports along with the statement the target lands in (`Session.neededAt`): they are typed first, in their recorded contexts, and spliced into the package in source order, and a request that lands while another reader types them waits, as for any statement being typed. What the open leaves per file is the package clauses and a skipped statement per import and per top-level definition; what it does per file is `typedPackageDef` and the skip of each statement.

What moves, and where it lands:

- **The diagnostics of imports** are reported when the file's first request types them, as the single-file `runUpTo` reports them with its run; the harness compares each file's errors after it was read, and finds the same set.
- **The completions and loads** that the imports force are done by the readers, under the completion lock and the root claim, side by side: the same work they already did for the imports of every file but their own.
- **A cancelled typing of an import** poisons the session (an import is not a replaceable statement, `replacePoisoned`), and the next request starts a fresh run; the same as for a block statement today.
- **The typed tree of a file** holds no `Import` until the file's first request, and the providers build a path's context from the tree's imports: hence every request on the file claims them, wherever its target lands, not only one that lands in a skipped statement.

The imports' symbols are still completed in the open, serially: left to the readers, a reader completing one holds it while it types the qualifier, and another reader's completion of an object resolves that object's parents through the imports of its file, so eight readers on the largest files first closed wait-for cycles (`Cyclic reference involving module class Symbols$` on an import line, then names resolving wrongly in 130 files; round-robin never saw it, as with the lazy headers under Tried). Completed before any reader runs, an import symbol is never waited on; what its completion costs is the qualifier typed ahead, against packages the index loaded.

Measured on the harness on 490 files, eight readers (`-Dtp.loop=3 -Dtp.ks=8 -Dtp.policies=big,rr`), the harness now reusing one driver across cells as an IDE session does, so that the first open is cold (after a start) and the rest warm (the classpath loaded):

| step | before (eb17e08088), cold | after, cold | after, warm |
|---|---|---|---|
| parse | 34–49 ms, parallel | 57 ms | 36–60 ms |
| index | 51–54 ms, parallel | 59 ms | 15–25 ms |
| typecheck with every statement skipped | 171 ms | 47 ms | 38–64 ms |
| sessions | 6 ms | 27 ms | 26–31 ms |
| open | 262–280 ms | 174 ms | 95–154 ms |

Every cell: 0 files differ from the reference, no back-off, no cycle raised. What the skipping typecheck still does per file is `typedPackageDef` (the package object and the sibling package objects completed) and the import symbols' completions; what the sessions step does grew with the skipped statements it files per session, which was a filter per file over every file's statements and is now one grouping.

## Tried and backed out: completing the import symbols on workers

Between indexing and the serial pass, each file's import symbols completed on the indexing pool (`sym.ensureCompleted()` on a worker, every completion in a typer state of its own, as a reader's is). The pre-pass took 30–50 ms in parallel, and it was wrong: 28 or 4 errors at the open, alternating between runs, `Cyclic reference involving module class Types$` at `ast/tpd.scala:8` and then `value Tree is not a member of object tpd` in thirty files, 142–157 files differing from the reference, and the `spliced 0 of 1 typed statements` failure at `Inlining.scala` 1697. Two workers completing imports whose qualifiers need each other's objects close a wait-for cycle, and the reader that would close it gets a `CyclicReference` (§4.2 of the multi-reader notes), which the completion reports and recovers from with a degraded object. Readers do not meet these cycles in the throughput runs (`cycleAborts 0, cycles raised 0`) because their completions nest in the order their typing needs them; a pre-pass that starts every file's imports at once does not have that order. The lazy imports above get the parallelism from the readers' own requests instead.

## Iterating

From the worktree root, with `JH` a JDK 21 (`export JH=/path/to/jdk-21`).

Once:

- Build without the optimizer, so an edit recompiles in seconds: an untracked `.sbt` file at the root holding `LocalProject("scala3-compiler-bootstrapped") / Compile / compile / scalacOptions --= Seq("-opt", "-opt-inline:dotty.**")` (a separate `.sbt` file cannot name the backticked vals of `build.sbt`).
- A snapshot of the sources to type, so that edits do not move the input: `mkdir -p out/xray-sources && git archive HEAD compiler/src/dotty/tools/dotc | tar -x -C out/xray-sources`.
- The test classpath: `SBT_OPTS=-Xmx8g timeout 1200 sbt -batch -java-home $JH "export scala3-presentation-compiler/Test/fullClasspath" | grep '^/' | tail -1 > out/pc-test-classpath.txt`.

Each change:

1. Build: `SBT_OPTS=-Xmx8g timeout 1200 sbt -batch -java-home $JH "scala3-presentation-compiler/Test/compile"` (seconds for an edit in one file, a few minutes when much of the compiler recompiles).
2. Check on the 130 files of `transform/`, about 16 s: `timeout 300 $JH/bin/java -Xmx16g -Xss16m -Ddotc.openTiming=1 -Dtp.root=out/xray-sources/compiler/src/dotty/tools/dotc/transform -Dtp.loop=10 -Dtp.ks=1,8 -Dtp.policies=rr,big -Dtp.showErrors=3 -cp "$(cat out/pc-test-classpath.txt)" dotty.tools.pc.xray.Throughput > local/tp.log 2>&1`, then `grep -E '^\[tp\] loop|DIVERGENCE|FAILED' local/tp.log | grep -v '     at'`. It passes when every `loop` line says `0 files differ` and none says `FAILED` or `UNWOUND`; a `DIVERGENCE` line shows the first diagnostics that differ from the reference run.
3. Check on all 490 files, a few minutes: the same with `-Dtp.root=out/xray-sources/compiler/src/dotty/tools/dotc -Dtp.loop=3 -Dtp.ks=4,8`. Some faults show only at this scale (both of those under Tried).
4. The `[open]` lines in the same log (`-Ddotc.openTiming`) give the time of each step of the open.
5. To see it, the X-ray: `$JH/bin/java -Xms20g -Xmx20g -XX:+UseParallelGC -Xss16m -Ddotc.openTiming=1 -Dxray.port=8801 -cp "$(cat out/pc-test-classpath.txt)" dotty.tools.pc.xray.XrayServer`, then Replay, Run throughput at http://127.0.0.1:8801/; its header shows the commit it runs. One run of the demo is not a measurement (the same build gave 4.75× and 5.56×): measure with the harness.

What cost time:

- **Both orders of assignment.** Round-robin (`rr`) passed where largest-first (`big`), the X-ray's, found the lazy headers' wrong errors.
- **An experimental path behind a `-D` flag**, so that one build runs the old path and the new side by side.
- **Races that only a loaded machine showed.** The two-reader soaks (`CompletionSoakSuite`) passed twenty quiet runs on both sides of a failure seen under load. `-Ddotc.interactive.chaos=3` (`util.Chaos`: a random pause of up to 3 ms at a claim, an abandon, a root acquired, a completion unwound, one typing checkpoint in 64) reproduced it in one run of the suite (I99 in the multi-reader notes), and `-Ddotc.interactive.failOnCycle=1` fails a request at a cross-reader cycle instead of at the provider's catch. Both go on the forked test JVM: `sbt -batch 'set `scala3-presentation-compiler` / Test / javaOptions ++= Seq("-Ddotc.interactive.chaos=3", "-Ddotc.interactive.failOnCycle=1")' "scala3-presentation-compiler/testOnly dotty.tools.pc.tests.completion.CompletionSoakSuite"`; the suite takes 25 min under chaos against under 1 min without. `-XX:ActiveProcessorCount=2` is the other lever.
- **sbt.** A change to `*.sbt` or `project/`, or a `reload`, through `sbt --client` can leave the server waiting at its load-failed prompt for a client that is gone, and every later client then hangs without a word: use `sbt -batch` for those. Every sbt and harness command under `timeout`. When something hangs, `jcmd <pid> Thread.print` before a kill; a worktree's server is the one listening on the socket `project/target/active.json` names (`lsof -U | grep <hash>`).
