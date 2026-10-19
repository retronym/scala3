# Reconciling the JVM and agent branches

Written with LLM assistance (Claude), see `LLM_POLICY.md`. Branch `claude/funny-chaplygin-0b0e4a`: `claude/replay-jvm-44e61d` plus the three commits of `claude/sad-booth-255608` (cherry-picked), plus one commit adding agent join points for the JVM hooks. Background: `REPORT-jvm.md`, `REPORT-agent.md`, and decision 3 of "Results: JVM bytecode and agent delivery" in `PLAN-replayable-compiler.md`.

## Result

- **One plugin, two deliveries.** `REPLAY_VARIANT=fork` (TyperObserver fork, now including the backend hooks) and `REPLAY_VARIANT=agent` (stock `main` ae374e9583 + `-javaagent`) both record the full stream, JVM records included.
- **Byte-identical.** `agent/compare.sh` (fork vs agent with `fixTypeApply`, everything after the `meta` line) gives identical streams for all five corpus files and `dotc/util`, both without and with `-opt -opt-inline:<sources>`. Output: `agent/compare-reconcile.txt`.

| stream | bytes (fork) | `ir:"jvm"` lines | |
|---|--:|--:|---|
| Closures | 461,838 | 294 | identical |
| ForDesugar | 345,420 | 168 | identical |
| Givens | 946,410 | 696 | identical |
| Mixins | 524,999 | 490 | identical |
| Patmat | 1,289,070 | 1,091 | identical |
| util | 84,777,905 | 48,614 | identical |
| Closures-opt | 610,990 | 950 | identical |
| ForDesugar-opt | 360,246 | 187 | identical |
| Givens-opt | 1,510,237 | 3,288 | identical |
| Mixins-opt | 662,290 | 1,219 | identical |
| Patmat-opt | 2,067,879 | 4,716 | identical |
| util-opt | 136,096,224 | 244,357 | identical |

(The agent files are 2 bytes longer: the `meta` line holds the output path, `out-agent` vs `out-fork`.)

- **Every JVM site can be hooked from outside.** No backend hook had to be declared upstream-only. Neither suspected obstacle applies: `traceIfChanged` is a local `def`, not `inline`, and lambda lifting turns it into `LocalOptimizer.traceIfChanged$1`. The optimiser's missing Context matters only to the fork, which has to carry the observer through `OptimizerSettings`. The agent's observer is a static.
- **The refactor leaves the fork's output unchanged.** With the refactored plugin, the fork variant's corpus streams (default and `-opt`) are byte-identical to the JVM branch's `out/`. util: see Checks.

## What moved

From the agent branch, unchanged:

- `agent/` (the Byte Buddy agent, the bootstrap API jar, `build.sh`, `compare.sh`, `bench/`, `events.py`, `test/Ranking.scala`);
- `plugin/src-fork/`, `plugin/src-agent/`, `plugin/src/Observer.scala`;
- `REPLAY_VARIANT` in `env.sh`, `build.sh` and `record.sh`;
- the agent-only events (Scope, Rejected, Tentative, buffered Report) in `Recorder`;
- `REPORT-agent.md`.

From the JVM branch, unchanged:

- the `compiler/src` backend hooks;
- `plugin/src/JvmRecorder.scala`;
- the checks (`jvm-verify.sh`, `jvmcheck.py`, `jvmcoverage.py`, `util-run.sh`);
- the fixture and the schema extension.

New or changed:

- **`Observer.scala`** (the plugin's own copy of the hook trait) gets the seven JVM methods, with the fork's signatures.
- **`ForkShim`** forwards the seven JVM methods.
- **`AgentBridge`** adapts them from the Object-typed API. `classNodes` unpacks `generateClassNodes`' result list (the main node first, the mirror second if there is one). `inlined` destructures the `KnownCallsite`.
- **`replay.agent.api.Observer`** gets seven methods. **`Hooks`** gets three stashes: `genDefDef`, `optOwner` and `armInline`.
- **`JoinPoints`** gets a `jvm` group of 10 sites, on unless the agent option is `nojvm`. **`Advices`** gets 10 advice classes. The agent's type matcher now also admits `dotty.tools.backend.jvm.*`.
- **`agent/compare.sh`** takes a second argument `-opt` and prints the count of JVM lines.
- **`plugin/rebuild-out.sh`** (new) rebuilds `out/`, see below.
- **`agent/alloc-check-jvm.txt`** holds the allocation check for the new sites.
- **`plugin/analyze.py`** tolerates a stream without a `stats` record.

## Conflicts resolved

Text conflicts were only in `Recorder.scala`, `ReplayPlugin.scala` and `Observer.scala`:

- **`Recorder.close`**: the JVM branch's `ctx.base.observer = null` became the agent branch's `Install.uninstall(this)`. The JVM timing line is kept.
- **`Recorder`**: the hook bodies are concatenated. That is the JVM overrides and the JvmRecorder services, followed by `rejected` and the later agent-only overrides.
- **`ReplayPlugin.init`**: the JVM branch's `-Ybackend-parallelism:1` check (otherwise `nojvm`) is kept, and its `ctx.base.observer = rec` became `Install.install(rec, options)`.
- **`Observer.scala`**: the agent-only methods come first, then the JVM methods.

Semantic conflicts, which git did not flag:

- `Recorder` now extends the plugin's `Observer`, not the fork's `TyperObserver`. The JVM overrides only compile once `Observer` declares those methods. The signatures are the same, so `JvmRecorder` is untouched.
- Without forwarding in `ForkShim`, the fork variant would have silently recorded no JVM data.

## JVM join points (stock `main`, ae374e9583)

All are FOUND by `Hooks.verify`. The descriptors are in `JoinPoints.java`. The arm/stash pattern is the one the typer hooks use: the outer method's advice sets a static, and the inner method's advice consumes it.

| class | method | mechanism | fork site | fidelity |
|---|---|---|---|---|
| `CodeGen` | `generateClassNodes(TypeDef)(ctx)` | exit advice: the returned list gives main and mirror | CodeGen.scala:162 `classNodes` | The fork fires before the method's last step, `atPhase(sbtExtractDependenciesPhase)` (class name and isLocal), and the agent fires after it. That step does not touch the ClassNodes or fire any hook. An empty list (the TypeError path) fires nothing. |
| `BCodeSkelBuilder$PlainSkelBuilder` | `genDefDef(dd)(ctx)` | enter advice stashes `dd` | BCodeSkelBuilder.scala:929 `genMethod` | genDefDef does not nest (it asserts so). |
| `BCodeSkelBuilder$PlainSkelBuilder` | `initJMethod(flags, params)(ctx)` | exit advice: fires with field `mnode` if it is non-null | same | `initJMethod` is called only from genDefDef, and the fork's hook follows it directly. |
| `BCodeBodyBuilder$PlainBodyBuilder` | `genStat(tree)(ctx)` | enter/exit advice, `mnode()` read once on enter | BCodeBodyBuilder.scala:91 | As in the fork, a throw skips the exit. |
| `BCodeBodyBuilder$PlainBodyBuilder` | `genLoadTo(tree, bt, dest)(ctx)` | same | BCodeBodyBuilder.scala:320 | `mnode()` is the one call into compiler code (an accessor). |
| `opt.LocalOptimizer` | `methodOptimizations(method, owner)` | enter advice stashes `owner`, exit advice restores it | LocalOptimizer.scala:219 `optimizerPass` | `traceIfChanged$1` does not capture `ownerClassName`. |
| `opt.LocalOptimizer` | `traceIfChanged$1(doTrace, method, trace, optName)` | enter advice fires | same | Mangled name, and the parameter order is the lambda lifter's. |
| `opt.GlobalOptimizer` | `doInline$1(method, state, changed, r, frame, w)` | enter advice arms with `method` | Inliner.scala:127 `inlined` | Mangled name. |
| `opt.GlobalOptimizer` | `inlineCallsite(callsite, frame, update)` | consumed on enter, fires on normal exit | same | The closure optimiser's `inlineCallsites` path stays unobserved, as in the fork. |
| `CodeGen` | `serializeClassNode(classNode)` | exit advice, also on a throw | CodeGen.scala:250 `classWritten` | The fork fires just before `classNode.accept(writer)`. The ClassWriter only reads the node, so the state on exit is the same. |

- **Allocation.** No hooked backend method gains an allocation site (`agent/alloc-check-jvm.txt`, from Byte Buddy's dump). For example, `genStat` goes from 340 to 373 instructions with allocations 0 → 0, and `genLoadTo` from 1212 to 1249 with 11 → 11.
- **The genLoad advice uses two classes** (`GenStat`, `GenLoadTo`) with fixed argument indices. A first version used `@AllArguments`, which allocates an array per call.
- **`-Ybackend-parallelism:1` is still required.** The agent's static markers assume one thread, as the fork's `OptimizerSettings` observer does.

## Upstream vs agent, all hooks

This updates the recommendation in `REPORT-agent.md` with the backend hooks. The criterion is the same: how hard the site is to hook from outside. Mangled local names and control-flow reconstruction argue for upstream. Stable, method-level sites can stay in the agent.

| hook | agent mechanism | fragility from outside | recommendation |
|---|---|---|---|
| `phaseBegin`/`phaseEnd` | call-site substitution in `Run.runPhases$1` + `RunHook` on the compiler's loader | high: mangled local, no method boundary, helper must be loadable by the compiler | **upstream** |
| `typedEnter`/`Exit` | advice that repeats `typed`'s two guards | medium: duplicates compiler logic | **upstream** |
| `completeEnter`/`Exit` | arm and Context stash across `complete` → `completeInCreationContext` | medium | **upstream** |
| `implicitSearchEnter`/`Exit` | method-level advice | low | **upstream** (cheap, central) |
| `candidateEnter`/`Exit` | arm across `tryImplicit` → `typedImplicit` | medium | **upstream** |
| ranking verdict (Scope, Rejected) | `candidateKind$1`, `comesTooLate$1`, `rank$1`, field `x$5` | high: four mangled names | **upstream**, as a verdict-level hook |
| `report` | method-level advice | low | agent. An upstream hook should also cover TyperState buffering. |
| `bufferedReport` | `StoreReporter.report`, field `fromTyperState` | low | agent |
| `adaptEnter`/`Exit` | arm across `adapt` → `adapt1` | medium, but chatty and internal | agent |
| `copied` | `TreeCopier.finalize` exit | low, but hot | agent |
| Tentative | `tryEither`, `setNewTyperState`, `TyperState.commit` | low to medium | agent |
| `classNodes` | `generateClassNodes` exit | low: private method, stable name | **upstream**. It is the ClassNode-complete callback the working list asked for, and the agent's site differs from the fork's in order. |
| `genMethod` | stash in `genDefDef`, fire after `initJMethod` | low to medium | agent |
| `genLoadEnter`/`Exit` | method-level advice on `genStat`/`genLoadTo` | low, but hot | agent |
| `optimizerPass` | owner stash + `traceIfChanged$1` | high: mangled local, lifted parameter order | **upstream**, as a per-pass callback next to `-Yopt-trace` |
| `inlined` | arm in `doInline$1`, fire in `inlineCallsite` | high: mangled local | **upstream**, if the optimiser view is kept |
| `classWritten` | `serializeClassNode` exit | low | agent (or fold into the per-pass callback as the final pass) |
| TypeApply fix | exit advice that replaces `@Return` | – | an ordinary compiler change, not a hook |
| Name/NameKind hashes | `defineMethod` at load, 4 pinned subclass bodies | high, `-javaagent` only | an ordinary compiler change, not a hook |

Summary for a busy reader:

- **The upstream interface is the hooks that need control-flow knowledge or mangled names.** On the tree side these are phase boundaries, typed, complete, implicit search, candidate, and the ranking verdict. On the backend side they are classNodes, and, if the optimiser view is wanted, optimizerPass and inlined.
- **The rest stays in the agent.** These are adapt, copied, report and buffered reports, tentative typing, genMethod, genLoad, and classWritten.
- **The backend's optimiser sites are the clearest new upstream case.** They are cheap in the compiler because `traceIfChanged` already exists. From outside they depend on two lambda-lifted names.

## Checks

- **`agent/compare.sh`** (`fixTypeApply`, with and without `-opt`): 12 of 12 identical, see the table above.
- **Fork variant vs JVM branch.** The 10 corpus streams are byte-identical to `replay-jvm-44e61d/local/replay/out`. For util, compare.sh compiles against the stock classpath with `-nowarn`, so its stream differs from the JVM branch's. `jvm-verify.sh` (fork) compiles util as the JVM branch did: `out/util.jsonl` and `out/util-opt.jsonl` are byte-identical to the JVM branch's after the `meta` line.
- **`plugin/jvm-verify.sh`, fork variant:** green.

  - Every corpus file, default and `-opt`: bytecode is the same with JVM recording on and off and with and without the plugin, the run reproduces, `analyze.py` reports 0 errors, and the lineage checks pass.
  - The Givens.render checks pass (7/7).
  - The one bytecode difference is in util (default and `-opt`): `GenericHashMap$EntryIterator` loses an unused `$outer` accessor. `REPORT-jvm.md` already lists this. It comes from the merged recorder's unfused phase plan. (log `jvm-verify-fork.txt`).
- **`plugin/rebuild-out.sh`** (agent variant with `fixTypeApply,rejected,tentative`, the same checks): the same results line for line, with 0 errors on every stream, including util and util-opt.

  - With the extras on, the JVM records are unchanged (Givens 696 and Givens-opt 3288 `ir:"jvm"` lines, util 48,614, as in the fork).
  - The extras add Rejected/Scope/Tentative events: Givens has 208/8/4, and util has 12,055/867/1,744, the agent report's numbers.
  - `Ranking.scala` stops at its intended ambiguity error, so it has no backend records and no `stats` line. `analyze.py` used to crash on a stream without `stats`. It now defaults it to empty, the only change to the checks. (log `jvm-verify-agent.txt`).
- **testCompilation** (sbt, JDK 21, log `testCompilation-reconcile.log`): 21 of 22 test methods pass. The one failure is `tests/generic-java-signatures/i24553.scala`, the JDK-dependent check file. This is the baseline.

## The viewer's data directory

`out/` is rebuilt by `plugin/rebuild-out.sh`. It uses the stock compiler and the agent with `fixTypeApply,rejected,tentative`, so one directory has the fork's events, the JVM records, and the agent-only Scope, Rejected, Tentative and buffered Report events:

- `{Closures,ForDesugar,Givens,Mixins,Patmat}{,-opt}.jsonl`;
- `util.jsonl` and `util-opt.jsonl`;
- `Ranking.jsonl` (from `agent/test/Ranking.scala`, typer only, see Checks);
- `jvm-fixture.jsonl` (tracked, unchanged).

`out-fork/` and `out-agent/` hold the compare.sh streams, which have no agent-only events. The `meta` line does not record the delivery or the agent options. A viewer that needs to know whether Rejected events could occur should look for them, not for a flag.

## Rebuild from scratch

`classes-fork/` (this branch's `compiler/src`, built with `sbt scala3-compiler-nonbootstrapped/compile`) and `classes-stock/` (copied from the agent worktree, `main` at ae374e9583) are snapshots and are not committed. `plugin/compiler.classpath` and `corpus/` are not committed either. Then run:

1. `agent/build.sh`
2. `REPLAY_VARIANT=fork plugin/build.sh`
3. `REPLAY_VARIANT=agent plugin/build.sh`
4. `agent/compare.sh fixTypeApply [-opt]`
5. `REPLAY_VARIANT=fork plugin/jvm-verify.sh`
6. `plugin/rebuild-out.sh`
