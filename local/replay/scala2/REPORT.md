# Greffier for Scala 2.13: report

Written with LLM assistance (Claude), see `LLM_POLICY.md`.

The compile server can now record a scalac 2.13.x compile in the v1 schema, and the existing viewer opens it unchanged. Design and rationale: `DESIGN.md`. Pieces:

- `plugin/src/`: the nsc recorder (`Recorder.scala`: arena, versions, events, derive; `JvmRecorder.scala`, `JvmText.scala`: a Scala 2 port of the Scala 3 JVM recorder; `ReplayPlugin.scala`: the plugin and the bridge from the agent's `Object`-typed interface).
- `agent/src/`: a separate Byte Buddy agent for nsc (`replay.nsc.agent`, API `replay.nsc.api`), a reduced copy of `../agent` (advice sites only, the same join-point table format). `../agent` is untouched.
- `build.sh <2.13.x> [out]` builds both against one scala-compiler; `run1.sh` records one compile; `identity.sh` compares class files with and without the recorder.
- `corpus/`: 2.13 equivalents of `../corpus` (`Implicits.scala` stands for `Givens.scala`).
- `../server`: version kind `scala2` (`versions.py setup 2.13.x`), `scala.tools.nsc.Main` command line, nsc diagnostics, the latest 2.13.x in `GET /api/versions`.

## Hook table (2.13.18, 36/36 found; 2.13.12 and 2.13.16 also 36/36)

| group | site | event / record |
|---|---|---|
| core | `NoOpProfiler$.beforePhase`, `afterPhase`; same on `RealProfiler` | `PhaseBegin`, `PhaseEnd`, then a tree version per unit |
| core | `Global$Run.compileUnitsInternal` (exit) | close: JVM records, `stats` |
| core | `Typers$Typer.typed(Tree, int, Type)` | `Typed` / `Retyped` (past typer), `resolves` edges |
| core | `Typers$Typer.adapt(Tree, int, Type, Tree)` | `Adapt` |
| core | `Namers$LockingTypeCompleter.complete$` | `Complete`, `forces` edges |
| core | `Implicits$ImplicitSearch.bestImplicit()` | `ImplicitSearch`, `implicit` edges |
| core | `ImplicitSearch.typedImplicit(ImplicitInfo, Z, Z)` (mangled) | `Candidate` |
| core | `scala.reflect.internal.Reporter.filteredInfo` | `Report` |
| buffered | `Contexts$BufferingReporter.issue` | `Report` with `buffered:true` |
| core | `Trees$Tree.copyAttrs(Tree)` | copier lineage (`derive … exact`) |
| jvm | `CodeGen.genClass`, `CodeGen.genMirrorClass` | ClassNode at genBCode (main, mirror) |
| jvm | `PlainSkelBuilder.genDefDef` + `initJMethod` (reads `mnode`) | MethodNode ← DefDef |
| jvm | `PlainBodyBuilder.genStat`, `genLoadTo` | InsnRange ← tree node |
| jvm | `LocalOpt.methodOptimizations(MethodNode, String)`, `removalRound$2`, 10 pass methods, `compactLocalVariables`, `removeUnusedLocalVariableNodes`, `removeEmptyLineNumbers` | `jvm:r<k>:<pass>`, `jvm:localVariables`, `jvm:lineNumbers` |
| jvm | `Inliner.inlineCallsite` | `jvm:inliner`, `inlined-from` derives |
| jvm | `PostProcessor.serializeClass` | `jvm:classfile` |

`traceIfChanged(pass)` exists in nsc's source as in dotc's, but the 2.13 compiler jar is built with the optimiser, which inlined every call to it (the method is dead code in the jar). So the agent hooks the pass methods themselves; the 3.10 release jar has the same problem, which the Scala 3 agent solves by rewriting the inlined sites.

## Verification

- All six corpus files compiled through `POST /api/compile` (2.13.18), with and without `-opt:local -opt:inline:<sources>`: 12 recordings, `analyze.py` reports 0 errors on each. An erroneous file stops after typer with the stream written and both errors parsed (`file`, `line`, `column`, message without the source echo).
- Byte identity: `identity.sh` compiles each corpus file with plain scalac and with the agent and plugin; class files are identical for all six, without options and with `-opt:local -opt:inline:**` (and with `-opt:inline:**` alone).
- Reproducibility: recording the same file twice gives identical streams after the `meta` line (Implicits, Patmat, Closures, with `-opt`). nsc's `Name.hashCode` is the name table index, so nothing like dotc's Name hash join points is needed.
- Offsets: `-P:replay-record:check` (each slice against the node's own `-Xprint` text): 9013 slices on Patmat, 0 failures.
- Implicit search (Implicits.scala): 11 `ImplicitSearch` events, 9 `Candidate`s: the `ShowOps` view for `List(1,2,3)`, `"x"` and `42`, `Show[List[Int]]` via `listShow` with its nested `Show[Int]` search for `IntShow`, `StringShow`, the `intToString` view, the local instance winning for `42.shown`, and the `implicitConversions` feature check. `implicit` edges `Implicits → syntax`, `Implicits → Show`. Elsewhere, failed view searches show nsc's overload resolution and the `n += 1` rewrite (`Double => Int`, `(=> Int) => ?{def +=: ?}`, with the buffered `value += is not a member of Int`).
- Viewer: the server was restarted from this checkout with `--ui` (the viewer in `replayable-compiler-design-780592`), `--versions`/`--recordings` pointing at the previous server's directories so the Scala 3 entries remain. `http://127.0.0.1:8777/` lists the 2.13.18 recordings (base and `-opt` variants); Implicits opens with no console errors, 27 versions (23 tree phases, genBCode bytecode, `jvm:r1:dce`, `jvm:lineNumbers`, `jvm:classfile`), 13 chapters, the typer version with lineage highlights, the bytecode of `Show$`, and the ImplicitSearch filter in the event inspector.

## Event volumes (2.13.18, one file per compile)

| file | KiB | versions printed/skipped | nodes | re-ids | Typed | Retyped | Adapt | Complete | ImplicitSearch | Candidate | Report | edges | JVM versions | insns in a tree range |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Closures | 406 | 14/9 | 1081 | 135 | 181 | 230 | 32 | 25 | 30 | 0 | 1 | 19 | 5 | 120/153 |
| ForDesugar | 233 | 14/9 | 638 | 91 | 91 | 129 | 18 | 15 | 11 | 2 | 3 | 20 | 3 | 75/94 |
| FunctionClass | 270 | 12/11 | 775 | 64 | 82 | 237 | 14 | 12 | 8 | 0 | 0 | 17 | 3 | 149/167 |
| Implicits | 707 | 17/6 | 1927 | 213 | 288 | 384 | 62 | 56 | 11 | 9 | 0 | 39 | 23 | 224/265 |
| Mixins | 362 | 14/9 | 998 | 95 | 148 | 270 | 28 | 25 | 10 | 0 | 1 | 40 | 9 | 153/176 |
| Patmat | 1261 | 15/8 | 3273 | 227 | 548 | 985 | 72 | 45 | 0 | 0 | 0 | 102 | 13 | 518/592 |
| Closures `-opt` | 691 | 14/9 | 1507 | | | | | | | | | | 23 | 120/153 |
| Implicits `-opt` | 1173 | 17/6 | 2994 | | | | | | | | | | 58 | 224/265 |
| Patmat `-opt` | 1877 | 15/8 | 4613 | | | | | | | | | | 37 | 518/592 |

(`-opt` changes only the JVM part.) `Retyped` outnumbers `Typed`: nsc's transforms retype through `localTyper` and erasure retypes every tree. Re-ids are trees whose symbol, span or children changed in place (typer, erasure, constructors). Compile time through the server is 1.6–3.8 s per file in a fresh JVM, against about 1.2 s for plain scalac on Patmat.

## Missing against Scala 3

- No `Rejected` or `Scope` events (implicit candidates pruned by ranking, shadowing or divergence): nsc's `rankImplicits` and `ImplicitComputation` are not hooked yet. The failed `ImplicitSearch` and `Candidate` events carry the outcome only.
- No `Tentative` events: nsc's speculative typing is `silent(...)` with a buffering `ContextReporter`, not a TyperState; its buffered errors are recorded as `Report` with `buffered:true`, but not grouped by attempt and not marked kept or discarded.
- `Complete` covers namer completers only (as Scala 3 covers `Namer.Completer`), not classpath loaders or the unpickler.
- No per-miniphase granularity: there are no miniphases; 23 tree versions per unit, one per phase.
- Lineage at typer is mostly `rewrite exact` from the same tree's earlier snapshot rather than from distinct untyped/typed trees, and synthetic trees made with `atPos(pos.focus)` (offset positions) are matched by span as `[point, point]`.
- `syminfo` covers symbols of definitions in the unit's tree (not every referenced local symbol, as on Scala 3), printed at the end of each phase without forcing a symbol that is not yet initialized.
- 4 of 79 Patmat methods have no tree ancestor: the static forwarders `Circle.andThen`/`compose` and `Rect.tupled`/`curried`, which forward to `Function1`/`Function2` methods the case-class companions inherit from the library (no DefDef in this run). 10–20% of real instructions are outside any `genStat`/`genLoadTo` range (e.g. the return after the body, `MODULE$` setup in `<clinit>`).

## Viewer: nothing had to change

The viewer loads nsc recordings as they are. Observations, not edited (ui/ is not mine):

- The bytecode entry in the version list is labelled `genBCode (bytecode)` (hard-coded in `app.js` `verName`, `ir.js`, `multi.js`), while the nsc tree phase it follows is called `jvm`. Cosmetic; a label from the phase record would read `jvm (bytecode)`.
- Chapters from phase groups degrade to one chapter per phase as expected (13 chapters for Implicits after the median pruning); `parser`, `typer` and `erasure` are forced chapters by name, which nsc shares.
- nsc's tree kinds (`ClassDef`, `ModuleDef`, `Template`, `TypeTree`, `LabelDef`, `ArrayValue`) are already known to the explorer (`explorer.js` lists `ClassDef`/`ModuleDef`).
- The `jvm` tree version is always empty (genBCode does not change trees) and shows as a 0-new phase.

## Notes

- The server merge: this branch merged `claude/quizzical-wiles-8dc295` (the backend join points for released Scala 3 compilers) before restarting the server from here, so a rebuilt Scala 3 workspace gets the current agent.
- `client.py`'s `-- <options>` form never worked: argparse consumes the `--`, so the options are taken as file names. `--opt` now uses nsc's spelling for 2.13 versions.
- Printing perturbs nsc: `-Xprint`'s printer assigns positions to position-less subtrees when it rebuilds definitions from symbols. The recorder resets them (7 on Patmat); without that, delambdafy's new `Template`s kept the position into the backend.
