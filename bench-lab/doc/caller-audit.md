# Caller audit: which callers rely on X?

## Problem

Some performance questions are about usage, not time: before changing what a method does, we need to know who relies on its current behaviour, how often, and in what proportion. The Positioned work asked this of `Positioned.envelope`: how often does it actually fill in a child's span, and which code created the span-less children it fills? A profiler cannot answer that, because the answer is a set of call sites with counts, attributed by a condition (the branch that fills a span), not by time spent.

A prototype, `CallerAudit` in the compiler (branch `claude/positioned-cache-misses-95c686`), answered it: `envelope` fills a child in 2–4% of copier calls and 3–9% of other constructor calls, and the span-less children come from 120–350 creation sites, dominated by a dozen `tpd` factories. The unique caller set also turned out to be a sample of how an API is used.

The prototype has to be edited into the compiler and rebuilt for each question, takes a lock on every hit, records every hit, and writes one text report per JVM.

## Key decisions

The mechanism is perflab's: probes woven in by a Java agent at class load, sites optionally qualified or tagged, per-thread counts merged at exit, JSON reports folded afterwards ([retronym/jvm-perf-lab's doc/caller-audit.md](https://github.com/retronym/jvm-perf-lab/blob/main/doc/caller-audit.md)). What stays here:

- **Specs** (`audit/*.audit`), naming dotc's methods.
- **Predicates** (`audit/Predicates.scala`), static boolean methods compiled against the compiler, so a spec's `when=` can use dotc's own types (`span.exists` on an opaque type) without the agent interpreting expressions.

`perflab run --profile audit:<spec file> -- <jmh args>` adds the agent to the benchmark fork and merges the report; a short run will do, since the run is for counts rather than timing.

## Prior art

- **The prototype itself:** `dotty.tools.dotc.util.CallerAudit`.
- **BTrace and Byteman:** probes declared outside the code and woven at load time. This is the same idea, minus their scripting languages: our probes record sites, nothing more.
- **JFR's `jdk.ObjectAllocationSample` and async-profiler's allocation stacks:** sampled stack attribution, but of allocations by bytes, not of arbitrary program points.
- **`-Ydebug`-style counters in dotc (`Stats.record`):** counts per name, no sites.

## Plan

- DONE 1. Runtime, agent, spec parser, report folding (since moved to perflab), predicates (`audit/Predicates.scala`). Spec of the envelope audit: `bench-lab/audit/positioned-envelope.audit`.
  Check against the prototype (one dottyUtil compile, master): fills 2241 under the copier and 10812 others, as the prototype's; constructor envelopes 38685 under the copier (prototype 38674) and 116716 others (114917); `withSpan` envelopes 2204 and 19623 (2204, 19601). The prototype ran on its branch, whose source differs a little from master. Distinct sites differ more (filler 553 vs 469), for the same reason or a difference in which frames are skipped: not yet pinned down.
- DONE 2. On perflab's agent: the envelope audit's counts are the same as with bench-lab's own (dottyUtil, `-wi 1 -i 1`).
- Future work: conditions over arguments (e.g. on `p` rather than the receiver); probes on field writes; probes at a call site inside a method, for callers that `from=` cannot tell apart.
