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

- DONE 1. `variants` in `CompileBenchmark` (each cycle of iterations runs every variant once, the order reversed every other cycle), `IterationLog`, `PairedProfiler` (time per variant and paired differences).
- DONE 2. Per-variant counters in `XctraceProfiler`, samples assigned to iterations by sample time.
- DONE 3. `ab.py` specs with `"paired"`, and `ab.py report`'s paired table (each variant's mean, and the paired difference: mean, spread and per-fork values).

A/A check (`ab/paired-aa.json`: dottyUtil, two identical variants, 3 forks of 12 × 3 s, before the order reversal): instructions/op paired difference +0.18% ± 0.51 over forks (−0.19, +0.76, −0.04), against about 1% between the forks' own means; within a quiet fork the per-cycle spread was 0.6–0.7%, in a fork with a core of other load 7%. Cycles/op came out −0.12% to −0.31% for the second variant in every fork: an order effect, hence the reversal every other cycle.

Second A/A check, with the order reversed every other cycle: instructions/op +0.08, +0.77 and +4.07% per fork. The third fork ran with about a core of other load, and its slow iterations (160–173 ms/op against 140) counted 1.60–1.63G instructions/op against 1.73–1.80G: xctrace credits each core's counter delta since its last sample to the thread running at the sample, so a preempted benchmark thread loses part of its counts to whoever replaced it. Pairing removes the fork's JIT luck, not the machine's noise: paired counters need a quiet machine (a load gate below one core), and the median of the per-cycle differences (`.delta%.median`) is reported beside the mean. Exact per-thread counting (the kperf helper's per-thread deltas) would remove this attribution error.

- TODO 4. `abindex.py`: paired campaigns in the summary table.
- TODO 5. Exact per-thread counts per iteration (kperf `-t`), instead of xctrace's sampled attribution.
