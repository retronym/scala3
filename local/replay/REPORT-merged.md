# Merged recorder: one id space, plugin over a TyperObserver

Written with LLM assistance (Claude), see `LLM_POLICY.md`. Branch `claude/replay-merged-221743` (local commits). Merges Track A (research plugin, phase walks, printed text) and Track B (in-tree typer hooks) into one recorder that writes schema v1 (`SCHEMA-v1.md`).

## Fork diff (upstream candidate)

Two commits on top of Track B's branch. Relative to `main` (`ae374e9583`), `compiler/src` changes by +148 −12 over 11 files: 99 lines are the new `typer/TyperObserver.scala`, 12 are the Name/NameKind content hashes from Track B, and the rest are hook sites and the copier fix.

- `6a1308b7e6` replaces Track B's concrete `Recorder` with a `TyperObserver` trait. It has no-op default methods: phase begin/end, typed/adapt/complete/implicit search/candidate enter+exit, report, and copied. Its companion has `inline` helpers, so a hook allocates no closure. The patch deletes `dotc/replay/` and `-Yreplay-record`.
- `9298f36556` changes `tpd.TimeTravellingTreeCopier.TypeApply` (`ast/tpd.scala:837`) to keep the tree when `fun`/`args` are unchanged and the recomputed type equals the old one. The commit first used `eq || =:=`; a follow-up commit narrows it to `eq || (== && =:=)` (see "testCompilation" below).

Hook sites. Each is a field read plus a null test.

| site | hook |
|---|---|
| `core/Contexts.scala:1134` | `ContextState.observer: TyperObserver \| Null` |
| `Run.scala:421`, `:425` | `phaseBegin` / `phaseEnd` around `phase.runOn` |
| `typer/Typer.scala:4157` | `typed` (around `adapt(typedUnadapted(..))`) |
| `typer/Typer.scala:4541` | `adapt` (around `adapt1`) |
| `typer/Namer.scala:940` | `complete` |
| `typer/Implicits.scala:1101` | `implicitSearch` (body moved to `inferImplicit0`) |
| `typer/Implicits.scala:1312` | `candidate` (around `typedImplicit`) |
| `reporting/Reporter.scala:241` | `report` |
| `ast/Trees.scala:1282`, `:1289` | `copied` in both `TreeCopier.finalize` overloads (which gain `using Context`) |

The observer goes in `typer/` because most of its events are typer events. The copier and phase hooks don't really belong there, so `core/` would also be a reasonable home.

`testCompilation` results and how each failure is attributed are in the "testCompilation" section below.

## Plugin

The plugin is in `local/replay/plugin/`, under the gitignored `local/`, and is committed with `git add -f`. Files: `src/ReplayPlugin.scala`, `src/Recorder.scala`, `src/MarkingPrinter.scala` (Track A's, unchanged), the scripts `build.sh`, `env.sh` and `record.sh`, the v1 validator and summary `analyze.py`, and `evshape.py` from Track B.

- **Installation.** `ResearchPlugin.init` runs once per Run, before any phase, and sets `ctx.base.observer = recorder`. Parser and typer events are therefore recorded into the same arena the `RecordPhase` walks use. A separate pre-parser phase was not needed. The last `RecordPhase` closes the file and uninstalls the observer. If the run stops early (errors, `-Ystop-after`), the file has been flushed at every phase end but has no `stats` line, and the observer stays set until the next `init`.
- **One arena.** A single `IdentityHashMap[Tree, id]` serves both. Interning is post-order over the subtree, so children have smaller ids whether a node was first seen by an event or by a walk. At hook time it reads fields only. Anything that could force a completion is deferred to the phase end and run with hooks suspended: `tree.symbol`, symbol names, `fullName`, type text, report messages, and edge endpoints (`topLevelClass`).
- **Ev ordering.** Events are buffered per phase and written at the phase's `PhaseEnd`, sorted by seq. I chose this over a separate End record. Definitions are written before the events, and there are no seq gaps: a no-op `Adapt` with nothing nested inside gives its seq back.
- **Per-phase maps.** Copier lineage (`copy → tree`) and typed/retyped lineage (`result → input`) are fresh `IdentityHashMap`s per phase group. The derive pass follows them back to the previous version. The arena itself stays global and strong.
- **Skipping.** A version is not printed (text, offsets or types) when it has no new nodes, no node's type object changed, and no reachable local symbol's (denotation, info, flags, owner, name) changed by `eq`.
- **Types.** `version.types` lists `[node, text]` when the printed type changed since the node was last listed. A node is re-printed if its type object changed, or for every node if the group contains a `DenotTransformer` or a symbol changed. Text is memoised per `Type` within a version.

## Outputs

`local/replay/out/` has one file per corpus file (`Closures`, `ForDesugar`, `Givens`, `Mixins`, `Patmat`.jsonl, each its own compile, which matches what the UI already loads) and one combined `util.jsonl` (all 39 `dotc/util/*.scala` files in one compile with `-Yexplicit-nulls`, because cross-file completion order matters there). All of them validate with `analyze.py` with 0 errors: references defined earlier, seq contiguous, parents earlier, `version.seq` is its group's `PhaseEnd`, `*SameAs` resolves, and offsets are nested and in range.

## Arena shrink from the TypeApply fix

Same recorder and default plan, compiler with and without `9298f36556` (`local/replay/classes-nofix`).

| file | arena nodes, no fix → fix | arena / typed tree | versions printed (of 85) | output KiB |
|---|---|---|---|---|
| Closures | 1504 → 826 (−45%) | 9.8× → 5.4× | 59 → 16 | 653 → 384 |
| ForDesugar | 1492 → 617 (−59%) | 10.4× → 4.3× | 61 → 16 | 572 → 293 |
| Givens | 3655 → 1644 (−55%) | 11.6× → 5.2× | 70 → 21 | 1503 → 765 |
| Mixins | 977 → 977 (0%) | 5.0× | 17 → 17 | 410 → 410 |
| Patmat | 2773 → 2463 (−11%) | 7.5× → 6.6× | 38 → 16 | 1359 → 1058 |

After the fix, the remaining arena growth comes from real lowering: in Givens, erasure adds 260 new nodes and mixin adds 256. Only 2–4% of derived nodes are still pure copies. The arena also now holds intermediate typer trees seen only by events, such as desugared input and speculative results, which Track A's walk never saw.

I have not checked whether the fix reduces allocation in ordinary compiles. That needs bench-lab.

## Event volumes

| | Closures | ForDesugar | Givens | Mixins | Patmat | util (5.1 kLoC) |
|---|---:|---:|---:|---:|---:|---:|
| Typed | 210 | 164 | 376 | 172 | 433 | 31519 |
| Retyped | 240 | 135 | 367 | 184 | 915 | 38694 |
| Complete | 27 | 20 | 54 | 25 | 50 | 3044 |
| Adapt | 12 | 12 | 19 | 9 | 14 | 2888 |
| ImplicitSearch / Candidate | 1/0 | 2/2 | 5/5 | 1/0 | 0/0 | 630/445 |
| Report | 0 | 0 | 1 | 0 | 0 | 0 |
| PhaseBegin+End | 170 | 170 | 170 | 170 | 170 | 170 |

On `util` the event counts match Track B exactly (Typed 31519, Retyped = Track B's erasure/inliner `Typed` 38694, Complete 3044, ImplicitSearch 630, Candidate 445). Its 2888 non-trivial `Adapt` match too. The file is 72 MB: version text and offsets are 45% (3060 versions, 724 printed), nodes 21%, ev 18% at 175 B/event, and derive 9%.

Derive on `util`, counted in nodes: rewrite exact 25655 / heuristic 10070, retype exact 15650, copy exact 4375 / heuristic 2912, split exact 863 / heuristic 21361, synth 14146, elide 15148.

Wall clock on `util`, cold, one JVM, JDK 21, machine not quiet: plain compile 3.7 s, full recording 19 s (about 5×), recording with `notext` 9.4 s. Printing per-version text and offsets is still the largest cost.

## Reproducibility

- Two fresh runs are byte-identical for each corpus file and for `util`, comparing everything after the `meta` line, which holds the output path.
- `REPLAY_PERTURB_IDHASH=1000` against the default gives byte-identical output for each corpus file and for `util`.
- On `util`, the event skeleton (`evshape.py`: seq, parent, kind, phase, unit, span) is identical with and without `notext,nosyms`. So printing versions and syminfo in the Record phases does not change which events are recorded. `stats.perturbations = 6`: these are hooks that fired while suspended, which Track B also saw.
- `.class`/`.tasty` output for the corpus is identical with and without the plugin (default plan).
- `-Ycheck:all` passes on all five corpus files, both with a plain fused compile and with the plugin in `fused` mode (recorder installed).

## Deviations from v1 as specified

- **`merge` is never produced.** I found no reliable signal for it.
- **`split` means one old node and several new nodes,** found either by the exact chain or by equal span. The span heuristic finds this often (21k nodes in util) because synthetic nodes inherit the span of the node they replace, so many heuristic splits are really "rewrite plus synthesised neighbours".
- **Copier-derived nodes that are not pure copies are `rewrite`.** That includes spine nodes whose descendants changed. There is no separate spine kind.
- **`textSameAs`/`offsetsSameAs` can name any earlier version of the unit.** That is the last printed one, not the immediately previous one.
- **`edge` records are aggregated per phase,** with `weight` and the first occurrence's `ev`. Totals need summing across records.
- **`sym` uses `firstPhase`/`firstSeq`** (where the recorder first saw it) instead of v0's `createdPhase`/`createdBy`.
- **Positional `ev.nodes` gained a second role for `ImplicitSearch`** (the chosen tree) and one for `Candidate` (the tree when accepted).
- **`Typed` vs `Retyped` is decided by `ctx.phase.isTyper`, not by the running group.** A completer that runs during a later phase still types at its creation phase, so its events stay `Typed`.
- **`version.types` replaces node `tpe` completely,** including the first listing. `type` records remain only for event payloads (`pt`, `tpe`, `ref`).

## Known gaps

- The arena is a strong identity map for the whole run.
- `LazyTree` children forced after a node was interned (TASTy-loaded bodies) would make the node's `children` stale. This was not seen on the corpus.
- Fused `mixin..capturedVars` is one version.
- No bench-lab numbers for the disabled hooks or for the TypeApply fix.

## testCompilation

There were three full runs, with sbt on JDK 21. Logs: `local/replay/testCompilation{,-2,-3}.log`.

**Run 1, with the TypeApply check `eq || =:=`: 2 of 22 test methods failed.**

1. `pickling` on `tests/run/i15302b.scala`. The TypeApply change caused this. `-Ytest-pickler` compares tree printouts before pickling and after unpickling. A later phase kept the TypeApply of `tup3.asInstanceOf[Int *: ((Int *: Int *: EmptyTuple) & (Int, Int))]` with its old type `(Int, Int, Int)`. Unpickling recomputes `Int *: (Int, Int)`. The two types are `=:=`, but they print differently, so the comparison failed. The compiler built without the change (`local/replay/classes-nofix`) passes.
2. `genericJavaSignatures` on `tests/generic-java-signatures/i24553.scala`. This branch did not cause it; the result depends on the JDK. The check file expects `public final native void java.lang.Object.wait(long)`, but that method is no longer `native` from JDK 19 on. I compiled the test once and ran it on two JVMs. Its output matches the check file on JDK 17.0.20, which is what CI uses (`.github/workflows/ci.yaml`, `java-version: 17`). It differs on JDK 21.0.12, the JVM this sbt server runs.

**Run 2, with the check narrowed to `eq || ==`: `i15302b` passed, but `tests/run/i1284.scala` now failed (`run` and `pickling`).** It was a `-Ycheck` type mismatch at `Array(A, B).toList`. The case: `wrapRefArray[A | B]`, where the old and recomputed `MethodType`s are structurally identical (same `toString`) and `==` holds, yet `=:=` is false under the checker. Keeping the tree in that case makes the checker fail later, on an enclosing `reflectiveSelectable…` application. I did not find out why `=:=` fails on two structurally equal method types there. Run 1 passed this test only because `=:=` happened to reject it.

**Final check: `(tree1.tpe eq tree.tpe) || tree1.tpe == tree.tpe && tree1.tpe =:= tree.tpe`.** Both `i15302b` (`-Ytest-pickler`) and `i1284` (`-Ycheck:all`) pass when compiled directly. The corpus arena and printed-version numbers above are unchanged. On `util` the arena is 133,798 nodes instead of 133,759: those 39 nodes are the `==`-but-not-`=:=` cases, which are now copied again.

Run 3, full `testCompilation` with the final check: 21 of 22 test methods pass and 87 of 88 Vulpix suites pass. The only failure is `i24553`, which depends on the JDK as explained above.
