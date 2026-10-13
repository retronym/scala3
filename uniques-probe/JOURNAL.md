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

## Exp 5 and 6: table capacity, HK lambdas under construction (2026-10-13 18:42–19:11)

Both on top of uncache (U):
- Exp 5, `-Ddotty.uniquesCapacityFactor=8`: start the three tables 8× larger, to avoid regrowing them from 64K slots on every compile.
- Exp 6, `-Ddotty.uncacheOpenHKParamRefs`: types over the params of an `HKTypeLambda` whose `resType` is still null (it is under construction) are not cached. Those had a 0.2% hit rate (Exp 2). A `ParamRef` caches its hash, so the lambda resets its param refs' hashes at the end of its constructor. Probe on scalaz: applied lookups 1.19M to 1.06M, misses 501K to 384K, about 10K hits lost; 0 errors.

| run | ms/op | cycles/op | IPC | GC time |
|---|---|---|---|---|
| U 1 | 4131 ± 289 (overlapped my low-priority probe runs; discarded) | 15.59G | 3.90 | 3.4 s |
| U 2 | 3993 ± 60 | 15.47G | 4.01 | 3.4 s |
| U+openHK 1 / 2 | 3934 ± 71 / 4092 ± 86 | 15.39G / 15.46G | 4.05 / 3.95 | 3.1 / 3.3 s |
| U+cap8 1 / 2 | 3949 ± 88 / 4286 ± 199 | 15.45G / 15.01G | 4.03 / 3.88 | 3.0 / 3.4 s |

**Finding:** within noise on scalaz, at most 1–3%. With U the applied table stays small, so regrowing it no longer shows in the profile. Parked.

## Exp 7: is scalaz representative? (2026-10-13)

Probe (`-Dprobe.lambdas`) on the other corpora that need no dependencies. Applied lookups by the binders of the params they mention:

| corpus | applied misses over method/poly params | their hit % | applied misses with no params (hit %) |
|---|---|---|---|
| scalaz | 1.84M | 26% | 276K (66%) |
| tastyQuery | 13.6K | 13% | 19.2K (68%) |
| dottyUtil | 9.3K | 21% | 9.3K (60%) |
| areWeFastYet | 2.7K | 27% | 3.6K (58%) |
| re2s | 543 | 4% | 1.5K (99%) |

**Finding:** scalaz is an outlier, with about 100× the method-param churn of the others: higher-kinded typeclass code where asSeenFrom of polymorphic signatures dominates. The pattern (a low hit rate on these types) holds everywhere, so U should be neutral to slightly positive elsewhere, but the extra structural subtyping could show up as a regression. To be measured.

## Exp 8: base vs U on other corpora (2026-10-13 19:11–19:43)

bench-lab, `-wi 15 -i 10 -w 5 -r 5`, base/U interleaved per corpus, two rounds. tastyQuery is added to bench-lab's corpora with `-Yexplicit-nulls`.

| corpus | base ms/op (r1 / r2) | U ms/op (r1 / r2) | Δ time | cycles/op base → U | Δ cycles |
|---|---|---|---|---|---|
| tastyQuery | 825 ± 33 / 829 ± 36 | 797 ± 22 / 804 ± 54 | −3.4% | 3.16G, 3.12G → 3.03G, 2.95G | −4.5% |
| dottyUtil | 169 ± 7 / 167 ± 20 | 168 ± 8 / 150 ± 6 | −5% (noisy) | 0.65G, 0.62G → 0.64G, 0.61G | −1.6% |
| re2s | 279 ± 9 / 270 ± 18 | 275 ± 9 / 273 ± 12 | −0.2% | 1.06G, 1.04G → 1.04G, 1.03G | −1.4% |

**Finding:** U is never worse, with cycles down 1.5–4.5% on corpora with about 100× less method-param churn than scalaz. The extra structural subtyping from lost identity does not show as a regression on any of them. Allocation is unchanged. GC count rises 3–4× under U on every corpus (17 → 71, 20 → 77, 18 → 81) with GC time about the same: Exp 9.
