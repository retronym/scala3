# bench-lab: a counters lab for the compiler

## Problem

[lampepfl/scala3-benchmarks](https://github.com/lampepfl/scala3-benchmarks) answers "how fast is release X on corpus Y": published compilers, end to end, files on disk, tracked over versions. It is the right tool for the release curve and the wrong one for a compiler engineer's question: *what did my change do to the machine, and where?*

That question needs a different instrument:

- **It runs the working tree.** No `publishLocal`, no version string: edit, run, compare against the branch point.
- **It reaches inside.** Micro benchmarks need `private[dotc]` APIs to drop caches between invocations and to run one phase or one operation on real inputs.
- **It measures the compiler and nothing else.** No file I/O in the measured region, no sbt, and counters for the compiler thread only, not the JIT and GC threads summed in.
- **It explains, not just times.** Wall time says a change was 2% faster. Counters say why: fewer instructions, or the same instructions with fewer L1I misses. Attribution says where: which methods, which phases.

## Key decisions

1. **An sbt project in this repo, built against the bootstrapped compiler.** The bootstrapped compiler is the bytecode that ships, so it is the one whose JIT behaviour matters. A non-bootstrapped variant can be added for quick iteration.

2. **No file I/O in the measured region.** Sources are read once into memory (`VirtualFile`). So is the classpath (`InMemoryClasspath`): the scala-library jar and the JDK's `java.base` (more with `-Dbenchlab.jdkModules=`), served to dotc by a platform whose classpath is in-memory directories, instead of jars and the jrt file system, which dotc caches process-wide. Output goes to an in-memory directory, so class files and TASTy are still generated (the backend's work is real work) but never written. This needs no new compiler flag; a `-Y` flag to discard output would only help outside the repo (sbt, the CLI) and would also skip the serialization we want to measure.

3. **Macro and micro in one harness, sharing inputs.**
   - Macro: one op is one full compilation of a corpus from scratch: a new `ContextBase`, platform, `Compiler` and `Run`, in a warm JVM. It pays for what a cold compile pays (definitions, classpath indexing, symbol loading) except file reads and JIT warmup, and nothing carries over between ops but the name table, so there is no crosstalk to reason about. `-p context=shared` instead reuses one root context and `Compiler`, a fresh `Run` per op, as in a warm compile server; dotc then keeps every earlier `Run` alive (through the root context's docstrings and GADT state, and phase instance state), about 240 MB per scalaz compile, until the heap runs out.
   - Phase: split a macro run by phase rather than benchmarking phases separately. The compiler records phase boundaries on its thread, and the counter samples are split by phase.
   - Operation: subtyping, implicit search, member lookup and similar, replaying inputs recorded from a real compilation, with explicit cold- and warm-cache variants.

4. **Counters are the primary output, not a profiler bolted on.** On Apple Silicon, `XctraceProfiler`: per-op event counts for the benchmark thread only, Apple's top-down breakdown, and per-method attribution through the JIT's perf map. On Linux, JMH's `perfnorm` and `perfasm`, plus top-down. The same metric names on both where possible.

5. **Experiments are first-class.** A variant is a set of JVM flags (`-XX:MaxInlineLevel`, `-XX:-Inline`, `-XX:+UseCompactObjectHeaders`, GC choice), a compiler setting, or a different compiler build (another worktree), or the same tree built differently: `SCALA3_BUILD_OPTIMIZE=false` builds the bootstrapped compiler without `-opt -opt-inline:dotty.**`, so it compares the compiler with and without its own inlining. Benchmark the default (optimized) build unless that is the experiment. A driver runs variants interleaved (A/B/A/B) and reports differences in time and in each counter with their noise.

6. **Corpora come from a scala3-benchmarks checkout**, not copied here: `small`, `dottyUtil`, `re2s` and `scalaz` need no dependencies. Micro benchmarks may use sources inline in the benchmark (`Corpus.inline`).

## Running

Needs a [lampepfl/scala3-benchmarks](https://github.com/lampepfl/scala3-benchmarks) checkout, by default beside this one; otherwise set `SCALA3_BENCHMARKS` before starting sbt.

```bash
sbt "scala3-bench-lab/Jmh/run -p corpus=dottyUtil CompileBenchmark"
```

Every run has two profilers on by default (`-noDefaultProf` turns them off): `gc`, for allocation per op (`gc.alloc.rate.norm`, the benchmark thread only) and GC count and time; and `JitProfiler`, for JIT stability: compilations, compile time and deoptimizations while measuring and in the last warmup iteration (`jit.*`), with per-iteration counts and the methods still being compiled or deoptimized in the fork's output. A steady state has next to none; if `jit.compilations` is large, warm up longer.

Counters on Apple Silicon (needs Xcode's `xctrace`): `benchLabXctrace <template> <jmh args>` runs JMH under `XctraceProfiler` and keeps the traces and `result.json` in `out/bench-lab/<template>`. The template is `cpu-counters` (Instruments' stock CPU Counters: Apple's top-down breakdown per thread group) or an event-sampling template from `templates/` (`ipc`, `frontend`, `mem-l1d`: event counts per op and per-method attribution); `templates/make_template.py` makes one for other events. It is a command rather than a `-prof` argument because sbt splits a typed command line at the `;` between profiler options.

```bash
sbt "benchLabXctrace cpu-counters -p corpus=dottyUtil CompileBenchmark"
```

async-profiler: `benchLabAsync <events> <jmh args>` records JFR over the measurement iterations and converts it with `jfrconv` into flame graphs in `out/bench-lab/async-<events>`: the benchmark threads only (stacks through the JMH stub), the same reversed, and all threads split by thread. `<events>` is a comma-separated list of `cpu`, `wall`, `alloc`, `lock` or any async-profiler event (hardware events such as `cycles` or `cache-misses` on Linux). The library is `$ASYNC_PROFILER_LIB`, else the Homebrew or `/usr/local` install.

```bash
sbt "benchLabAsync cpu,alloc -p corpus=dottyUtil CompileBenchmark"
```

Benchmarks: `CompileBenchmark` (full compilation), `ParserBenchmark` (parsing only), `PicklerBenchmark` (TASTy pickling of typed trees). `-p corpus=/abs/path` compiles the `.scala` files under any directory (with a known corpus's options when the directory name matches one). `-p scalacOptions=-Ystop-after:typer` passes extra compiler options. `CompileLoop` compiles a corpus in a loop outside JMH, for attaching other tools.

### A/B campaigns

`scripts/ab.py` runs a campaign described by a JSON spec (`ab/*.json`): a benchmark under one profile (an xctrace template, or async-profiler events, with kperf if given), over corpora and JVM-flag variants, round by round with the variants interleaved, so drift hits all of them alike. It is resumable (cells with a `result.json` are skipped), waits for a quiet machine (`loadgate`) and for any other JMH run, and checks that each run measured what it claims: the JVM, the JVM arguments (JMH's `-jvmArgsAppend` replaces the benchmark's `@Fork(jvmArgsAppend)`, so every variant gets the spec's `jvmArgs`), and, for kperf, that samples were not lost. `ab.py report` prints a table per corpus (ms/op, instructions, cycles, IPC, allocation, each against the first variant) and, for async-profiler campaigns, an index of every cell's flame graphs. A variant can also add JMH arguments (`variantJmhArgs`, e.g. `-p scalacOptions=...`), so it can be a compiler setting; these are checked against the run too. Results go to `out/ab/<name>/<corpus>/<variant>/<round>`. `abtrees.py` does the same across builds: each variant is a worktree with its own sbt server (spec key `"trees"`), and the results are gathered and reported together.

```bash
python3 -I bench-lab/scripts/ab.py run bench-lab/ab/<spec>.json
python3 -I bench-lab/scripts/ab.py report bench-lab/ab/<spec>.json
```

Profile tools, for any `benchLabAsync` profile directory or async-profiler `ab.py` cell:

- `regraph.py`: (re)renders the flame graphs with a chosen `jfrconv` (`--jfrconv`, `$JFRCONV`, else a build of retronym/async-profiler's integration branch if present, which has `--hw` for kperf events and the extended flame graph controls), adding classified forward and reverse graphs of the benchmark threads. Up-to-date graphs are skipped (a `.regraph` stamp records the `jfrconv` build and `--minwidth`).
- `profdiff.py`: two runs compared per op (ops counted from each iteration's mean): a differential flame graph and the methods whose self time changed most.
- `abindex.py`: `out/ab/index.html`, every campaign with a summary (mean ms/op, cycles/op and instructions/op per corpus and variant, their spread over rounds and the change against the first variant), its cells, flame graphs and differential graphs, filterable, with each campaign's `NOTES.md` and a findings log from `out/ab/FINDINGS.md`.
- `frameweight.py`: the weight of frames matching a regex across runs: self, self plus the itable/vtable stubs it calls, inclusive, and inclusive short of frames matching `--until`.
- `subtree.py`: one frame's subtree: its callers, and where the time inside it goes, by categories (`-c name=regex`).

### Variance reduction

What makes a difference between variants stand out from the noise, with the evidence from the Positioned campaigns (`positioned-serial`: re2s, 4 variants × 5 rounds, xctrace ipc):

- **A/B within one JVM where the change allows it.** Feature flags compared in the same fork share its compiled code, so the fork's JIT luck cancels in the difference (see Plan, paired A/B).
- **Microbenchmarks for a local change.** A benchmark of the changed operation (e.g. copying a tree with the tree copier) isolates it far better than a whole compilation, where it is a fraction of a percent.
- **No optional parallelism.** The pickler runs on an executor, a fresh thread per op, invisible to the benchmark thread's counters: on re2s 12% of the process's cycles. `-Yno-parallel-pickling` (retronym/scala3#15) moves it onto the benchmark thread (from 83% to 93% of cycles; instructions/op 4.47G → 4.88G). Use it when counting the benchmark thread, or count all compiler threads; `<trace>.threads.txt` shows what is outside the benchmark thread. The backend is already serial (`-Ybackend-parallelism` is 1).
- **Watch the machine.** `ab.py` waits for a quiet machine and reruns a cell whose competing load averaged too high. A limit of 1.5 cores still admits about one core of other work.
- **A better-lit metric.** On re2s, instructions/op varied 0.2–1.3% between rounds where ms/op varied 1–7%, and allocation per op is steadier still. They are proxies, though: priming spans cut instructions by about 1% on scalaz but cycles by only 0.6%.
- **JIT determinism.** Forks of the same variant differ by 0.5–1% in instructions, and one re2s fork was 3% off (and the slowest). Options: `-Xbatch`, a fixed `-XX:CICompilerCount`, longer warmup, and `JitProfiler`'s check that nothing is compiled while measuring.
- **Paired, interleaved designs.** Variants alternate within each round; report the spread of the paired differences, not of the absolute values.
- **Exact counts of work.** A probe that counts operations (see `doc/caller-audit.md`) has no noise at all, and measures the work removed rather than its cost.
- **Robust statistics.** Median or trimmed mean, and the per-fork values, not only a mean with an error bar.
- **A fixed, pre-touched heap** (`-Xms` = `-Xmx`, `-XX:+AlwaysPreTouch`).
- **Apple Silicon.** Performance-core residency, Low Power Mode and thermals. Cycles normalise away the clock frequency; time does not.
- **No I/O in the measured region** (above), and an isolated machine where possible.

### No file I/O in the measured window

Checked on `dottyUtil` with JFR file events (`jdk.FileRead`, `jdk.FileWrite`, no threshold) over 10 `CompileLoop` runs: all reads fall in the first run (the classpath snapshot, and the JVM loading compiler classes on first use), none after it. An earlier check, with the jar classpath, found the same apart from the odd lazy read from the scala-library jar.

### Live heap across ops

`CompileLoop` prints the live heap after each run (after a full GC), and `-Dbenchlab.histoAt=<runs>` writes class histograms, to diff what survives. From scratch per op, it stays flat (scalaz: 110 MB over 8 runs); with `-Dbenchlab.shared` it grows by a whole `Run` per op.

## Plan

- DONE 0. Scratch project: `XctraceProfiler` attaching only for the measurement iterations, per-thread counters, calibrated on kernels with known bottlenecks.
- DONE 1. Skeleton: one macro benchmark on scala3-benchmarks corpora, in-memory I/O, runs under `XctraceProfiler` (`benchLabXctrace`). I/O claim checked (above).
- DONE 1b. async-profiler (`benchLabAsync`); phase benchmarks for parsing (`ParserBenchmark`) and TASTy pickling (`PicklerBenchmark`, compiled up to the pickler in setup, re-pickling the same trees per op).
- TODO 2. Phase attribution of the counters.
- TODO 3. Operation-level micro benchmarks with recorded inputs and cache control.
- TODO 3b. Optimizer benchmark: compile with `-opt` in setup, capture the class nodes before global optimization, and per op run `GlobalOptimizer` and `LocalOptimizer` on copies with fresh optimizer state (the call graph is keyed on node identity). Needs compiler refactoring: the optimizer wiring moved out of `GenBCode.getCodeGen` into a factory, and a hook to capture the pending class nodes at the start of `CodeGen.finish` (e.g. a `GenBCode` subclass installed via `Compiler.phases`). Re-reading unoptimized class files instead would lack the inline info attribute and differ from the in-compiler input.
- DONE 4. Variant driver: `ab.py` (JVM flags and compiler settings, interleaved, with deltas), `abtrees.py` (builds).
- TODO 4b. Paired A/B within one fork (`doc/paired-ab.md`): variants alternate by iteration, sharing the fork's compiled code.
- TODO 5. Linux parity.
- TODO 6. First experiment: JIT inlining limits on the compiler, with the stalls before and after and where they moved.
- Future work: PMI-sampled templates (instruction-level attribution, needs a one-off template saved in Instruments), Processor Trace for exact control flow on M4/M5, a non-bootstrapped variant.
