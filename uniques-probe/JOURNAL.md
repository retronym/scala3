# Uniques churn: experiment journal

Corpus: scalaz from lampepfl/scala3-benchmarks (`-source 3.0 -Xkind-projector`), Apple Silicon, JDK 25. Newest entries last.

## Exp 0: where the time goes (2026-10-13)

async-profiler, hardware counters, jdk26, `CompileBenchmark` with a fresh context per op: `AppliedUniques.linkedListLoop` takes 2.17% of compiler-thread cycles but only 0.57% of instructions (IPC 0.68 against 2.59 overall), and 1.14% of branch misses.

## Exp 1: bucket chains (2026-10-13)

Reflective dump of the three tables after one compile, after `System.gc()`:

| table | slots | entries (live / stale) | load | avg probes on hit | entries sharing a full 32-bit hash |
|---|---|---|---|---|---|
| AppliedUniques | 4M | 417K (324K / 93K) | 0.10 | 1.05 | 11 pairs |
| NamedTypeUniques | 1M | 404K (385K / 19K) | 0.39 | 1.19 | 11 pairs |
| Uniques | 512K | 81K | 0.16 | 1.35 | about 1,900 hashes shared by 3–9 types |

**Finding:** collisions are not the problem; the cost is dependent loads from table to `Entry` to referent to `tycon`/`args`, in a table that grew to 16 MB of references for 417K entries. The table only grows, and its count includes stale entries until the reference queue is drained.

## Exp 2: hit/miss traffic and who causes it (2026-10-13)

`Uniques.probe` hook (DO NOT MERGE) and `uniques-probe/Probe.scala`. All events counted; 1 in 16 sampled with a stack walk (phase, call site) and, for misses, a weak reference to see whether the new type is alive at the end of the run.

| table | lookups | hit % | misses | survive to end | hits that made caller-allocated parts garbage |
|---|---|---|---|---|---|
| applied | 3.68M | 36% | 2.34M | 14% | 96% (fresh `args` list) |
| named | 1.48M | 69% | 463K | 83% | n/a |
| uniques | 902K | 53% | 421K | 18% | 100% (the probe type) |

Lookups by the binders of the params they mention (`-Dprobe.lambdas`):

| applied lookups mentioning | hits | misses | hit % |
|---|---|---|---|
| params of a method/poly type | 657K | 1.84M | 26% |
| no params | 544K | 276K | 66% |
| params of an HK lambda under construction | 224 | 125K | 0.2% |
| params of a finished HK lambda | 134K | 103K | 57% |

**Finding:** `MethodOrPoly` is an `UncachedGroundType`, so method and poly types are never hash-consed, and a `ParamRef` hashes by `System.identityHashCode(binder)`. A type over the params of a method or poly type can therefore only be hit by operations on the same lambda instance. These types are 79% of AppliedUniques misses, and every `LambdaType.newLikeThis` (e.g. asSeenFrom of a polymorphic signature) mints a new batch. Top miss sites: `MethodOrPoly.newLikeThis` (1.0M misses, 2.7% hit rate), `HKLambda.instantiate` (272K, 0.3% survive), `MethodOrPoly.instantiate` in typer (170K, 0% survive).

## Exp 2b: uncache types over method/poly params (2026-10-13)

`-Ddotty.uncacheMethodParamRefs`: `ParamRef.computeHash` returns `NotCached` when its binder is a `MethodOrPoly`. scalaz compiles without errors.

Deterministic `Stats` counters (`-Ydetailed-stats`, one compile per variant):

| counter | base | uncache | delta |
|---|---|---|---|
| AppliedUniques puts / misses | 3.68M / 2.34M | 1.19M / 493K | −68% / −79% |
| Uniques puts | 902K | 525K | −42% |
| subtype calls | 2.16M | 2.67M | +23% (all successful) |
| `Symbol.denot` | 129.7M | 135.5M | +4% |
| `typeParams` | 13.2M | 13.9M | +5% |
| implicit search counters | | | identical |

Allocation per compile (thread-allocated bytes, nonbootstrapped, 15 runs): 3957 MB to 3893 MB (−1.6%). Wall time inconclusive on a loaded machine.

**Finding:** uncaching removes the churn but costs identity: the extra subtype calls are `eq` fast paths falling through to structural comparison. An earlier bench-lab run showing 2× instructions was an artefact of load average 38 on 18 cores and is discarded.
