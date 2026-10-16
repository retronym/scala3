# Cost per definition

## Problem

We want to find the definitions (methods, vals, classes) that take an unusual share of compile time, and see where their time goes. A flame graph cannot answer this directly: it aggregates by call path in the compiler, not by the user's definition being compiled.

Timing `typedDefDef(a)` charges `a` for work that belongs to other definitions:

- **Inferred types.** If `a` calls `b` and `b` has no declared result type, `b`'s symbol is completed on `a`'s stack: `Namer.Completer.complete` → `valOrDefDefSig` → `typedAheadExpr(b.rhs)`. `b`'s body is typed there, inside `a`'s timer, and cached as a `TypedAhead` attachment; `typedDefDef(b)` later looks cheap.
- **Parents and signatures**: class parents, type definitions and annotations are completed on demand, on whoever needs them first.
- **Classfiles and TASTy**: unpickling a class happens in `SymbolLoader.complete`, on the stack of whichever definition first referred to it.

## What the compiler had

- `util.Stats` (`-Ydetailed-stats`): counters and timers by string, compiled out (`inline val enabled = false`); counts and inclusive time, not per definition.
- `profile.Profiler` (`-Yprofile-enabled`, from Scala 2's `-Yprofile-*`): per phase, wall, CPU and allocation; `-Yprofile-trace` writes a Chrome trace with nested events for phases, units, typed definitions, implicit searches, inline calls and classfile/TASTy completions. Source completions were not hooked, so a typed-ahead body showed up inside the definition that triggered it. Events are inclusive and per occurrence; there was no aggregation, and nothing per definition after the typer.

## Design

### A cost stack in step with the compiler's hooks

A stack of frames (`profile/DefCosts.scala`) is kept in step with the profiler's hooks, each frame with a kind and a key:

| kind | hook | key |
|---|---|---|
| phase | `Run.runPhases` (`onPhase`) | the phase |
| unit | `Phase.runOn`, `TyperPhase` (`onUnit`) | the compilation unit |
| def | `Typer.typed{Val,Def,Type,Class}Def` in the typer (`onTypedDef`) | the symbol |
| retype | the same after the typer (erasure, other `ReTyper`s) and `Recheck` (capture checking) | the symbol |
| complete | `Namer.Completer.complete` (new hook) | the symbol |
| load | `SymbolLoader.complete` | the symbol |
| transform | a member's subtree in `MegaPhase.transformTree`, `MacroTransform`, `Inlining` (new hooks) | the symbol |
| implicit, inline | `inferImplicit`, `inlineCall` | the innermost frame's key |

A frame's self time is its time minus that of the frames nested in it, so `b`'s typed-ahead body, being the `complete` frame of `b`, is charged to `b`. Only class members and top-level definitions get frames; local definitions are part of their owner. Per-phase kinds (unit, retype, transform, implicit, inline) take the phase of the innermost unit frame: inside a MegaPhase, `ctx.phase` is not the MegaPhase. This is Scala 2's stackable timers (`Statistics.pushTimer`, exclusive time per tree class) per definition, with completions as frames.

The hooks are the profiler's `inline` methods with try/finally, so exceptions, cyclic references and macro suspension unwind frames. `Profiler.defCosts` is a final field: with the cost per definition off, a hook costs a field load and a null check, and its arguments are not evaluated.

### Two consumers

- **`-Yprofile-defs <file>`**: a table, one row per kind, phase and key: calls, self and total µs, the longest self time, self allocation, the definition's size in characters. Wall clock and allocated bytes (`nanoTime` 8 ns, `getCurrentThreadAllocatedBytes` 29 ns per read on an M5 Pro; thread CPU time costs 380 ns per read, too much for hundreds of thousands of frames). `perflab defcosts` (jvm-perf-lab) turns it into histograms per kind, the definitions with the most self time, those slow for their size, and comparisons between runs.
- **`-Yprofile-defs-listener <class>`**: a `CostListener` told about each frame. bench-lab's `SpanCostListener` records each frame as an async-profiler span, nested as the frames are, tagged with a string the compiler already holds (the phase's name, the source path, `Name.toString`, "implicit"), and only if a sample was taken during it (`Span.hasSamplesSince`, added to the vendored `one.profiler.Span`; upstream keeps that check private). Recording CPU samples with spans attributes CPU, or PMU events with kperf, to definitions without clock reads per frame.

With a listener alone, a frame is its kind, key, phase and parent, reused from a pool by depth; the clocks and per-key accumulators are only for the table.

### Spans in the flame graph

async-profiler's Span API ([#1755](https://github.com/async-profiler/async-profiler/pull/1755)) only filters (`jfrconv --tag`, `--latency`). retronym/async-profiler#3 adds `jfrconv --spans`: each sample gets the path of the spans enclosing it (`typer > Foo.scala > Foo > bar > implicit`) as a hidden leaf, and the flame graph has a span panel: for the hovered frame, the zoom root or the search's matches, the samples by definition (with its owners), by phase or by unit; a click filters the graph to one definition's samples, Shift+click highlights its frames. Span tags as root frames were tried first and dropped: the graph splits into a sliver per definition.

## Results so far

- Overhead (scalaz, paired within forks, xctrace): the cost stack with a listener and no recording, +3.5% ± 0.9 time and +3.1% ± 1.6 instructions; writing the table each op, +14%. `bench-lab/ab/def-costs-overhead-5.json`. The hooks' cost with profiling off is not yet measured (needs an A/B across builds, `abtrees.py`).
- On scalaz the top definitions are dominated by `Uniques` table growth (`WeakHashSet.resize`), which lands on whichever definition triggers it, the same one in every op since each op compiles in the same order.

## Future work

- Measure the hooks with profiling off against `bench-lab-harness`.
- Flag costs dominated by one amortized operation (table growth, GC) rather than charging them to a definition.
- Overloads share a full name in the table; add the signature when it can be read without forcing completion.
- Propose `Span.hasSamplesSince` (or a lazy tag) upstream.
- A span lane in async-profiler's heatmap: phases and units over time, and a definition's time slices.
