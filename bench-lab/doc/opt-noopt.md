# The compiler with and without its own optimizer

## Question

The build compiles the compiler with `-opt -opt-inline:dotty.**` and the library with `-opt -opt-inline:**,!java.**` (Scala 2's optimizer: Scala-level inlining, closure and box elimination). What does that buy in compile time today, on a modern C2, and where? Scala-level inlining can help (fewer megamorphic call sites, eliminated closures and boxes) or hurt (bigger methods past C2's `FreqInlineSize`/`MaxInlineSize` and `InlineSmallCode` limits, more code cache, more L1I pressure; see scala-opt-ab's `Using.resources` result, where the optimized method was 18% slower).

## Variants

Two worktrees at the same commit (bench-lab-perflab, 0c8314024c), each with its own sbt server (JDK 21.0.12-tem):

- `opt`: `compiler-perf-testing-plan-14aa8c`, the default build.
- `noopt`: `compiler-perf-testing-plan-14aa8c-noopt`, the sbt server started with `SCALA3_BUILD_OPTIMIZE=false`, which drops the optimizer options from `scala3-compiler-bootstrapped`, `scala-library-{non,}bootstrapped` and `scala2-library`. Checked with `show Global/optimize` and `show scala3-compiler-bootstrapped/Compile/compile/scalacOptions` in each server.

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

## Status

- DONE Both trees, servers and the toggle checked; both built (`scala3-bench-lab/Jmh/compile`). 5257 of 5400 compiler class files differ between the builds.
- DONE Smoke runs (`ab/smoke-opt-noopt*.json`: dottyUtil, 1 fork, 5+3 iterations; `async:cpu`, `xctrace:ipc`, kperf `cycles+instructions`): both trees run, cells gathered, report, flame graphs and `profdiff` work. Two perflab fixes were needed (jvm-perf-lab, uncommitted): `perflab trees` lost the other variants from the report when a tree is the home checkout, and JMH's `"NaN"` score error crashed the summary and index.
- Smoke numbers, loaded machine, not to be trusted: noopt +18.5% instructions/op and +13% cycles/op on the bench thread (xctrace), ms/op within noise. `profdiff` already shows the inlining shadow: `Symbols$.toDenot`, `EqHashMap.index`, `Period$.firstPhaseId$extension` have samples only in noopt.
- TODO `perflab analyze`'s phase table attributes 56% of samples to "other phases" and ~0% to megaphases; fix `bench-lab/perflab-phases.toml` before relying on the phase split.
- TODO Output equivalence (`OutputDigest`, `samebytes.py`).
- TODO Campaigns 1 and 2, on a quiet machine.
- TODO Analysis and write-up.
