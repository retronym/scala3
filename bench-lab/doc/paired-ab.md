# Paired A/B within one fork

## Problem

`ab.py` compares variants across forks. Each fork compiles the compiler's hot code differently: forks of one variant differ by 0.5–1% in instructions per op, and one in five re2s forks was 3% off. A change worth 0.5% needs many rounds to show through that, although the variants differ only in a compiler setting that could be switched within one JVM.

## Key decisions

1. **Variants alternate by iteration within one fork.** A, B, C, A, B, C… through warmup and measurement, so every variant runs on the same compiled code and the fork's JIT luck cancels in their difference. Several forks still run, to sample the JIT's variation; the statistic is the spread of the paired differences across forks, not of absolute values.

2. **Variants are per-run compiler settings.** `CompileBenchmark` takes `variants="<name>=<scalacOptions>|…"` and rebuilds its compiler for the next variant at each iteration. Since each op compiles from scratch, this costs nothing extra. Anything compared this way must be a setting read per run (a `-Y` option), not a system property read once per JVM. Variants that need different JVM flags or builds stay with `ab.py` and `abtrees.py`.

3. **Counters are split by time, not by restarting xctrace.** The rows of an xctrace counters export carry their sample time, relative to the trace's start, and the trace records its start date to the millisecond. The fork records each iteration's start and end, and its variant, as wall-clock instants; `XctraceProfiler` assigns each sample to the iteration it falls in and reports per-variant counts (`xctrace.INST_ALL.<variant>` per op). With 5–10 s iterations, a millisecond of misalignment is well under 0.1% of an iteration. Times per variant come from JMH's per-iteration results.

4. **Warmup alternates too,** so no variant gets more JIT attention than the others, and JIT activity during measurement is checked per variant (`JitProfiler`).

## Supporting changes

- `CompileBenchmark`: the `variants` parameter, the per-iteration rebuild, and the iteration log (instants and variant names) that the profilers read.
- `XctraceProfiler`: split samples by iteration and variant.
- `ab.py report` / `abindex.py`: paired differences per fork and their spread.
- Settings compared this way become `-Y` options where they are system properties today (e.g. `-Ddotty.primeSpans`).

## Prior art

- JMH runs one benchmark configuration per fork; interleaving within a fork is what `ab.py` cannot do across forks.
- Interleaved, paired A/B is standard in performance work where machine drift dominates (e.g. Mytkowicz et al., "Producing Wrong Data Without Doing Anything Obviously Wrong!", ASPLOS 2009, on measurement bias from setup).

## Plan

- TODO 1. `variants` in `CompileBenchmark`, timing per variant from JMH's iteration results; check on two identical variants that the paired difference is near zero and its spread smaller than across forks.
- TODO 2. Per-variant counters in `XctraceProfiler`.
- TODO 3. Paired summaries in `ab.py report` and `abindex.py`.
