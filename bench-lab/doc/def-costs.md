# Cost per definition: histograms and outliers

## Problem

We want to find the definitions (methods, vals, classes) that take an unusual share of compile time: a histogram of cost per definition, and the outliers in it. A flame graph cannot answer this directly. It aggregates by call path in the compiler, not by the user's definition being compiled.

The obvious instrumentation, timing `typedDefDef(A)`, charges A for work that belongs to other definitions:

- **Inferred types.** If A calls `b` and B has no declared result type, `b`'s symbol is completed on A's stack: `Namer.Completer.complete` → `valOrDefDefSig` → `typedAheadExpr(B.rhs)`. B's body is typechecked there, inside A's timer, and cached as a `TypedAhead` attachment. When the typer later reaches B, `typedDefDef(B)` finds its body already typed and looks cheap.
- **Parents and signatures.** The same happens for class parents (`typedAhead(parent, ...)`), type definitions and annotations completed on demand.
- **Classfiles and TASTy.** Unpickling a class happens in `SymbolLoader.complete`, on the stack of whichever definition first referred to it.

So the time of a definition is its own work plus a random share of other definitions' work, depending on which one happened to be typed first.

## What exists in the compiler

**`util.Stats`** (`-Ydetailed-stats`). Counters and timers (`Stats.record`, `Stats.trackTime`, about 230 call sites) keyed by string. They are compiled out: `inline val enabled = false`, so using them needs a compiler rebuild. They count events and inclusive time. They say nothing about which definition was being compiled.

**`profile.Profiler`** (`-Yprofile-enabled`), ported from Scala 2's `-Yprofile-*`:

- Per phase: wall, CPU and user time, allocation and heap for the main thread, to the console or CSV (`-Yprofile-destination`). Optional GC between phases (`-Yprofile-run-gc`) and start/stop hooks for an external tool around a phase (`-Yprofile-external-tool`).
- `-Yprofile-trace <file>`: a Chrome trace (Perfetto) with nested duration events for the run, phases, compilation units, `typedValDef`/`typedDefDef`/`typedTypeDef`/`typedClassDef` (`onTypedDef`), implicit searches (`onImplicitSearch`), inline calls (`onInlineCall`) and classfile/TASTy completions (`onCompletion` in `SymbolLoaders`, drawn as "↯"). Thread counters (CPU, allocation, heap, classes loaded, JIT time) every 10 ms; GC events.

Gaps for this question:

1. **Source completions are not hooked.** `Namer.Completer.complete` has no `onCompletion`, so B's body typed during A's typing shows up as part of A's `typedDef` event. This is exactly the inferred-type case above.
2. **Inclusive, wall-clock events only.** The trace has nested intervals. Perfetto can compute self time per slice, but the trace is JSON with one event per definition, implicit search and completion, which is large for a big corpus. There is no aggregation, histogram or ranking in the compiler.
3. **Typer only.** After typer, granularity is the compilation unit.

`Config.showCompletions` and the `completions` printer trace completions for debugging; they are not measurements.

## Proposal

### 1. A cost stack in lockstep with the completion stack

Keep a stack of frames on the compiling thread, one per hooked region: typed definition, source completion, classfile/TASTy completion, implicit search, inline call. A frame records its kind, its key (the symbol, or the implicit's expected type) and its start time. When a frame ends:

- `total = now - start`
- `self = total - children`
- the parent's `children += total`

The completion of B is its own frame keyed by B, so B's body typed ahead is charged to B, and A keeps only its own work. A definition's cost is the sum of its frames' self time: its `typedDef`, its completion, and the implicit searches and inline expansions inside them, each also available separately by kind.

This is the same idea as Scala 2's stackable timers in `Statistics` (`pushTimer`/`popTimer` with exclusive "specific" time), applied per definition, with completions as frames.

The hooks already exist as `inline` methods on `Profiler` (`onTypedDef`, `onImplicitSearch`, `onInlineCall`, `onCompletion`) with try/finally, so exceptions, cyclic-reference errors and macro suspension (`SuspendException`) unwind frames correctly. What's needed:

- `onCompletion` in `Namer.Completer.complete`, with a kind that says source completion. This alone also fixes the attribution in today's `-Yprofile-trace`.
- A `RealProfiler` mode that keeps the stack and aggregates per key, instead of, or as well as, writing trace events.

### 2. Clocks

Measured on an M5 Pro, JDK 21, per call:

| clock | cost |
|---|---|
| `System.nanoTime` | 8 ns |
| `ThreadMXBean.getCurrentThreadAllocatedBytes` | 29 ns |
| `getCurrentThreadCpuTime` / `getCurrentThreadUserTime` | about 380 ns |

Two reads per frame. With wall time and allocation, overhead is under 100 ns per frame, small next to typing a definition. Thread CPU time would cost about 0.8 µs per frame, which is a measurable distortion at hundreds of thousands of frames (implicit searches dominate the count). So the in-compiler mode measures wall time and allocation. CPU attribution comes from sampling (part 4), which costs nothing per frame.

Wall time includes GC pauses and preemption. GC is reported separately by the profiler already. A frame that contains a pause is an outlier for the wrong reason, so the report should mark frames that overlap a GC event rather than trust them.

### 3. Histograms and outliers

Output per run, as TSV: kind, symbol's full name and signature, position, calls, self time, self allocation, total time. A stable key (full name plus signature, not symbol identity) lets runs be compared, e.g. the two variants of a paired A/B, to answer "which definitions got slower with my change".

Reported from it:

- **Histogram per kind** of self time, in log2 buckets (µs). Most definitions are cheap; the shape of the tail is the point.
- **Outliers by self time**: the top N per kind.
- **Outliers relative to size**: self time per node of the definition's tree. A long method that is slow is expected; a short one that is slow is what we're after (an implicit search that explodes, a match type that unfolds, an inline expansion that blows up). A robust threshold, such as median plus k times the median absolute deviation of log(self per node), flags them without tuning.

Aggregation can live in jvm-perf-lab's Python tools (a TSV in, a page out), so the compiler's part is the stack and a writer.

### 4. Async-profiler spans

Async-profiler's Span API ([async-profiler#1755](https://github.com/async-profiler/async-profiler/pull/1755), with [#1763](https://github.com/async-profiler/async-profiler/pull/1763) and [#1805](https://github.com/async-profiler/async-profiler/pull/1805)) records `[start, end]` intervals on a thread, with a string tag, into the JFR recording as `profiler.Span` events. Tags are deduplicated in the recording. `jfrconv --tag <regex>` keeps only samples taken inside spans with a matching tag, and `--latency <ms>` only those inside spans at least that long. `Span.start()` returns a timestamp; the tag is passed at `end`. `endIfProfiled` and `emitIfProfiled` drop spans that enclosed no sample, without entering native code. When async-profiler is not loaded, all calls are no-ops.

Spans nest by time only: a sample in B's completion inside A's typing lies inside both spans, so `--tag A` would include B's work. Emit **segments** from the cost stack instead, so each sample lies in exactly one span, the innermost frame's:

- on push: end the parent's open segment (`emitIfProfiled(segmentStart, now, parentTag)`), start the child's;
- on pop: emit the child's segment, restart the parent's at `now`.

Then:

- CPU per definition, exclusive by construction, from `cpu` samples; cycles, cache misses or branch mispredictions per definition from kperf events, which makes the cost of a definition a hardware-counter question too.
- A flame graph of one outlier: `jfrconv --tag 'def:pkg.C#m.*' profile.jfr m.html`.
- The histogram from span durations: segment durations summed per tag give exclusive wall time per definition, as in part 3, from the same recording.

**Cheap tags.** The tag is a `String` argument to `end`/`emit`, so it is built before the native check, even if the segment is then dropped. Ways to keep that cheap:

- Build a definition's tag once and keep it in its frame or a map from symbol to tag. A definition is typed once and completed once, so that's one string per definition touched, and only while a recording is running (`Recording.state()` is a static field read). The kind prefix (`def:`, `complete:`, `implicit:`, `unpickle:`) is a constant.
- Upstream, `Span` could expose whether a sample was taken since a timestamp (it has `hasProfileSamples` privately), or take a `Supplier<String>`, so a tag is only built for a segment that is kept. Worth proposing once we have numbers.

**Keeping async-profiler out of the compiler.** The compiler shouldn't depend on `one.profiler`. `Profiler` is `sealed`, and the context has `setProfiler`. Unsealing it, or adding a listener interface that the cost stack calls on push and pop, lets bench-lab install a span emitter in the context it creates for each op. The compiler keeps the stack and the hooks; bench-lab owns the async-profiler dependency.

## Plan

0. **Hook source completions.** `onCompletion` in `Namer.Completer.complete`, with a kind. Immediately useful: today's `-Yprofile-trace` shows B's typed-ahead body under B, and Perfetto's self time per slice works.
1. **Cost stack and per-definition TSV** in `RealProfiler` (wall time, allocation), behind a new `-Yprofile-*` setting. Check overhead with bench-lab's paired A/B (profiling on vs off) on scalaz.
2. **Report** in jvm-perf-lab: histograms per kind, outliers by self time and by self time per node, comparison of two runs by stable key.
3. **Listener for spans**: unseal `Profiler` or add the listener; a span emitter in bench-lab that emits segments; per-definition CPU and PMU counts and per-outlier flame graphs from the JFR.

## Future work

- Per-definition costs after typer. MegaPhase transforms trees per node, not per definition, so a frame per definition would need a hook in `MegaPhase.transformStat` or similar; worth it only if later phases show outliers per unit.
- Macro expansion as its own kind (it is currently inside the inline call or the typed definition).
- Upstream `Span` API to build tags lazily (above).

## Prior art

- Scala 2: `Statistics` stackable timers (exclusive time per tree class in the typer, `-Ystatistics`), and the `-Yprofile-trace` work that this compiler's `Profiler` is ported from.
- async-profiler's Span API and `jfrconv --tag`/`--latency`, built for request latency in services; per-definition compile cost is the same problem with a definition in place of a request.
