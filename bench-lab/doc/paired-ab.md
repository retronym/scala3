# Paired A/B within one fork

## Problem

Campaigns compare variants across forks. Each fork compiles the compiler's hot code differently: forks of one variant differ by 0.5–1% in instructions per op, and one in five re2s forks was 3% off. A change worth 0.5% needs many rounds to show through that, although the variants differ only in a compiler setting that could be switched within one JVM.

In `positioned-serial` (re2s, instructions/op in G, one fork per variant per round):

| variant | rounds 1–5 |
|---|---|
| base | 4.869 4.897 4.904 4.920 4.760 |
| prime | 4.800 4.829 4.857 4.854 4.880 |
| nocheck | 4.875 4.852 4.851 4.849 4.873 |
| both | 4.807 4.798 4.784 4.828 4.718 |

One fork per variant lands about 3% off. Whether the mean or the median is used moves the verdicts on prime and nocheck by 0.4–0.7 points, and only both separates clearly from base (−1.7% to −2.0%).

## Key decisions

The design is perflab's ([retronym/jvm-perf-lab's doc/paired-ab.md](https://github.com/retronym/jvm-perf-lab/blob/main/doc/paired-ab.md)): variants alternate by iteration within a fork in a Williams order, warmup included, and the profilers split time and counters by iteration. For dotc:

- **Variants are per-run compiler settings.** `CompileBenchmark` takes `variants="<name>=<scalacOptions>|…"` and has a compiler per variant, switching at each iteration. Since each op compiles from scratch, this costs nothing extra. Anything compared this way must be a setting read per run (a `-Y` option), not a system property read once per JVM; variants that need different JVM flags or builds are compared across forks (`perflab ab`, `perflab trees`).
- Settings compared this way become `-Y` options where they are system properties today (e.g. `-Ddotty.primeSpans`).

## Plan

- DONE 1. `variants` in `CompileBenchmark` (each cycle of iterations runs every variant once, the order reversed every other cycle), `IterationLog`, `PairedProfiler` (time per variant and paired differences).
- DONE 2. Per-variant counters in `XctraceProfiler`, samples assigned to iterations by sample time.
- DONE 3. Campaign specs with `"paired"`, and the report's paired table (each variant's mean, and the paired difference: mean, spread and per-fork values).

A/A check (`ab/paired-aa.json`: dottyUtil, two identical variants, 3 forks of 12 × 3 s, before the order reversal): instructions/op paired difference +0.18% ± 0.51 over forks (−0.19, +0.76, −0.04), against about 1% between the forks' own means; within a quiet fork the per-cycle spread was 0.6–0.7%, in a fork with a core of other load 7%. Cycles/op came out −0.12% to −0.31% for the second variant in every fork: an order effect, hence the reversal every other cycle.

Second A/A check, with the order reversed every other cycle: instructions/op +0.08, +0.77 and +4.07% per fork. The third fork ran with about a core of other load, and its slow iterations (160–173 ms/op against 140) counted 1.60–1.63G instructions/op against 1.73–1.80G: xctrace credits each core's counter delta since its last sample to the thread running at the sample, so a preempted benchmark thread loses part of its counts to whoever replaced it. Pairing removes the fork's JIT luck, not the machine's noise: paired counters need a quiet machine (a load gate below one core), and the median of the per-cycle differences (`.delta%.median`) is reported beside the mean. Exact per-thread counting (the kperf helper's per-thread deltas) would remove this attribution error.

- DONE 4. Paired campaigns in the index's summary table (perflab).
- TODO 5. Exact per-thread counts per iteration (kperf `-t`), instead of xctrace's sampled attribution.
