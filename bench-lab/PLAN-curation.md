# Curating the compiler labs: bench-lab (Scala 3) and compiler-lab (Scala 2)

## Problem

Both labs sit on long branches of Jason's forks that mix three things: the lab harness, compiler changes worth upstreaming (cost per definition), and experiment leftovers (campaign specs for goForward, Positioned and kperf, plus a goForward benchmark and code generators). Scala 3's branch also carries the history of tooling that has since moved to jvm-perf-lab. So nobody can review or share a piece without taking the whole stack.

| | branch | base | ahead | behind base | net diff |
|---|---|---|---|---|---|
| Scala 3 | `bench-lab-perflab` (= `bench-lab-harness` + the perflab port) | scala/scala3 `main` @ 88be2f56a1 | 45 | 34 | +2755 / −43, 73 files |
| Scala 2 | `compiler-lab-builds` | scala/scala `2.13.x` @ 339909af8f | 12 | 49 | +1598 / −9, 26 files |

Scala 3's first commit alone adds 8.7k lines. The port (0c8314024c) then deletes 9k: `scripts/*.py`, the Java profilers, the audit agent, `templates/*.xml` and `project/BenchLab.scala`. Of 45 commits, 7 change the compiler.

Goal: each repo ends up with a few upstreamable compiler commits and one lab commit on top, and the two labs look alike.

## Key decisions (proposed)

1. **Squash the lab to one commit per repo.** The lab's history is mostly tooling that now lives in jvm-perf-lab, so it records nothing the final tree needs. Measurement rationale that is still worth keeping (for example why single-shot runs use 8+8 iterations) goes into the docs, not the log.
2. **Compiler changes go first, as their own commits, in the form they'd take upstream.** That way the lab commit can be dropped and the rest still stands, and the same commits become the upstream PRs.
3. **Campaign specs leave the lab, except for one or two examples.** Most specs name flags that only exist on an experiment branch (`-Ddotty.primeSpans`, goForward variants), so they belong with that experiment. The rest go to an archive tag. Results already live in `out/` (gitignored).
4. **Keep the lab in the compiler repo for now, but move its build wiring into the lab directory.** Reasons are under "Minimizing the footprint".
5. **Don't rewrite the old branches; tag them.** `bench-lab-harness` and `compiler-lab-builds` become `archive/…` tags, so branches stacked on them keep a base to rebase from.

## Scala 3: `bench-lab-perflab`

### Commits

| commits | what | classification | fate |
|---|---|---|---|
| e9be5bc761 | initial harness, 8.7k lines | lab, ~6.6k of it deleted later by the port | squash into lab; the deleted parts just disappear |
| e9be5bc761 (Build.scala part) | `optimize` key / `SCALA3_BUILD_OPTIMIZE=false` builds library and compiler without `-opt` | build change with merit of its own (faster local builds; the "compiler with/without its own inlining" experiment) | own commit; upstream PR |
| 9cf5353a1a 0164c5bb49 4533abbe03 | scalaLibrary corpus | lab | squash |
| 259654253e a0ae622763 db769ebe69 ffac8d198a | OutputDigest, `samebytes.py` | lab | squash; port to Scala 2 |
| 988df4f628 a377107c8c 6ca854b32f, and from e9be: `GoForwardBenchmark`, `ab/*goforward*`, `ab/denot-prev.json`, `scripts/gen_goforward_*.py` (marked DO NOT MERGE) | goForward / denotation experiment | experiment | drop from lab; move to the goForward experiment branch |
| c25c00c118 c61536b3c9 b57ede8fab 4ec7442176 3d25d848ca 0d210e4e89 332d979602 326000bf64 58a9aead8b de2fa56577 | ab.py, xctrace, abindex, ByThread, live index | tooling now in jvm-perf-lab | drop (history only) |
| 83ff583b58 83c2a3d5e9 d646eeea80 | paired A/B: `Variants`/`IterationLog` (moved) plus `CompileBenchmark.variants` (stays) | mostly moved | squash what's left |
| 387ab53b98 | caller-audit agent (moved), `BenchLab.scala` audit task (deleted), `audit/Predicates.scala` | mostly moved | drop the agent; `Predicates` and its spec go with Positioned |
| 1debe844e5 7a4c071e98, `ab/positioned-*.json`, `audit/positioned-envelope.audit`, `audit/Predicates.scala` | Positioned span priming (retronym/scala3#15) | experiment | move to `claude/positioned-bench` |
| `ab/prof-*`, `exp12*`, `kperf-corpora`, `replace-direct`, `stack-27296`, `prof-27296` | historical campaigns | history | archive tag; keep `paired-aa.json` and `def-costs-overhead.json` as examples |
| aa2618074f c0a1700870 48dd012412 | README A/B sections; `doc/paired-ab.md`, `doc/caller-audit.md` | lab docs that overlap jvm-perf-lab's `doc/paired-ab.md` and `doc/caller-audit.md` | squash, cut to the compiler-specific parts (the positioned-serial evidence becomes a worked example in jvm-perf-lab), link the rest |
| c6faa9913a | `CLAUDE.md` | lab | squash, reduced to a pointer to jvm-perf-lab's AGENTS.md |
| 72ebcb296d | `doc/def-costs.md` | design of the compiler change | turns into the upstream PR description; the lab keeps a usage section only |
| 357d488d1d 1a95a7e1b1 f8be6f863a f6e98b2830 7c30167da7 61a4447d22 4af89dcaaf | DefCosts, `-Yprofile-defs[-listener]`, hooks in Typer, Namer, Recheck, MegaPhase, MacroTransform, Inlining, SymbolLoaders, `ProfiledThreadPool`, `DefCostsTest` | upstream candidate | squash into one commit, "Cost per definition" |
| 054cc01abc | `Uniques.CostedResize`, `ContextBase.costUniques` | upstream candidate, but touches core hash-consing | own commit after the above; same PR or a follow-up |
| f5dacb4284 | `SpanCostListener` (its vendored `Span.java` was deleted by the port) | lab | squash |
| d33542b700 541e1181ec 19d1983512 | span panel, `span-config.json` | lab | squash |
| 0c8314024c | the port to perflab | lab | squash |

### Changed files outside `bench-lab/`

| file | classification |
|---|---|
| `compiler/src/.../profile/{DefCosts,Profiler,ProfiledThreadPool}.scala`, `config/ScalaSettings.scala`, `typer/{Typer,Namer}.scala`, `transform/{MegaPhase,MacroTransform,Inlining,Recheck}.scala`, `core/SymbolLoaders.scala`, `compiler/test/.../DefCostsTest.scala` | upstream: cost per definition |
| `core/{Uniques,Contexts}.scala` | upstream: rehash frames |
| `project/Build.scala`: `optimize` key and `optimizeOptions` | upstream: build |
| `project/Build.scala`: `scala3-bench-lab` project, `build.sbt` line, `project/plugins.sbt` sbt-jmh | lab |
| `perflab.toml` | lab |
| `.gitignore` (`__pycache__/`) | lab; redundant with `bench-lab/.gitignore`, so drop it |

There are no TEMP compiler changes on this branch. The experiment-specific compiler changes (`-Yno-prime-spans`, goForward) live on their own branches.

### Final shape (on current `main`)

1. **Build: optional `-opt` for the library and compiler** (`SCALA3_BUILD_OPTIMIZE`, `Global / optimize`). About +25 / −8 in `project/Build.scala`.
2. **Cost per definition: `-Yprofile-defs`, `-Yprofile-defs-listener`.** `DefCosts`, the `Profiler` hooks, and the hooks in typer, namer, recheck, transforms and loaders, plus `DefCostsTest`. About +470 / −30.
3. **Cost per definition: hash-consing table growth as a frame of its own.** About +45 / −6.
4. **bench-lab.** `CompileBenchmark`, `ParserBenchmark`, `PicklerBenchmark`, `JavacBenchmark`, `InMemory*`, `Corpus`, `OutputDigest`, `SpanCostListener`, `perflab.toml`, `perflab-phases.toml`, `span-config.json`, two example specs, README and `doc/def-costs.md` (usage), and the build wiring. About 1.1k lines, down from 2.1k in `bench-lab/` today.

Experiment branches (Positioned, goForward, scalaLibrary patmat) rebase onto commit 4 and carry their own specs.

## Scala 2: `compiler-lab-builds`

### Commits

| commits | what | classification | fate |
|---|---|---|---|
| 9787847966 | `doc/design.md` with plan | lab | squash; move the DONE/TODO plan out (to a PLAN file, or drop it once done) |
| b505a27356 98596f679c 29db00be01 f297976f82 | sbt project, `CompileBenchmark`, `InMemory*`, `Corpus`, `perflab.toml`, README, audit spec | lab | squash |
| 3b8cef375b | DefCosts, `-Yprofile-defs[-listener]`, Namer completion hook, `CostProfiler`, `DefCostsTest` | upstream candidate | own commit; drop its stray re-indent of `YpatmatExhaustdepth` in `ScalaSettings.scala` |
| a5ed3b3666 | `SpanCostListener` plus vendored `one/profiler/{Span,Recording}.java` (233 lines) | lab plus duplication | squash; depend on `perflab-spans` instead of vendoring |
| a917ea9db5 | cost-per-definition docs, overhead campaign | lab | squash; the campaign is an example spec |
| 96cd94936a 477b122c24 | `jdkHome` (another JDK's API, including SDKMAN names) | lab | squash |
| 696f979c09 6a2f747546 | `@build=` variants (`IsolatedBuild`), code-cache sizing, `builds-aa.json` | lab | squash; candidate to generalize in jvm-perf-lab (below) |
| (beyond) `compiler-lab-multi-global` 3cdaa264a3 f6cc3dd592 58b4977675 | `globalClasses` | experiment | stays a branch on top of the curated lab |
| `campaigns/typer-global-builds*.json` | cake / multi-global experiment | experiment | move to that experiment's branch |

Stale branches to delete after approval: `compiler-lab-step5`, `claude/compiler-lab-601e68`, and `origin/compiler-lab` (at a917ea9db5). Reuse the `compiler-lab` name for the curated branch.

### Changed files outside `test/compiler-lab/`

| file | classification |
|---|---|
| `src/compiler/.../profile/{DefCosts,Profiler,ThreadPoolFactory}.scala`, `Global.scala`, `settings/ScalaSettings.scala`, `typechecker/Namers.scala`, `test/junit/.../DefCostsTest.scala` | upstream: cost per definition |
| `build.sbt` (`compilerLab`, `perflabVersion`) | lab |
| `perflab.toml` | lab |

### Final shape (on current `2.13.x`)

1. **Cost per definition: `-Yprofile-defs`, `-Yprofile-defs-listener`.** About +520 / −8.
2. **compiler-lab.** About 800 lines once the vendored Java and the experiment campaigns are gone.

## Upstream PRs, in order

1. **scala/scala: cost per definition.** This is the smaller diff: one new hook, Namer completion, with everything else riding on hooks `-Yprofile-trace` already has. It settles the option names and the TSV format that `perflab defcosts` reads, and Jason knows the scala/scala reviewers. Before opening: run the "options off, against the branch point" measurement that `doc/design.md` lists as TODO.
2. **scala/scala3: optional `-opt` in the build.** Tiny and independent; can go any time.
3. **scala/scala3: cost per definition.** Same option names and format as in step 1. The `doc/def-costs.md` content becomes the PR description. Bring the overhead numbers from `def-costs-overhead*.json`, with the options both off and on. Expect review on two points: `Profiler`'s inline methods gaining `using Context`, and `onCompletion` gaining a `CostKind`.
4. **scala/scala3: rehash frames.** Separate, because it changes `Uniques`, which core reviewers will look at differently. It could be folded into step 3 if reviewers prefer.
5. **Later, maybe: the labs themselves.** This waits until perflab is published (JitPack tag or Maven Central). A `mavenLocal` snapshot dependency rules out upstream, and makes the forks hard to share too.

Whether `-Yprofile-defs-listener` should load a class named by an option is a question for review. There's precedent in `-Xplugin` and `-Yprofile-external-tool`. If reviewers push back, the fallback is to upstream `-Yprofile-defs` alone and keep the listener on the fork.

## Alignment between the labs

| aspect | Scala 3 today | Scala 2 today | proposal |
|---|---|---|---|
| directory / project / package | `bench-lab/`, `scala3-bench-lab`, `dotty.tools.benchlab` | `test/compiler-lab/`, `compilerLab`, `scala.tools.nsc.compilerlab` | keep each repo's location idiom, but use one name. Rename Scala 3 to `compiler-lab` during the squash, since every downstream branch rebases then anyway (open question) |
| campaign specs | `ab/` | `campaigns/` | `campaigns/` in both |
| `perflab.toml` `[paths]` | `out/bench-lab/runs`, `out/ab` | `out/compiler-lab/runs`, `out/compiler-lab/ab` | `out/<lab>/runs`, `out/<lab>/ab` in both |
| reuse parameter | `context=fresh\|shared` | `global=fresh\|resident` | one name and the same values (`fresh\|resident`) |
| docs | README holds both the design and how to run; `doc/{def-costs,paired-ab,caller-audit}.md` | README (how to run) plus `doc/design.md` (design and plan) | both: README for running, `doc/design.md` for the why, cost per definition as a README section; generic material only in jvm-perf-lab |
| `SpanCostListener` | sampled only | sampled, plus `$All` for kperf | add `$All` to Scala 3. Better: the async-profiler kperf fix makes `$All` unnecessary in both |
| span library | `perflab-spans` | vendored `one.profiler.{Span,Recording}` | `perflab-spans` in both |
| `span-config.json` | yes | no | add one to Scala 2 (`implicit` and `macro` markers, no rehash tables) |
| `perflab-phases.toml` (`perflab analyze`) | yes | no | add one for scalac's phases |
| OutputDigest / `samebytes.py` | yes | listed as future work | port to Scala 2 (no TASTy header to mask) |
| comparing builds within a fork | `perflab trees` only | `@build=` / `IsolatedBuild` | Scala 3 would want it too, but the bootstrapped library makes sharing harder; see jvm-perf-lab below |
| `jdkHome` | no | yes | port only if a Scala 3 experiment needs it |
| `CLAUDE.md` | yes | no | both, as a pointer to jvm-perf-lab's AGENTS.md (or neither) |
| DefCosts API | `enter` returns the frame, `exit(frame)` | `enter(kind, key, phase)`, `exit(kind, key)` | keep each, since they follow each compiler's `Profiler`. Define the shared output format (kinds, columns) once, in jvm-perf-lab's `defcosts` docs |
| build wiring | settings in `project/Build.scala` (~45 lines) | settings in `build.sbt` (~30 lines) | both in `<lab>/build.sbt` (below) |

### Still duplicated that belongs in jvm-perf-lab

- Scala 2's vendored `Span.java` and `Recording.java`: replace them with `perflab-spans`.
- `perflab.toml` boilerplate both labs repeat: the `[agent] jar` path with its hard-coded version, `[index] flags`, and `[benchmark] corpus/variants` names. perflab should default these, so each toml shrinks to `[build]`, `[paths]` and lab-specific config.
- `IsolatedBuild`'s generic part: a variant that names a class-loader root, a shared parent loader, a check that shared classes are byte-identical, and the code-cache advice. perflab-jmh could offer this, with each lab supplying which directories and which classes must be shared.
- Scala 3's `doc/paired-ab.md` and `doc/caller-audit.md`: generic design that jvm-perf-lab's docs of the same names already cover. Move the Positioned evidence there as a worked example.
- The pattern in `SpanCostListener` (start, `hasSamplesSince`, emit with a label) could be a 10-line helper in perflab-spans. Optional.

## Minimizing the footprint

- **Wiring inside the lab directory.** sbt loads `<subproject>/build.sbt` for a project the root declares, and those files can use `Build.commonBootstrappedSettings` and the other projects. In Scala 3, the root declaration plus `.dependsOn(...)` go in one line of `build.sbt`, and the JMH settings, perflab dependencies and scalaLibrary-corpus options go in `bench-lab/build.sbt`. Scala 2 works the same way. Outside the lab directory, the lab commit then touches only `build.sbt` (1–3 lines), `project/plugins.sbt` (Scala 3 only, sbt-jmh) and `perflab.toml`. So rebasing onto a new `main` stops conflicting in `Build.scala`. This needs a quick check that `--client` and the IDE exclusions behave.
- **Does the lab need to be in the repo?** Scala 3's bench-lab, once goForward is gone, uses only public or protected API: it subclasses `ContextBase` with an overridden `newPlatform`, and uses `classpath.*`, `TreePickler` and `tpd`. So no `private[dotc]`. Scala 2 needs `instanceSettings` and the quick classes. An external build could reach both through `ProjectRef(file(<checkout>), …)`, and a single repo holding both labs would make alignment trivial. Not yet, though: the lab has to change in step with compiler hooks (the CostListener API) until those are upstream, experiment branches add benchmarks next to the change they measure, and two sbt servers per experiment is friction. Revisit once cost per definition is merged upstream.
- **Specs and results out of the repo**, as in decision 3. Results are already outside git.
- **`perflab.toml` at the repo root** stays, because perflab finds it there. It could move to the lab directory if perflab learns to look there (`perflab --config`, or a search path).

## Doing it, once approved

1. Tag `archive/bench-lab-harness` and `archive/compiler-lab-builds`.
2. Scala 3: on a new branch from current `main`, build commits 1–3 from the compiler diffs, then add commit 4 from the final `bench-lab/` tree minus what moves out. Check that it compiles, `DefCostsTest` passes, and `perflab run` works on `small`. Do the same for Scala 2.
3. Move the experiment specs and benchmarks to their branches (Positioned, goForward, multi-global) and rebase those onto the curated lab.
4. Make the alignment changes as follow-up commits on the lab: renames, `perflab-spans` in Scala 2, `campaigns/`, docs. The same goes for the jvm-perf-lab items, in that repo.
5. Jason force-pushes, and moves draft PR retronym/scala3#14 (still pointing at `claude/scala3-counters-lab-dc0f98`).

## Risks and open questions

- **Downstream branches.** `claude/positioned-bench`, `claude/scalalib-bench-lab-d2e828`, the goForward work and `compiler-lab-multi-global` are stacked on the old lab. Each needs a `rebase --onto`, and its specs must still find their flags. The archive tags keep the old base reachable.
- **Squashing loses the "why" behind measurement choices.** Before squashing, sweep the old commit messages for rationale and put it in the docs.
- **perflab is a `mavenLocal` snapshot.** Nobody else can build either lab until jvm-perf-lab publishes a tagged version, so that is the real blocker to sharing. The span and kperf features also need retronym/async-profiler's integration branch.
- **Upstream overhead evidence.** Both PRs need numbers with the options off. Scala 2's are still TODO, and Scala 3's exist only in campaign specs that this plan moves out of the lab. Record the results in the PR, not the spec.
- **Rename `bench-lab` → `compiler-lab` in Scala 3?** It makes the labs look alike, but it touches every downstream branch and memory note. My recommendation is yes, during the squash, since the cost is paid then anyway.
- **Keep or drop `JavacBenchmark` and `scripts/compare.py`** (the dotc-vs-javac re2s comparison)? `compare.py` reads like a one-off analysis, so I'd drop it and keep `JavacBenchmark` as a yardstick.
- **Where do archived specs live:** a tag on the fork, or a notes repo next to jvm-perf-lab? A tag is cheapest.
- **Do both labs belong in one external repo eventually?** Revisit after the upstream PRs land.
