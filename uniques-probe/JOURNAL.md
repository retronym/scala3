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

## Exp 9: the GC count, and a confound (2026-10-13 19:45)

`-Xlog:gc` in my driver (G1, `-Xms4G -Xmx4G`, 40 re2s compiles): base and U have identical pause mixes. A plain bench-lab run with `-jvmArgsAppend "-Xlog:gc*..."` for both variants: `gc.count` 7–8 in both, 294 against 304 young pauses.

**Cause:** a `-jvmArgsAppend` on the JMH command line replaces `@Fork(jvmArgsAppend = Array("-Xms4G", "-Xmx4G", "-Xss2M"))` instead of adding to it, as `jvmArgs` in result.json shows. In Exps 3–8, base ran with a fixed 4 GB heap and every switch variant with sbt's `-Xms1024m -Xmx8192m`. The higher GC count under U was this heap difference.

**Retractions:** the base-against-switch comparisons in Exps 3, 4 and 8 (the 14% on scalaz included) are confounded and need rerunning. Still valid: comparisons among switch variants, which shared the same JVM arguments (local against U in Exp 4, and Exps 5–6); the deterministic counters (Exp 2, 2b, 7); and the driver's allocation numbers. Exp 10 reruns base against U with the same heap arguments passed to both.

## Exp 10: base vs U with the same JVM arguments (2026-10-13 19:48–20:14)

Same as Exp 3, but `-jvmArgsAppend "-Xms4G -Xmx4G -Xss2M[ -Ddotty.uncacheMethodParamRefs]"` for both variants (checked in result.json `jvmArgs`). Stopped after round 1 of tastyQuery and both rounds of scalaz, at wrap-up.

| run | ms/op | cycles/op | IPC | alloc/op | GC time / count |
|---|---|---|---|---|---|
| scalaz base 1 / 2 | 4609 ± 159 / 4733 ± 149 | 17.26G / 17.56G | 3.55 / 3.48 | 3997 / 4013 MB | 4.9 / 5.0 s, 139 / 136 |
| scalaz U 1 / 2 | 4045 ± 88 / 4236 ± 164 | 15.84G / 15.79G | 3.96 / 3.80 | 3980 / 3981 MB | 2.8 / 2.8 s, 114 / 112 |
| tastyQuery base 1 | 773 ± 52 | 3.06G | 3.22 | 646 MB | 0.4 s / 44 |
| tastyQuery U 1 | 794 ± 34 | 3.09G | 3.10 | 644 MB | 0.4 s / 19 |

**Finding:** the scalaz win holds without the confound: −11% time (mean 4671 to 4141 ms/op), −9% cycles on the compiler thread, and GC time down 43% (fewer weak references to process and fewer promoted entries). tastyQuery is neutral (one round). The cross-corpus gains in Exp 8 were the heap confound; the cross-corpus result is now "no regression", not "small win".

## Summary so far

- **Problem:** `AppliedUniques` is hot from memory stalls, not collisions. Most of its traffic, and nearly all of its churn, is applied types over the params of method and poly types. Those lambdas are never hash-consed, so such a type can only be hit by operations on the same lambda instance, and most die young. Each one costs a `WeakReference`, global table growth (to 4M slots on scalaz) and GC reference processing.
- **Lead change:** don't hash-cons a type that mentions a `ParamRef` of a `MethodOrPoly` (`ParamRef.computeHash` returns `NotCached`).
  - scalaz: −11% time, −9% cycles, −43% GC time.
  - tastyQuery: neutral.
  - Cost: about 23% more subtype calls on scalaz, from lost `eq` shortcuts, which the measurements show is outweighed.
  - Implicit caching is unaffected on scalaz.
- **Ruled out or parked:**
  - Per-binder tables that keep identity: worse than U.
  - Larger initial tables: noise.
  - Also uncaching types over HK lambdas under construction: noise.
- **Not done yet:**
  - `testCompilation` with U on by default (correctness).
  - Exp 10 round 2 on tastyQuery, plus dottyUtil and re2s.
  - Corpora with library dependencies (bench-lab only has the JDK and the Scala library on its classpath).
  - Turning the switch into a real change: remove the probe and the DO NOT MERGE commits, and write a comment and a test.

## Exp 11: how much churn happens under implicit search? (2026-10-13)

Idea (@retronym): demarcate an arena around implicit search, since a failing candidate's typer state is discarded, then drop the arena on failure or promote it on success. Probe on base, sampled events tagged by whether an `ImplicitSearch` frame is on the stack (`explore` is inline, so other speculative scopes are undercounted):

| | scalaz | tastyQuery |
|---|---|---|
| applied misses under implicit search | 207K of 2.34M (9%) | 6.5K of 37K (17%) |
| … surviving to the end of the run | 3–4% | 11–29% |
| uniques misses under implicit search | 28K of 424K (7%) | 1.9K of 29K (7%) |
| named misses under implicit search | 13K of 467K (3%) | 4.5K of 86K (5%) |

Most of it (162K of the 207K applied misses on scalaz) is under the search but outside `tryImplicit`: eligibility, implicit scope, ranking.

**Finding:** a clean scope with almost pure garbage on scalaz, but small coverage. The churn U removes is mostly in megaphase transforms, which have no speculative scope. An implicit-search arena is worth at most about 1% on top of U on scalaz, perhaps more on implicit-heavy code. Ranked below a bounded direct-mapped cache for method-param types, which would target U's measured cost (lost `eq` hits).

## Exp 12: a direct-mapped cache on top of U (2026-10-13 20:25–)

`-Ddotty.uncachedAppliedCache=<slots>`: under U, applied types over method/poly params have hash `NotCached`. AppliedUniques keeps a strong, direct-mapped array of them, indexed by a hash of the identity hash codes of `tycon` and `args` (their identity test is `tycon eq` and `args eqElements` anyway). A matching slot is shared; otherwise the new type replaces it. Cached types keep hash `NotCached`, so caches gated on the hash behave as under U. Retention is bounded by the slot count.

Probe, scalaz, U plus the cache:

| slots | cache lookups | cache hits | hit % |
|---|---|---|---|
| 1024 | 2.52M | 601K | 23.8% |
| 4096 | 2.52M | 623K | 24.7% |
| 16384 | 2.52M | 638K | 25.3% |

Base had 657K hits on these types in the global table, so 1024 slots win back about 91% of them: the hits have strong temporal locality.

Deterministic counters (`-Ydetailed-stats`, one scalaz compile):

| counter | base | U | U + 4K cache |
|---|---|---|---|
| subtype calls | 2.16M | 2.67M (+23%) | 2.28M (+5.5%) |
| `Symbol.denot` | 129.7M | 135.5M | 130.2M |
| `typeParams` | 13.22M | 13.91M | 13.30M |
| global AppliedUniques puts | 3.68M | 1.19M | 1.19M |

**Finding (so far):** the cache recovers most of U's identity cost while keeping its churn reduction. Timing is pending.

## Exp 12, profiled: base, U, U + direct-mapped cache keyed on part hashes (2026-10-13 21:31–21:43)

One corpus (scalaz), one run per variant: patched JDK 26, kperf cycles and instructions sampled every 10M events, 12 × 5 s warmup and 12 × 5 s profiled, `-Xms4G -Xmx4G` for all (`bench-lab/ab/prof-scalaz.json`). Compared with `bench-lab/scripts/profdiff.py`: cycles on the benchmark thread per op, from the samples. Indicative (one run each).

| variant | cycles/op | ms/op |
|---|---|---|
| base | 17.96G | 4465 ± 200 |
| U | 17.27G (−3.8%) | 4209 ± 96 |
| U + cache (part-hash keys) | 16.86G (−6.1%) | 4081 ± 96 |

Base → U: biggest decreases are all weak-table overheads, in cycles per op:
- `ReferenceQueue.poll` (via `ContinuationSupport.pinIfSupported`): −341M.
- `AppliedUniques.linkedListLoop`: −174M.
- `WeakHashSet.resize`: −129M.
- `Entry.tail_=`: −63M.

Increases are lost identity, spread thinly: `SimpleIdentityMap.apply` +58M, `Hashable.finishHash` +37M, `BaseClassSet.recur` +36M.

U → U + cache: most of that comes back (`SimpleIdentityMap.apply` −90M, `finishHash` −53M, `Type.loop` −44M, `widen` −35M), at a cost of +145M in the cache lookup (`enterUncached`) and about +46M of `identityHashCode` for uncached parts. Net −2.3%.

The first cache version keyed on identity hashes only, and on stock JDK 25 cost cycles: on fresh objects `identityHashCode` takes the VM slow path, and there each call toggles W^X write protection. Keying on the parts' own hashes fixed that.

**Finding (new):** in base, `ReferenceQueue.poll()` from `WeakHashSet.removeStaleEntries` takes 2.5% of compiler-thread cycles. 2.0% of that is AppliedUniques, which polls on every lookup and pays one locked `poll()` per stale entry; millions of entries die per compile. This is independent of the hash-consing semantics → Exp 13: a weak table without a reference queue, in the style of `ThreadLocal.ThreadLocalMap`. Lookups unlink cleared entries they pass, and a full sweep runs before growing, so the table grows only for live entries. That should also stop the table growing because of stale entries (4M slots for 417K live).

## Exp 13: weak tables without a reference queue (2026-10-13 21:47–22:07)

`-Ddotty.weakNoQueue`: entries are created without a `ReferenceQueue` and `removeStaleEntries` does nothing. When `count` reaches the threshold, the whole table is swept for cleared entries, and the table doubles only if live entries still fill three quarters of it (as in `ThreadLocal.ThreadLocalMap`). `resize` drops cleared entries. Cleared entries are not unlinked during lookups.

Same profile setup as Exp 12 (`bench-lab/ab/prof-exp13.json`). The first UDMh recording came back nearly empty (868 KB, against about 116 MB for the others; samples lost in the kperf helper), so it was rerun. `ab.py` now rejects kperf runs with implausibly few cycles per op on the benchmark threads.

| comparison | cycles/op | ms/op |
|---|---|---|
| base → NQ | 17.86G → 18.50G (+3.6%) | 4406 → 4550 |
| base → UDMh (U + cache keyed on part hashes) | 17.86G → 16.54G (−7.4%) | 4406 → 4005 |
| UDMh → UDMh + NQ | 16.54G → 16.99G (+2.7%) | 4005 → 4108 |

Base → NQ:
- **Saved:** `ReferenceQueue.poll` (via `pinIfSupported`), −459M.
- **Added:** `linkedListLoop` +391M and `Reference.get` +161M, because cleared entries stay in the chains until a sweep; `addEntryAt` +290M, the inlined full-table sweeps, which recur because garbage refills the table quickly.

**Finding:** rejected. With millions of short-lived entries the queue pays for itself: it names exactly the entries that died, which is cheaper than scanning for them. Under UDMh the remaining poll cost is only 120M cycles/op (0.7%), so there is little left to win there. UDMh reproduces: −7.4% here against −6.1% in Exp 12.

## Exp 14: U + cache on the other corpora, profiled (2026-10-13 22:12–22:30)

Same profile setup (`bench-lab/ab/prof-corpora.json`): base against UDMh (U plus the direct-mapped cache keyed on part hashes), one run each. The first tastyQuery base run overlapped other load on the machine (load average 12.9; 1001 ms/op) and was rerun. `profdiff.py` now counts ops per iteration from each iteration's mean; counting from the overall score skewed sub-second benchmarks.

| corpus | base cycles/op | UDMh cycles/op | Δ cycles | ms/op base → UDMh |
|---|---|---|---|---|
| scalaz (Exp 13) | 17.86G | 16.54G | −7.4% | 4406 → 4005 |
| tastyQuery | 3.38G | 3.34G | −1.1% | 845 → 805 |
| re2s | 1.11G | 1.08G | −2.1% | 266 → 261 |
| dottyUtil | 0.64G | 0.68G | +4.9% | 156 → 161 |

On the small corpora no method's self cycles per op move by more than about 9M. The top entries are the same methods moving between compiled and inlined frames ([i]/[j]), i.e. JIT variation, and the dottyUtil delta is spread across the whole profile. The one attributable change on tastyQuery is the expected one, `WeakHashSet.resize` −6M.

**Finding:** neutral within run-to-run noise on corpora with little method-param churn, and −7.4% on scalaz, which has about 100× more. No regression attributable to the change.
