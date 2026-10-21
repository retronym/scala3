# The compiler with and without its own optimizer

## Question

The build compiles the compiler with `-opt -opt-inline:dotty.**` and the library with `-opt -opt-inline:**,!java.**` (Scala 2's optimizer: Scala-level inlining, closure and box elimination). What does that buy in compile time today, on a modern C2, and where? Scala-level inlining can help (fewer megamorphic call sites, eliminated closures and boxes) or hurt (bigger methods past C2's `FreqInlineSize`/`MaxInlineSize` and `InlineSmallCode` limits, more code cache, more L1I pressure; see scala-opt-ab's `Using.resources` result, where the optimized method was 18% slower).

## Variants

Two worktrees at the same commit (bench-lab-perflab, 0c8314024c), each with its own sbt server (JDK 21.0.12-tem):

- `opt`: `compiler-perf-testing-plan-14aa8c`, the default build.
- `noopt`: `compiler-perf-testing-plan-14aa8c-noopt`, the sbt server started with `SCALA3_BUILD_OPTIMIZE=false`, which drops the optimizer options from `scala3-compiler-bootstrapped`, `scala-library-{non,}bootstrapped` and `scala2-library`. The env var only reaches the server that was started with it, and sbt servers here stop after 10 idle minutes (`~/.sbt/1.0/global.sbt`), so a later `sbt --client` started a fresh server with the optimizer on. The noopt tree therefore also has an untracked `noopt.sbt` with `Global / Build.optimize := false`, which every server reads. Checked with `show Global/optimize` and `show scala3-compiler-bootstrapped/Compile/compile/scalacOptions` in each server.

The benchmark JVM is the patched JDK 26 for both. The corpora are compiled against each build's own library jar; dotc reads it as TASTy, so the corpus input is the same.

Sanity check: both builds must emit the same bytes for the corpora (`OutputDigest` in each tree, then `samebytes.py`). The compiler's own optimizer must not change what it compiles.

## Campaigns

`perflab trees` drives both trees round by round, variants alternating, results gathered into `opt`'s `out/ab`. The variants are different builds, so they cannot be paired within a fork; cross-fork noise is 0.5-1% (one fork in five ~3% off), so this resolves effects of a few percent, which is what's expected here.

1. `ab/opt-noopt.json`: timing and counters. `xctrace:ipc`, re2s and scalaz, 6 rounds, loadgate 0.8 cores. Reports ms/op, instructions and cycles per op on the bench thread, IPC, `gc.alloc.rate.norm`, and `jit.*` (a steady state should have no compilations while measuring). Caveat: without `-Yno-parallel-pickling` (not on this branch) the pickler's thread (~12% of cycles on re2s) is outside xctrace's bench-thread counts; ms/op still includes it.
2. `ab/prof-opt-noopt.json`: profiles. async-profiler `cycles+instructions` through kperf, scalaz, 12+12 iterations (~1 min each), 2 rounds so a fork's JIT luck shows up.
3. Optional, if 1 shows a cycles difference larger than the instructions difference: the same as 1 with `xctrace:frontend` (L1I and branch misses), for the code-size hypothesis.

## Analysis

- Headline: per corpus, opt vs noopt in ms/op, instructions/op, cycles/op, IPC and allocation, medians over rounds and per-fork values (`perflab ab report`).
- Phase split: `perflab analyze` on each variant's profile, the share of each dotc phase. Robust to inlining, since phases are anchored on frames neither build inlines.
- Differential: `perflab profdiff <opt cell> <noopt cell>` for the diff flame graph and the methods whose self cycles per op changed most.
- Inlining-aware attribution. In `opt` the methods the optimizer inlined have no frames of their own (C2's inlining keeps frames, Scala's does not), so their cost shows as their callers' self time, and a naive self-time diff reports it as a regression in the caller. To compare like for like: take the methods with samples in `noopt` and none in `opt` (the inlined set; to be cross-checked against the opt build's bytecode), fold them into their callers in the `noopt` stacks, then diff self time. A script for this goes in `bench-lab/scripts/` if `profdiff` cannot do it.
- JIT side: `jit.*` from `JitProfiler`, and if the profiles point at it, a `-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` run of each to find hot methods that C2 refuses to inline in `opt` ("hot method too big") but inlines in `noopt`, and the code cache size at the end.

## Findings (re2s unless noted; one fork per cell, so a few percent is the resolution)

- **Speed, no profiler, 150 s warmup** (`warm-noprof-opt-noopt`): localopt 222.8, noopt 229.7, opt 235.9 ms/op. The local optimizations help (-3% against noopt); the inliner costs more than that (+5.9% over localopt, +2.7% over noopt). Shorter warmups (~50 s; `jit-opt-noopt`, `jit3-…`) agree: noopt 230-231 against opt 244-248 on re2s; scalaz opt +2.5% (`jit-opt-noopt`), localopt level with noopt (`jit3`, one round).
- **Cold, one compile per JVM** (`cold-opt-noopt`, 2 × 8 forks): no clear sign (opt 741 vs noopt 759 ms on re2s, rounds overlap).
- **The async profile is biased between builds.** kperf `cycles+instructions` with jfrsync costs noopt +13.5% and localopt +14.6% but opt only +7%, enough to reverse the order: profiled, opt is fastest. Not the profile's JVM flags: `-XX:+PreserveFramePointer -XX:+DebugNonSafepoints` alone (`warm-fpflags-opt-noopt`) cost noopt nothing and opt ~4%. Use profiles for attribution within a build, not for totals across builds. Not yet known: whether jfrsync or the kperf sampling carries the bias.
- **JIT work** (`-XX:+CITime`, whole fork): opt gives C2 8% less bytecode but costs 8-12% more C2 time; C2 throughput drops 15-18% (re2s 227 → 192 KB/s; scalaz 210 → 171). localopt sits between (re2s C2 230 → 218 → 201 KB/s for noopt → localopt → opt). nmethod code within ±3%. It is bigger compile units, not more code.
- **Bytecode**: opt 3.38 MB, noopt 3.07 MB, localopt 2.89 MB across 84,736 methods (same set). 382 methods cross `FreqInlineSize` (325 B) upward in opt. 75 trait static forwarders (`X$`, 8 B) take the whole body (+22 KB), e.g. `Applications.typedApply$` 8 → 2603 B, so a callee C2 always inlined becomes a separately compiled method: `Typer.typedApply → typedApply$` is inlined in noopt and a call in opt (274M cycles/op through it).
- **Stabilization** (`stabilize-opt-noopt`, 3 forks, profiled): plateau (within 5% of the last minute) at 20/20/20 s for noopt, 20/40/90 s for opt. C2 never goes quiet in either (~3 compiles/s after the first 10 s, up to 200 s).
- **Unrelated to -opt**: `Positioned.span` runs as C1 (tier 1, trivial) code called through the vtable from `include$1` (megamorphic profile), ~0.8% of cycles in every build; investigated in its own session (`claude/positioned-span-final`).
- The forks run with `-XX:MaxInlineLevel=35` from scala3's `.jvmopts` via sbt `javaOptions`, not the JDK default.

## Tools

- `bench-lab/ab/*opt-noopt*.json`, `jit3-…`, `warm-…`, `stabilize-…`, `cold-…`: the campaign specs.
- Scratchpad scripts used for the analysis (to move into perflab if kept): an inline-tree viewer (compiled units of N async runs against a baseline, how each callee was reached: inlined, called, both), a per-method bytecode-size comparison against HotSpot's thresholds, a stabilization analysis from `iterations.tsv` and jfrsync's `jvm-events.tsv`.

## Status

- DONE Trees (opt, noopt, localopt), builds, smoke runs, perflab fixes (`trees` spec, NaN error, campaign descriptions panel; pushed to jvm-perf-lab).
- DONE Timing, cold, JIT totals, profiles, profiler-bias controls, stabilization.
- TODO Output equivalence (`OutputDigest`, `samebytes.py`).
- TODO Which part of the async profile carries the cross-build bias (jfrsync or kperf sampling).
- TODO More forks for the headline (localopt < noopt < opt) before quoting it outside this lab.
- TODO A build that keeps `-opt` but excludes forwarders/`$anonfun` bodies from inlining, if the inliner is worth keeping at all.
