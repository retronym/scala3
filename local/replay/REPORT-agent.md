# Hook delivery by a bytecode agent

Written with LLM assistance (Claude), see `LLM_POLICY.md`. Branch `claude/sad-booth-255608` (local commits on top of `claude/replay-merged-221743`). Design context: "Hook delivery: upstream interface vs. bytecode agent" in `PLAN-replayable-compiler.md`.

## Result

A Byte Buddy agent on a stock `main` compiler (`ae374e9583`, no in-tree hooks) produces v1 streams **byte-identical** to the `TyperObserver` fork's. This holds for all five corpus files and for `dotc/util` (36 files, 73.6 MB), comparing everything after the `meta` line. Both runs use the same plugin and recorder code, and the fork's output is regenerated in this worktree.

The agent has to supply the fork's two behaviour changes as well as its hooks:

- **The `TypeApply` copier fix**, as an optional exit advice (`fixTypeApply`). Without it, 5 of 6 streams differ. The arena and version counts are exactly the merged report's "no fix" column (Givens 3655 nodes and 70 printed versions, against 1644 and 21 with the fix). Mixins is identical either way.
- **The `Name`/`NameKind` content hashes**, as load-time class redefinition. Adding `hashCode` to the five base classes was not enough. In stock, `case class UniqueNameKind` and the three `AvoidNameKind` enum cases carry a compiler-synthesized `hashCode` (a product hash, and the hash of the case name), because their base had none when they were compiled. In the fork they inherit `NameKind.hashCode = tag`. `MoveStatics` groups classes in a `Map[Name, …]`, so this reordered the top-level classes of Givens from `moveStatic` on (12 lines). The agent now also replaces those four bodies. Without any Name hashes (`nonames`), the streams differ by 4 to 2636 lines per file.

So the fork can shrink to nothing: the agent supplies the hooks and both fixes. That doesn't mean the fixes belong in an agent (see the recommendation).

## How it is wired

- **`replay-agent-api.jar`, on the bootstrap path** (`Boot-Class-Path` in the agent manifest). It holds `replay.agent.api.Observer`, an interface in which every compiler object is an `Object` and a `Span` is its `long`, and `Hooks`, with a static `observer` field, a few arm/consume markers and the verifier. The bootstrap path is the only place that the advice inlined into compiler classes and the plugin (a `URLClassLoader` child of the compiler's loader) both see, whatever loader the compiler runs in. The cost is the erased interface. The plugin's `AgentBridge` casts back, and `checkcast` is the whole price.
- **`replay-agent.jar`** (`-javaagent`, with `byte-buddy.jar` on its `Class-Path`). Byte Buddy 1.17.5, pinned in `agent/build.sh` and fetched with coursier. `ReplayAgent` installs two `AgentBuilder`s. The first does Name hashes, using `REDEFINE` and `defineMethod`, at load time only. The second does the advice and substitution, with `disableClassFormatChanges`, plus `RETRANSFORMATION` when self-attached. `JoinPoints` is the pinned table. A site is matched by binary class name, method name and erased descriptor, plus a required field where the advice reads one. Anything else is MISSING, and `Hooks.verify` (called from the plugin's `init`) force-loads every target class and throws with the table if a site is missing.
- **The plugin** has its own copy of the observer trait (`src/Observer.scala`) and one `Install` per delivery: `src-fork/` sets `ctx.base.observer` to a shim, and `src-agent/` sets `Hooks.observer` to the bridge. `build.sh` and `record.sh` take `REPLAY_VARIANT=fork|agent|stock`. Apart from that, the recorder changed in two places: `copied` has no Context (stock `finalize` has none), and the agent-only events listed below were added. The fork's output with the refactored plugin is byte-identical to the merged session's `out/`.

Run it: `agent/build.sh`, `REPLAY_VARIANT=fork plugin/build.sh`, `REPLAY_VARIANT=agent plugin/build.sh`, then `agent/compare.sh [agent opts]`.

## Join points (stock `main`, ae374e9583)

All found. "Arms" means the method sets a static marker that the next entry of the inner method consumes. That is how the advice reproduces a fork hook that wraps a *call* inside a method rather than the method itself: a direct, unarmed call of the inner method fires nothing (e.g. `adapt1`'s own recursion, or ImportSuggestions' calls of `typedImplicit`).

| group | class | method | descriptor | mechanism | fork site |
|---|---|---|---|---|---|
| fork | `Run` | `runPhases$1` | `(List;List;[Phase;Context;)V` | MemberSubstitution of the `Phase.runOn` call → `RunHook.runOn` | Run.scala:421/425 |
| fork | `typer.Typer` | `typed` | `(Tree;Type;SimpleIdentitySet;Context;)Tree` | advice, with the fork's two guards mirrored | Typer.scala:4157 |
| fork | `typer.Typer` | `adapt` | same | advice, arms | Typer.scala:4541 |
| fork | `typer.Typer` | `adapt1` | same | advice, fires | Typer.scala:4541 |
| fork | `typer.Namer$Completer` | `complete` | `(SymDenotation;Context;)V` | advice, arms and publishes ctx | Namer.scala:940 |
| fork | `typer.Namer$Completer` | `completeInCreationContext` | `(SymDenotation;)V` | advice, fires | Namer.scala:940 |
| fork | `typer.Namer$ClassCompleter` | `completeInCreationContext` | `(SymDenotation;)V` | advice, fires (override) | Namer.scala:940 |
| fork | `typer.Implicits` (default method) | `inferImplicit` | `(Type;Tree;JSet;Context;)SearchResult` | advice | Implicits.scala:1101 |
| fork | `typer.Implicits$ImplicitSearch` | `tryImplicit` | `(Candidate;Z)SearchResult` | advice, arms | Implicits.scala:1312 |
| fork | `typer.Implicits` (default method) | `typedImplicit` | `(Candidate;Type;Tree;JContext;)SearchResult` | advice, fires | Implicits.scala:1312 |
| fork | `reporting.Reporter` | `report` | `(Diagnostic;Context;)V` | advice | Reporter.scala:241 |
| fork | `ast.Trees$Instance$TreeCopier` | `finalize` | `(Tree;Tree;)Tree` | exit advice | Trees.scala:1282 |
| fork | `ast.Trees$Instance$TreeCopier` | `finalize` | `(Tree;MemberDef;)MemberDef` | exit advice | Trees.scala:1289 |
| fix | `ast.tpd$TimeTravellingTreeCopier` | `TypeApply` | `(Tree;Tree;List;Context;)TypeApply` | exit advice replacing `@Return` | tpd.scala:851 |
| names | `core.Names$SimpleName`, `$TypeName`, `$DerivedName`, `NameKinds$NameKind`, `$NameKind$Info` | `hashCode` | `()I` | `defineMethod` at load | Names.scala:264/344/388, NameKinds.scala:36/46 |
| names | `NameKinds$UniqueNameKind`, `$AvoidNameKind$$anon$9/10/11` | `hashCode` | `()I` | body replaced with `tag()` at load | inherited in the fork |
| extra | `typer.Implicits$ImplicitRefs` | `candidateKind$1` | `(Type;ZTermRef;Context;)I` | exit advice | – |
| extra | `typer.Implicits$ImplicitSearch` | `comesTooLate$1` | `(Candidate;)Z` | exit advice, field `x$5` | – |
| extra | `typer.Implicits$ImplicitSearch` | `searchImplicit` | `(List;Z)SearchResult` | advice, field `x$5` | – |
| extra | `typer.Implicits$ImplicitSearch` | `rank$1` | `(ZZListBuffer;ZList;SearchResult;List;)SearchResult` | enter advice, field `x$5` | – |
| tentative | `typer.Typer$` | `tryEither` | `(Function1;Function2;Context;)Object` | advice, arms | – |
| tentative | `core.Contexts$FreshContext` | `setNewTyperState` | `()FreshContext` | exit advice, fires | – |
| tentative | `core.TyperState` | `commit` | `(Context;)V` | enter advice | – |
| tentative | `reporting.StoreReporter` | `report` | `(Diagnostic;Context;)V` | enter advice, field `fromTyperState` | – |

Notes on fidelity:

- **`Typer.typed`**. The fork hooks only the final `else` of `typed`. The advice therefore repeats its two guards: the source-switching self-call (`tree.source != ctx.source && tree.source.exists`) and `ctx.run.isCancelled`. These are field accessors plus `SourceFile.equals`, which is the one place the advice calls compiler code.
- **`Run`**. There is no method boundary around `runOn`, because `Profiler.onPhase` and `Stats.trackTime` are `inline`, and `Phase.runOn` is overridden and called via `super`. So the single call is replaced with a static `RunHook.runOn(phase, units, ctx)`. `RunHook` names compiler types and so must be loadable from the compiler's loader. It is in the agent jar, which is on the system class path with `-javaagent`. A compiler in another loader (e.g. sbt's) would need it injected there; I did not do that. The fork calls `phaseEnd` after Run's `InterruptedException` catch, and `RunHook` calls it just before. That ordering differs only for cancelled compiles.
- **`complete`**. `completeInCreationContext` has no Context, so `complete`'s enter advice publishes it in `Hooks.completeCtx` and restores the previous value on exit.
- **The markers** are plain statics, which assumes the single-threaded typer (`ContextBase.checkSingleThreaded`).

## Timings

Crude: no JMH, and the machine was not quiet (load 5–12 from other sessions). JDK 21.0.12. Raw numbers are in `agent/bench-results.txt` and the script is `agent/bench/bench.sh`.

**A. Recording on, fork vs stock+agent (`fixTypeApply`), fresh JVM per compile, 5 alternating reps.**

| | fork | agent | Δ |
|---|---:|---:|---:|
| corpus (5 compiles, median) | 9.62 s | 12.71 s | +0.6 s per compile |
| `dotc/util` (median) | 13.31 s | 13.99 s | +0.7 s (+5%) |

The difference is a fixed cost per JVM, not per hook. It covers Byte Buddy start-up and transforming the large target classes (`Typer` alone is about 15k lines of bytecode) at load. A trivial command line costs about 230 ms more with the agent (premain plus matching). The per-event cost is lost in the noise against the recorder's own work.

**B. No recorder (hooks disabled), 10 compiles of `dotc/util` in one JVM, 3 rounds per variant.**

| | 1st compile | iterations 6–10, mean (median) |
|---|---:|---:|
| stock | 5.06 s | 817 (774) ms |
| stock + agent | 5.18 s | 750 (742) ms |
| stock + agent + `fixTypeApply` | 5.03 s | 757 (731) ms |
| fork | 4.88 s | 820 (822) ms |

All four are within noise of each other. The agent column being faster is noise, not a result. Limits: 15 warm samples per variant, no fork isolation of GC/JIT state, a shared machine, and ~1 s compiles. This rules out a gross cost (more than ~10%) and nothing finer. Measuring ~1% needs the bench-lab paired setup.

**Allocation.** `agent/bench/AllocCheck.java` reads Byte Buddy's dump (`-Dnet.bytebuddy.dump`) and counts the allocation sites in each changed method, original against instrumented: `NEW`, `NEWARRAY`, `ANEWARRAY`, `MULTIANEWARRAY`, `invokedynamic` and boxing `valueOf`. The result is `agent/alloc-check.txt`. **No hooked method gains an allocation site.** For example, `Typer.typed` goes from 61 to 138 instructions with allocations 0 → 0, and `TreeCopier.finalize` from 12 to 31 with 0 → 0. Enter/exit state lives in `@Advice.Enter` and `@Advice.Local` slots. With no observer, a hook is `getstatic Hooks.observer; ifnonnull; aconst_null; goto` (from `javap` of the dumped `Typer.typed`).

One finding here: Byte Buddy's default `InitializationStrategy` had added a `<clinit>` to each Name class. It reflectively calls `Nexus.initialize`, so it allocates once per class. Nothing needs initializing, so the Name builder now uses `InitializationStrategy.NoOp`.

## Dynamic self-attach

`-P:replay-record:selfattach=<replay-agent.jar>;fixTypeApply` calls `ByteBuddyAgent.install()` from `ReplayPlugin.init` (JDK 21 needs `-XX:+EnableDynamicAgentLoading`, and `-Djdk.attach.allowAttachSelf=true` avoids the external attach process). It then appends the API jar to the bootstrap path and the agent jars to the system class path, and calls `agentmain`.

Attaching takes about 540 ms. All advice and substitution sites can still be retransformed after the typer classes have loaded, because they only change method bodies. That includes `Run.runPhases$1`: `compileUnits` is on the stack, but `runPhases$1` has not been entered yet, so its first invocation already runs the new body. One class, `Implicits$ImplicitSearch`, was loaded but not picked up by `installOn`'s retransformation pass. It is probably loaded while another class is being transformed, where Byte Buddy's circularity lock skips it, but I have not confirmed this. `verify` now retransforms any target that is still untransformed.

The Name hashes are lost: adding `hashCode` to a loaded class is a schema change. The cost is visible. The corpus differs from the fork by 0 to 2636 lines, and `dotc/util` by 546k lines. Without content hashes, implicit candidate order follows identity hashes, so the self-attached run tries 25 more candidates (470 vs 445 Candidate events, +50 Typed) and every later seq shifts. So self-attach is fine for observation-only tooling on a released compiler. Reproducible streams need either `-javaagent` or the hash fix in the compiler.

## Hooks the fork does not have

Both are agent options, so the acceptance diff stays clean when they are off (rerun after adding them: still byte-identical). Their advice follows the same rules (no allocation, observer read once). Every `.symbol` and every printing step is deferred to the phase-end flush.

**`rejected`: implicit scope, ranking and dropped candidates.**

- **`Scope`** (enter/exit, one per `searchImplicit(eligible, contextual)`). Payload: `scope` (`contextual` or `implicitScope`), `eligible` (type ids, in rank order), `levels`, `result`, and `ambiguous` (the two alternatives of an `AmbiguousImplicits`). The fork's `Candidate` events nest inside it.
- **`Rejected`** leaves. Payload: `ref`, `reason`, `than`, `level`, `tried`. The reasons:
  - `notEligible`: `filterMatching`'s `candidateKind` returned None (wrong shape or inaccessible).
  - `comesTooLate`: dropped by `comesTooLate`.
  - `lowerPriorityThan`: dropped by `rank` after a success. The agent sees this by comparing successive `rank` pending lists.
  - `notBetterThanAmbiguous`: dropped by the same comparison while an ambiguity was being healed.
  - `notTried`: never reached.
  - `lostTo`: tried and succeeded, but `disambiguate` kept another candidate (`tried: true`).
- **Failures of tried candidates** keep the fork's `Candidate.reason`, which is the `SearchFailureType` class name. `AmbiguousImplicits`, `DivergingImplicit`, `ShadowedImplicit` and `NoMatchingImplicits` map one-to-one onto the requested vocabulary. The ambiguity partners are in the enclosing `Scope.ambiguous`.

On `Givens.scala` this shows directly why `pickLocal` resolves as it does. The `render` search for `Show[Int]` (seq 399) has a contextual Scope with nothing eligible, which fails. It is followed by an `implicitScope` Scope where `Show.given_Show_Int` succeeds. The `pickLocal` search (seq 632) has a contextual Scope whose only eligible entry is the local `given_Show_Int`, which succeeds, and there is **no** implicit-scope Scope. So the global given never competes in `pickLocal`. dotc consults the implicit scope only when the contextual search fails, so "global loses to local" is not a ranking decision that can be observed. The stream shows the decision dotc actually makes: the contextual search succeeded, so the implicit scope was skipped. Showing the global given as a candidate there would require running a search dotc does not run, which would change the compile. In the same file, 208 `notEligible` rejections were recorded, mostly Predef conversions and the companion givens of the wrong type.

`agent/test/Ranking.scala` exercises the remaining paths:
- `summon[Tc[Int]]`: `general` and `specific` are both tried, and `general` is recorded as `lostTo` `specific`.
- An ambiguous `summon[Tc[String]]`: `a1` and `a2` are both accepted, the Scope records `AmbiguousImplicits` with both alternatives, and `Lib.general` is recorded as `notBetterThanAmbiguous`.

On `dotc/util` the extras add 12055 Rejected events (11832 notEligible, 206 notTried, 17 lostTo) and 867 Scopes, and the fork's event counts are unchanged.

Gaps:
- Eligibility is cached per context and type, so `notEligible` appears once, at the first computation, and sometimes before the search's `Scope`, not inside it.
- The `ignored` filter and `extensionOnly` masking are not hooked.
- `scope` distinguishes contextual from implicit scope, and nesting `level`, but not local from imported.

**`tentative`: tryEither and buffered reports.**

- **`Tentative`** events wrap `Typer.tryEither`. The outcome is `kept` if the fresh TyperState it creates was committed (in tryEither's own commit, or by a fallback such as `tryAlternatively`'s `failedState.commit()`), otherwise `discarded`, or `aborted` on an exception. A kept Tentative with nothing inside is dropped, like a no-op Adapt.
- **Reports in tentative typing.** The fork's `Reporter.report` hook never sees diagnostics reported inside tentative typing: a TyperState's `StoreReporter(fromTyperState = true)` overrides `report` and does not call `super`. The agent hooks that branch and emits `Report` with `buffered: true`, plus `discarded: true` when the enclosing state was dropped.

On `dotc/util`: 1634 kept and 110 discarded Tentatives, with 116 buffered reports, 110 of them discarded. That is the class of `<error …>` types the UI evaluation found in a successful compile.

**Perturbation.** With the extras on, `stats.perturbations` (hooks that fired while the recorder was suspended) rises from 6 to 671 on `util`. The cause is the same as the fork's 6: type printing at phase end forces completions, which run typer code. More hook kinds now count it. The fork's event counts and skeleton are unchanged, so the extras don't change what the fork's hooks record.

## Recommendation: upstream vs agent

My criterion is how hard the site is to hook *from outside*. Sites where the agent had to reconstruct the compiler's control flow should be upstream. Stable method-level sites, and chatty or internal ones, can stay in the agent.

- **Upstream:**
  - **Phase boundaries.** There is no method to advise. The agent depends on a mangled local (`runPhases$1`) and a call-site substitution with a helper the compiler's loader must see. This is the strongest case.
  - **`Typer.typed`.** The hook belongs inside the `else` branch, and the agent duplicates two guards to fake that.
  - **`Completer.complete`.** It needs a marker and a Context stash.
  - **`inferImplicit` and a per-candidate hook.** `inferImplicit` is method-level and easy. The candidate hook needs a marker across `tryImplicit` → `typedImplicit` to exclude ImportSuggestions.
  - **A `ClassNode`-complete callback.** This does not exist yet and I did not try it.

  I agree with the working list. I would also add one thing: a per-candidate **verdict** at the ranking level (pruned, lost to, ambiguous with). It is the most useful hook for the UI, and in the agent it depends on the most fragile names in the table: `rank$1`, `candidateKind$1`, `comesTooLate$1` and the field `x$5`. As a concept it is stable even if the ranking code changes.

  Separately, `Reporter.report` as the fork has it misses TyperState-buffered diagnostics. An upstream report hook should either fire there too, with a flag, or say in its contract that it doesn't.
- **Agent-only:**
  - `adapt`/`adapt1` (chatty, internal).
  - `TreeCopier.finalize` (hot, internal, and stock has no Context).
  - `Reporter.report` itself (a stable method-level site).
  - `searchImplicit`/`rank` details beyond the verdict.
  - `tryEither`/`TyperState.commit`.
  - `MegaPhase.transformNode` and `genLoad`, if they are added later.
- **Not hooks:** the `TypeApply` copier fix and the Name content hashes should be ordinary compiler changes, each justified on its own terms. The Name hashes fix run-to-run nondeterminism. The TypeApply fix stops unchanged trees being copied in every MegaPhase. Both can be patched in by an agent, and this experiment does that. The Name one only works with `-javaagent`, and needed a pinned list of anonymous enum-case classes. Moving it upstream removes the most fragile rows of the table.

## Files

- `agent/build.sh`: builds `agent/target/replay-agent{,-api}.jar` against `classes-stock`.
- `agent/compare.sh`: the acceptance diff. Outputs go to `out-fork/` and `out-agent[-opts]/`.
- `agent/src/api/`: `Observer`, `Hooks`.
- `agent/src/agent/`: `ReplayAgent`, `JoinPoints`, `Advices`, `RunHook`.
- `agent/bench/`: `bench.sh`, `Loop.java`, `AllocCheck.java`. Results are in `agent/bench-results.txt` and `agent/alloc-check.txt`.
- `agent/events.py`: prints the implicit and tentative event tree of a stream.
- `agent/test/Ranking.scala`.
- `plugin/src-fork/`, `plugin/src-agent/`, `plugin/src/Observer.scala`.
- `classes-fork/` and `classes-stock/`: compiler snapshots (not committed). `classes-stock` is `compiler/src` at `ae374e9583` built with sbt in this worktree, plus the same `compiler.properties` as the fork.
