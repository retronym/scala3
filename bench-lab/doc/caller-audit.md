# Caller audit: which callers rely on X?

## Problem

Some performance questions are about usage, not time: before changing what a method does, we need to know who relies on its current behaviour, how often, and in what proportion. The Positioned work asked this of `Positioned.envelope`: how often does it actually fill in a child's span, and which code created the span-less children it fills? A profiler cannot answer that, because the answer is a set of call sites with counts, attributed by a condition (the branch that fills a span), not by time spent.

A prototype, `CallerAudit` in the compiler (branch `claude/positioned-cache-misses-95c686`), answered it: `envelope` fills a child in 2–4% of copier calls and 3–9% of other constructor calls, and the span-less children come from 120–350 creation sites, dominated by a dozen `tpd` factories. The unique caller set also turned out to be a sample of how an API is used.

The prototype has to be edited into the compiler and rebuilt for each question, takes a lock on every hit, records every hit, and writes one text report per JVM.

## Key decisions

1. **Probes are woven in by a Java agent, not written into the compiler.** A probe names a method of the compiler (`Class#method`, at its entry or before its returns, optionally only when called from a given method) and is woven in when the class loads. An audit then needs no compiler edit or rebuild, works on any build including released ones, and costs nothing when the agent is absent. Writing probes into the source stays possible (a small runtime API), for conditions too awkward to name from outside.

   Probes sit at method boundaries, with a caller filter (`from=`) and a predicate (`when=`), rather than at lines: lines move from build to build, and "this method, called from there" named `envelope`'s fill branch exactly (the call from `include`).

2. **A probe records a site, optionally qualified.**
   - The site is the first frames of the stack after dropping those a regex matches, e.g. `Positioned` internals and tree constructors.
   - It can be qualified by the class of the receiver or of an argument, e.g. which tree class.
   - A probe can instead tag an object with its site (weakly, by identity), and another probe can read that tag back. This pairs the code that made an object with the code that later relied on it: the creation site of a span-less tree, read at the `envelope` that fills it.
   - Plain counters give the denominators.
   - Tagging at constructor exit is a probe kind of its own, guarded by a predicate on the new object (e.g. a tree created without a span). In the prototype this pairing was the most useful qualifier. A predicate is a static boolean method named in the spec, in a small helper class compiled against the compiler. So the agent never interprets expressions, and the helper sees the compiler's own types (for `span.exists`, an opaque type).

3. **Cheap enough to leave on for a whole JMH run.**
   - Counts are kept per thread and merged at the end, instead of taking a lock per hit.
   - A hot probe can record one hit in N, with the total still counted exactly. Shares stay right, while the rare sites a full count would show may be missed.

4. **Reports are data, folded afterwards.**
   - Each JVM writes JSON: counters, and per probe its sites with counts.
   - A script merges reports across runs and corpora and folds sites, by method or by the first frame outside a package. It then prints the table that answers the question, e.g. "fill share by creation site, across corpora".

5. **A bench-lab command runs an audit**: `benchLabAudit <spec> <jmh args>`. The spec is a small file of probes. The command adds the agent to the benchmark fork and runs JMH, with few iterations, since the run is for counts rather than timing. It then merges the report into `out/bench-lab/audit-<spec>`.

## Supporting changes

- A Java agent jar built by sbt from bench-lab sources (`benchLabAuditAgent`). It weaves with the ASM on the benchmark's classpath (JMH's generator brings it) rather than the ClassFile API, which sbt's JDK 21 lacks.
- The runtime API from the prototype (`probe`, `count`, `tag`/`tagOf`, `onStack`), moved to bench-lab and made thread-local and sampled. The woven code calls it.

## Prior art

- **The prototype itself:** `dotty.tools.dotc.util.CallerAudit`.
- **BTrace and Byteman:** probes declared outside the code and woven at load time. This is the same idea, minus their scripting languages: our probes record sites, nothing more.
- **JFR's `jdk.ObjectAllocationSample` and async-profiler's allocation stacks:** sampled stack attribution, but of allocations by bytes, not of arbitrary program points.
- **`-Ydebug`-style counters in dotc (`Stats.record`):** counts per name, no sites.

## Plan

- DONE 1. Runtime (`audit/Audit.java`: concurrent counts, sampling, weak identity tags, JSON report), agent (`audit/AuditAgent.java`, `audit/Spec.java`), predicates (`audit/Predicates.scala`), `scripts/auditfold.py` (merge reports, fold by method or first frame outside a package), `benchLabAudit <spec> <jmh args>`. Spec of the envelope audit: `bench-lab/audit/positioned-envelope.audit`.
  Check against the prototype (one dottyUtil compile, master): fills 2241 under the copier and 10812 others, as the prototype's; constructor envelopes 38685 under the copier (prototype 38674) and 116716 others (114917); `withSpan` envelopes 2204 and 19623 (2204, 19601). The prototype ran on its branch, whose source differs a little from master. Distinct sites differ more (filler 553 vs 469), for the same reason or a difference in which frames are skipped: not yet pinned down.
- Future work: conditions over arguments (e.g. on `p` rather than the receiver); probes on field writes; probes at a call site inside a method, for callers that `from=` cannot tell apart.
