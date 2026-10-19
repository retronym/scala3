# JVM bytecode in the replay recorder

Written with LLM assistance (Claude), see `LLM_POLICY.md`. Branch `claude/replay-jvm-44e61d` (local commits, on top of the merged recorder). This implements the JVM half of "Extension: post-tree phases" in `PLAN-replayable-compiler.md`. The schema additions are in `SCHEMA-v1.md`, section "Extension: JVM bytecode".

## What it records

- **Bytecode versions per class.** At genBCode there is one `version` with `ir:"jvm"` per ClassNode, mirror classes included. Its text is ASM `Textifier` output, and its offsets cover the ClassNode, FieldNodes, MethodNodes and `InsnRange`s. MAXSTACK/MAXLOCALS lines are left out: they are 0 until the optimiser or the ClassWriter computes them.
- **Tree → bytecode lineage** (`derive how="codegen"`). It maps TypeDef → ClassNode, ValDef → FieldNode, DefDef → MethodNode, and any tree node that `genLoad`/`genStat` emits → the `InsnRange` of the instructions emitted while it was on the stack.
- **Optimiser passes as phases.** Each local-optimiser step that changes a method's text becomes a synthetic phase (`jvm:r<round>:<pass>`). So does the global optimiser (`jvm:inliner`) and the ClassWriter-bound state (`jvm:classfile`). Methods are deduplicated by text. Ranges follow their instructions by node identity, which yields `copy`/`rewrite`/`elide`, and the inliner yields `inlined-from`.

No backend `ev` records (`GenLoad`) are written. The ranges carry the same information without lengthening the `seq` order.

## Fork diff

Two commits on top of the merged recorder change `compiler/src` by +52 −3 over 7 files. 28 of those lines are the TyperObserver additions: seven no-op methods and an `inline` helper. Every hook site is a field read and a null test, as before.

| hook | site | lines |
|---|---|---|
| `classNodes(typeDef, classNode, mirror)` | `backend/jvm/CodeGen.scala:162` (after genClassNode / genMirrorClassNode and the TASTY attribute, before scheduling or global optimisation) | 2 |
| `classWritten(classNode)` | `backend/jvm/CodeGen.scala:250` (after local optimisation and the InnerClasses rebuild, before `ClassWriter`) | 2 (observer captured at `:35`) |
| `genMethod(dd, mnode)` | `backend/jvm/BCodeSkelBuilder.scala:929` (genDefDef, after initJMethod) | 2 |
| `genLoadEnter/Exit(tree, mnode)` | `backend/jvm/BCodeBodyBuilder.scala:91` (genStat) and `:320` (genLoadTo); the bodies moved to `genStat0`/`genLoadTo0` | 8 |
| `optimizerPass(owner, method, pass)` | `backend/jvm/opt/LocalOptimizer.scala:219`, inside the existing `traceIfChanged(optName)` that every local pass already calls | 6 |
| `inlined(owner, method, calleeOwner, callee, insnMap)` | `backend/jvm/opt/Inliner.scala:127`, after each `inlineCallsite` | 3 |
| observer for Context-free code | `backend/jvm/opt/OptimizerSettings.scala:35` | 1 |

The optimiser has no `Context`, by design (it may run on worker threads). That is why `OptimizerSettings` captures the observer next to the other eagerly read settings. As a consequence, `-Ybackend-parallelism:1` is required. The plugin checks it, records it in `meta.requires`, and skips JVM recording with a warning otherwise.

`traceIfChanged` was already a per-pass hook point. It is what `-Yopt-trace` uses. Passing the observer through it gave per-pass snapshots without touching any pass.

## Plugin

`plugin/src/JvmRecorder.scala` (574 lines) plus small additions to `Recorder` (observer overrides, a few accessors, and `newExtNode` for nodes outside the tree arena).

- **Text and offsets.** A `MethodVisitor` wrapping `TraceMethodVisitor` marks the Textifier's text-list index before each instruction. `MethodNode.accept` makes exactly one visit call per `AbstractInsnNode`, which the code asserts. A `ClassVisitor` wrapping `TraceClassVisitor` marks where each field and method starts. The class text is kept split around the method bodies, so an optimiser step re-textifies only the method that changed and splices it in.
- **Ranges.** `genLoadEnter` remembers `instructions.getLast` and `genLoadExit` takes the first and last new instruction nodes. They are resolved to indices when `classNodes` fires. Node references survive later insertions, which indices would not. Ranges with the same extent share one node, and each tree node on it gets its own derive.
- **Tree ancestors.** The recorder first tries the arena node itself, then the copier hook back to the arena (the backend's module `thisMap` copies), then the symbol (DefDef/ValDef), then span+kind. Forwarders resolve to the module method by (name, descriptor). Forwarders to inherited methods resolve by member symbol in any unit. Static forwarders `m$` and the trait `$init$` get fresh symbols, so they are matched by name (heuristic). A synthesized `<clinit>` falls back to the TypeDef (heuristic).
- **Optimiser.** `optimizerPass` re-textifies the method. A new method state is kept only if the text changed. Ranges are re-placed at the min..max index of their surviving instruction nodes, or elided. `inlined` records the callee→copy instruction map. At the first hook after the global optimiser, all methods are snapshotted as `jvm:inliner`, with `inlined-from` ranges for the whole callee body and for each callee range.
- **Emission.** Everything is written in `Recorder.close`, after the last tree version and grouped by JVM phase, so that `inlined-from` can refer to a callee in any class or unit.

## Checks

`plugin/jvm-verify.sh` re-runs all of the following. `jvmcheck.py` and `jvmcoverage.py` are the lineage checks.

- **Givens.render.** `jvmcheck.py out/Givens.jsonl --givens`: the two `Apply`s of `shown` map to ranges that end in `INVOKEVIRTUAL Givens$package$.shown`. The literals `1`, `2`, `3` and `"x"` map to exactly `ICONST_1`, `ICONST_2`, `ICONST_3` and `LDC "x"`. The outer `+` maps to the range ending in `ARETURN` (the string concatenation indy is inside it).
- **Through the optimiser.** `jvmcheck.py --opt`: every genBCode range either reaches the class's last version through copy/rewrite or ends in an `elide`. Under `-opt -opt-inline:<sources>`, literal ranges keep their instructions, except 2 in Patmat where `simplifyJumps` dropped a `GOTO` after `ICONST_0`.
- **Validation.** `analyze.py` (extended for `ir:"jvm"`) reports 0 errors on all outputs.
- **Reproducibility.** Two runs are byte-identical after the `meta` line for each corpus file (default and optimised), for `util`, and for `util` optimised. `REPLAY_PERTURB_IDHASH=1000` gives byte-identical output on the corpus (default and optimised).
- **Bytecode unchanged.**
  - `.class`/`.tasty` output is identical with JVM recording on and off (`nojvm`), for the corpus (default and optimised) and for `util` (default and optimised).
  - With the plugin versus a plain compile it is identical for the corpus, and for `util` with `fused`.
  - Unfused `util` differs from a plain compile in one class, `GenericHashMap$EntryIterator`, which loses an unused `$outer` accessor. That comes from the merged recorder's unfused phase plan, not from this work: it happens with `nojvm` too, and not with `fused`.
- **`-Ycheck:all`.** No failures on the five corpus files, with a plain compile and with the plugin (`fused`, `-opt -opt-inline:<sources>`).
- **testCompilation** (sbt on JDK 21, log `testCompilation-jvm.log`). 21 of 22 test methods and 87 of 88 suites pass. The one failure is `tests/generic-java-signatures/i24553.scala`, the JDK-dependent check file described in `REPORT-merged.md` (`Object.wait(long)` is no longer `native` from JDK 19 on). It is the same as the baseline.

## Lineage coverage

At genBCode, with no optimisation:

| | MethodNodes with a tree ancestor | instructions (with an opcode) inside a tree-derived range | ranges with an ancestor |
|---|---|---|---|
| Closures | 23/24 | 119/149 (80%) | 108/112 |
| ForDesugar | 14/15 | 69/91 (76%) | 59/65 |
| Givens | 47/48 | 330/372 (89%) | 257/283 |
| Mixins | 32/32 | 214/237 (90%) | 198/213 |
| Patmat | 86/86 | 511/588 (87%) | 410/428 |
| corpus | 202/205 (98.5%) | 1243/1437 (86.5%) | |
| dotc/util (139 classes) | 2145/2182 (98.3%) | 23312/24794 (94.0%) | 20861/21830 |

- **Methods without an ancestor.** In the corpus these are only `$deserializeLambda$`, which has no source tree. In `util` there are also forwarders to methods inherited from outside `util` (e.g. `NoSourcePosition.show` from `Showable`).
- **Uncovered instructions.** These are almost all code the backend synthesizes from new trees: forwarder bodies (`GETSTATIC M$.MODULE$; …; INVOKEVIRTUAL; xRETURN`), trait static forwarders `m$`, and the `<clinit>` module-field initialisation.
- **Unresolved trees.** "unknownTrees" (969 in `util`) counts genLoad trees that the backend built itself and that resolve to nothing.

## Text sizes

Characters of class text at genBCode, offset nodes, and for `-opt -opt-inline:<sources>` the number of versions and the final (`jvm:classfile`) size:

| file | class | genBCode chars | nodes | -opt versions | -opt final chars |
|---|---|--:|--:|--:|--:|
| Closures | `Closures$` | 8419 | 78 | 4 | 8628 |
| Closures | `Closures` | 2193 | 6 | 9 | 2639 |
| Closures | `Closures$$anon$1` | 3153 | 56 | 7 | 3077 |
| ForDesugar | `ForDesugar$` | 9325 | 79 | 3 | 9511 |
| ForDesugar | `ForDesugar` | 1379 | 4 | 2 | 1397 |
| Givens | `Show$given_Show_String$` | 2342 | 23 | 2 | 2605 |
| Givens | `Show$given_Show_Int$` | 2060 | 24 | 2 | 2185 |
| Givens | `Show` | 854 | 3 | 9 | 1233 |
| Givens | `Show$` | 1519 | 18 | 2 | 1835 |
| Givens | `Show$given_Show_List` | 4039 | 33 | 2 | 4289 |
| Givens | `Givens$package$$anon$1` | 1491 | 14 | 6 | 1663 |
| Givens | `Givens$package$` | 7521 | 137 | 8 | 8103 |
| Givens | `Givens$package` | 991 | 3 | 9 | 1052 |
| Givens | `Givens$` | 6303 | 78 | 17 | 7658 |
| Givens | `Givens` | 726 | 4 | 2 | 744 |
| Givens | `Givens$given_Show_Int$2$` | 1297 | 13 | 2 | 1414 |
| Mixins | `Timestamped` | 1631 | 11 | 2 | 1775 |
| Mixins | `Service` | 7934 | 161 | 6 | 8599 |
| Mixins | `Counting` | 3164 | 35 | 2 | 3326 |
| Mixins | `Logger` | 1876 | 18 | 2 | 2026 |
| Mixins | `Mixins$` | 1635 | 29 | 2 | 1659 |
| Mixins | `Mixins` | 338 | 2 | 2 | 344 |
| Patmat | `Rect` | 7387 | 141 | 11 | 7384 |
| Patmat | `Rect$` | 2901 | 42 | 2 | 3066 |
| Patmat | `Patmat$` | 5445 | 137 | 12 | 5016 |
| Patmat | `Patmat` | 558 | 3 | 2 | 570 |
| Patmat | `Circle` | 6283 | 108 | 11 | 6281 |
| Patmat | `Circle$` | 2671 | 35 | 2 | 2836 |
| Patmat | `Shape` | 271 | 1 | 1 | 271 |
| Patmat | `Dot$` | 4519 | 52 | 4 | 4674 |
| Patmat | `Dot` | 2325 | 11 | 10 | 3111 |

`util`:

- **Default.** At genBCode, class text is 1.9 MB over 139 classes (median 5.9 K, max 273 K for `GenericHashMap`). Together with the `jvm:classfile` versions it is 3.7 MB of 84.8 MB, so JVM records are a small part of the file.
- **Optimised** (`-opt -opt-inline:<sources>`). There are 944 class versions over 26 JVM phases, 23 MB of text, and a 136 MB file. Every changed step repeats the whole class text, and every range of a changed method gets a new node and a `copy` derive (74,850 of them). Per-method `textSameAs`, or ranges that keep their id while their text is unchanged, would shrink this a lot. I left that for later, since the viewer has not consumed the optimised output yet.

Derives on `util` optimised, counted in nodes: codegen exact 24,532 / heuristic 259, rewrite 20,068, copy 74,850, elide 1,324, inlined-from 2,506 (449 inlined callsites).

## Overhead

On `util`, measured with the plugin's `timing` option, which times the JVM hooks and emission and reports on stderr only. JDK 21; the machine was not quiet.

- **Without the optimiser.** JVM recording costs 0.25 s. A plain compile takes about 5 s, and a full recording about 13 s (tree recording dominates, as in `REPORT-merged.md`).
- **With `-opt -opt-inline:<sources>`.** JVM recording costs about 1.0 s, mostly from re-textifying a method after every pass to test for change. A cheaper test, such as a hash over the instructions, would remove most of that.
- **Disabled hooks** (no observer). There are null tests on `genLoadTo`/`genStat` and one per pass. I have no bench-lab numbers for them.

## Fixture for the viewer

`out/jvm-fixture.jsonl`, 37 lines, generated by `plugin/mkfixture.py` from a real `-opt` recording of `plugin/fixture-Hi.scala`:

```scala
object Hi:
  def inc(x: Int): Int =
    val y = x
    y + 1
```

It is trimmed to one class (`Hi$`), one method (`inc`) and four ranges (`val y`, `x`, `y`, `1`). It has two JVM phases: genBCode, and `jvm:r1:pushPop`, into which the recording's `r1:copyProp` and `r1:staleStores` steps are folded. It shows:

- `codegen` derives from the tree nodes;
- `rewrite` of `y`'s range from `ILOAD 2` to `ILOAD 1` (copy propagation);
- `elide` of `x`'s range (the store and its load are gone);
- `rewrite` of `val y` down to its label;
- `copy` of the literal's `ICONST_1`.

It validates with `analyze.py`. The task asked for about 20 lines. I kept 37 so that every kind of record and every derive kind except `inlined-from` appears; `Givens-opt.jsonl` has `inlined-from` examples (`render` inlines `shown` and `given_Show_List`).

## Outputs

In `out/` (gitignored; regenerate with `plugin/jvm-verify.sh`):

- `{Closures,ForDesugar,Givens,Mixins,Patmat}.jsonl` and `*-opt.jsonl` (`-opt -opt-inline:<sources>`);
- `util.jsonl` and `util-opt.jsonl`;
- `jvm-fixture.jsonl`.

## Known gaps

- Per-pass text is per class, and range copies are chatty (see "Text sizes").
- Rounds are counted per method, so `jvm:r2:dce` mixes methods that took different paths through round 1. A per-method round view would need the viewer to group by method.
- `inlined-from` links to the callee's genBCode state. When the callee had itself been changed by inlining before being inlined, the copies of its own inlined instructions have no callee range.
- The closure optimiser's rewrites show up only as `rewrite` in `jvm:inliner`, with no hook of their own.
- Synthesized backend code (forwarder bodies, `MODULE$` init) has no tree lineage below the method level.
- Scala.js is out of scope.
