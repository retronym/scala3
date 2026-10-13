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

## Exp 3: bench-lab A/B, base vs uncache, idle machine (2026-10-13 17:46–18:08)

bench-lab `CompileBenchmark` (bootstrapped, a fresh context per op), `benchLabXctrace ipc -p corpus=scalaz -wi 10 -i 10 -w 10 -r 10`, JDK 25, A/B/A/B, load average about 4 to 6 (the benchmark's own GC and JIT threads included).

| run | ms/op | cycles/op (bench thread) | IPC (bench) | instructions/op | alloc/op | GC time / count |
|---|---|---|---|---|---|---|
| base 1 | 4805 ± 102 | 17.95G | 3.54 | 63.5G | 3976 MB | 4.8 s / 135 |
| uncache 1 | 4092 ± 105 | 15.69G | 3.96 | 62.1G | 3941 MB | 3.3 s / 270 |
| base 2 | 4638 ± 120 | 17.59G | 3.57 | 62.8G | 3985 MB | 4.9 s / 141 |
| uncache 2 | 3993 ± 47 | 15.63G | 4.00 | 62.5G | 3940 MB | 3.3 s / 266 |

**Finding:** uncache is 14% faster (mean 4722 to 4043 ms/op) and 12% fewer cycles on the compiler thread, with the same instructions: the extra subtype work (Exp 2b) costs about what the saved lookups did, and the win is memory stalls (IPC 3.55 to 3.98). GC time drops by a third (fewer weak references to discover and process, fewer promoted entries), though young collections double in number; not yet understood.

Cycles by method (bench thread), base: `TypeApplications.appliedTo` 4.6% (where `AppliedUniques.enterIfNew` and its loop are inlined) and `WeakHashSet.resize` 2.4%. Both drop out of the top list with uncache. The resize cost is separate: a fresh context starts the table at 64K slots and doubles it to 4M every compile, counting stale entries.

## Exp 4: per-binder tables (2026-10-13 18:09–18:41)

`-Ddotty.localMethodParamUniques`: each `MethodOrPoly` owns a small open-addressing table, holding strongly the applied types whose `tycon` or a direct arg is one of its params (directly, or through an applied type routed the same way). These keep identity (types stay cached, and all hits on them come from the same lambda instance anyway) but skip the global weak table. The routing is shallow, so some types over method params (inside `TypeBounds`, refinements and so on) still go global.

Probe (one compile): 2.42M of 3.68M applied lookups go to binder tables, with 750K hits; global applied misses fall from 2.34M to 558K. 38% of binder-table entries are alive at the end of the run, since a table holds its entries as long as its binder lives.

Same bench-lab setup as Exp 3; an extra `localBinder` field on `CachedAppliedType` is present in all variants, adding about 30 MB/op of allocation to base.

| run | ms/op | cycles/op | IPC | alloc/op | GC time / count |
|---|---|---|---|---|---|
| base 1 / 2 | 4639 ± 52 / 4589 ± 77 | 17.68G / 17.56G | 3.50 / 3.55 | 4024 / 4007 MB | 4.9 s / 150, 135 |
| uncache 1 / 2 | 4009 ± 82 / 3991 ± 57 | 15.65G / 15.55G | 4.00 / 4.04 | 3976 / 3954 MB | 3.2 s / 268, 278 |
| local 1 / 2 | 4218 ± 62 / 4264 ± 104 | 16.35G / 16.41G | 3.85 / 3.78 | 3978 / 3980 MB | 3.8–3.9 s / 257, 271 |

Cycles by method (bench thread): base `AppliedUniques.enterIfNew` 4.8% and `WeakHashSet.resize` 2.5%; uncache `AppliedUniques.enterIfNew` 1.8%; local `AppliedUniques.enterIfNew` 1.6% and `LocalAppliedUniques.enterIfNew` 1.5%.

**Finding:** per-binder tables take 8% off base, but uncaching takes 13%. Keeping identity for these types is worth less than the lookups (and the longer-lived entries, see GC time) cost. Uncache stays the lead.
