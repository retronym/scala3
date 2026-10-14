# Paired A/B within one fork

## Problem

`ab.py` compares variants across forks. Each fork compiles the compiler's hot code differently: forks of one variant differ by 0.5–1% in instructions per op, and one in five re2s forks was 3% off. A change worth 0.5% needs many rounds to show through that, although the variants differ only in a compiler setting that could be switched within one JVM.

In `positioned-serial` (re2s, instructions/op in G, one fork per variant per round):

| variant | rounds 1–5 |
|---|---|
| base | 4.869 4.897 4.904 4.920 4.760 |
| prime | 4.800 4.829 4.857 4.854 4.880 |
| nocheck | 4.875 4.852 4.851 4.849 4.873 |
| both | 4.807 4.798 4.784 4.828 4.718 |

One fork per variant lands about 3% off. Whether the mean or the median is used moves the verdicts on prime and nocheck by 0.4–0.7 points, and only both separates clearly from base (−1.7% to −2.0%).

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

First use: the Positioned changes, all per-Run settings since retronym/scala3 1a64cdb214. With `-Yno-parallel-pickling` throughout: base adds `-Yno-prime-spans`; prime adds nothing; nocheck adds `-Yno-prime-spans -Yno-check-positions`; both adds `-Yno-check-positions`.

- TODO 1. `variants` in `CompileBenchmark`, timing per variant from JMH's iteration results; check on two identical variants that the paired difference is near zero and its spread smaller than across forks.
- TODO 2. Per-variant counters in `XctraceProfiler`.
- TODO 3. Paired summaries in `ab.py report` and `abindex.py`.
