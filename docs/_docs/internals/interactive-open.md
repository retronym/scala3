---
layout: doc-page
title: "Opening many files for several readers"
---

# Opening many files for several readers: design notes

## The problem

`InteractiveDriver.openAll` opens a set of files in one run for several readers to type (the throughput experiment of [interactive-multi-reader.md](interactive-multi-reader.md), and the X-ray's Throughput tab). The open is serial, a write action, and every reader waits for it. On the 490 files of `compiler/src/dotty/tools/dotc`, typing with eight readers speeds up about 7× over one, but the wall clock only about 5×, because of the open.

`-Ddotc.openTiming` splits it (warm runs, 490 files):

| step | before | with parallel indexing (17acd06be4) |
|---|---|---|
| parse | 31–65 ms, already parallel | same |
| index (`enterSyms`) | 150–160 ms | about 47 ms |
| typecheck with every statement skipped | 200–285 ms | about 150 ms |
| sessions | 6–22 ms | same |
| open | about 360–400 ms | about 250 ms |

Parallel indexing also made the skipping typecheck cheaper: much of its time was loading packages from the classpath, which indexing now does first, side by side. What remains is mostly the skipping typecheck.

## What the skipping typecheck is for

`openAll` reuses the batch pipeline: `run.compileSources` with the target past the end of every file, so `TyperPhase` types each file with every top-level statement skipped. `Targeting` always types package clauses and imports. The pass leaves:

1. **the skeleton of each file** (`unit.tpdTree`): typed package clauses and imports, with a place for each skipped statement, into which a resume splices its typed statement;
2. **the context of each skipped statement** (`SkippedStat.ctx`), built by `typedStats` as it walks the file: the owner and the chain of import contexts that statement is typed in later;
3. **the diagnostics of the imports**, reported at open.

None of these needs every file before the first reader starts. Name resolution does not depend on the pass: completers use the import contexts the Namer built at index time, and each import symbol's completer types its own qualifier.

## Tried

**Typing the files' imports in parallel, on workers inside the write action** (`local/spike-lazy-headers-and-parallel-imports.patch`, `-Ddotc.parallelOpen`). It cuts the pass from about 150 ms to about 142 ms once indexing is parallel, which is not worth what it costs:

- The workers share the run's root typer state (a type variable instantiated twice): each needs a private one (`Sharing.privately`).
- A worker that closes a wait-for cycle backs off with `CancelledCompilation` (`CompletionRoots.acquire`); the open has no restart, so each worker retries its file. A retry is not idempotent: typing takes the Namer's `SymOfTree` off the import trees (`retrieveSym`), so the retry must put them back.
- A file's worker and another worker's completions touch the same untyped trees (an import symbol's completer types the qualifier ahead on the tree `typedImport` then takes). Tree state is `@confined` to "the completer of the definition, then the one reader that claims its statement"; a worker typing a file holds no claim.
- At full scale (490 files, not 130), a member of an anonymous class (`Inlining.scala` 1697, `YCheckPositions.scala` 825) is skipped in a typing whose tree is not the one the splice later looks in: "spliced 0 of 1 typed statements". Not explained.

**Typing each file's header lazily, on the first reader that needs its session** (`-Ddotc.lazyHeaders`, same patch). The open stops after indexing (92 ms on 490 files). `sessionOf` has the first reader type the file's package clauses and imports, record its skipped statements and build the skeleton. Every file's diagnostics equal the reference with round-robin readers, but with readers taking the largest files first 127–136 files gain errors in every round: extension methods not found (`hasAnonymousChild is not a member of Symbol`), signatures of the classpath's compiled compiler where the source's should be (`missing argument for parameter ignored of method inferImplicit`), `Not found: arg`. Names resolve to the wrong definitions, depending on which file's header is typed first. Not explained; the leading suspect is `cleanup(t, errorOutCompleters = ...)`, which now runs per file when its header is typed, late, while other readers resolve against the file's symbols.

## Next: lazy import typing only

Keep the open's walk of every file, and so the skeleton and every skipped statement's context, serial and eager as now, but stop typing the imports in it:

- the skipped statements' contexts take the import contexts the Namer built at index time (`ctx.importContext(imp, sym)`), not ones built from a typed `Import`;
- the import trees stay untyped in the skeleton until a resume touches their file, which types them under the file's claim, or a pass types them for their diagnostics.

What it leaves open:

- **Diagnostics of imports** move from the open to the first typing of the file (as with a single-file `runUpTo` today).
- **Whether an `ImportInfo` from the Namer is the one typing expects**: it is built from the import symbol, and the typer's comes from the typed tree; they should agree on what they import, but selectors are checked only by `typedImport`.
- **What else the skipping typecheck does per file** that readers rely on (`cleanup`, `topLevelTrees` for `openedTrees`), which must stay in the open.

## Testing

`presentation-compiler/test/dotty/tools/pc/xray/Throughput.scala` as a main on the test classpath, in its loop mode, is the test: `-Dtp.root=<sources> -Dtp.loop=N -Dtp.ks=1,8 -Dtp.policies=rr,big`, with `-Dtp.showErrors=3` to print what differs. On the 130 files of `transform/` a loop takes about 16 s. The largest-first assignment (`big`), which the X-ray's Throughput tab uses, found what round-robin missed.
