# Replayable compiler: design

Status: design draft for review. Nothing implemented. Authored with LLM assistance (see `LLM_POLICY.md`).

## Problem

We want to record a `dotc` run in a form that a UI can scrub through: watch the parser output become typed trees, see implicit search and subtype checks that justified each node, then watch each lowering phase rewrite the tree down to erased, lambda-lifted, mixin-expanded code. The audience is learners ("what does `for` desugar to, why did this `given` win") and compiler developers ("which phase introduced this node, who forced this completer").

Second goal: the same recording for a whole project, as a zoomable graph where symbols, files and packages cluster by how they reference each other, and edges appear as the compiler resolves them.

## What dotc already gives us

- **Trees are persistent.** `TreeCopier` (`ast/Trees.scala:1272`) returns the same node when children are unchanged, so consecutive phase outputs share most of their structure. A "version" of a program is a root pointer; diffing two versions is a walk that skips on `eq`. This is the property the whole design leans on. (`-Xprint-diff` already exploits it for text.)
- **Nodes have identity.** `Positioned.uniqueId` plus `span`. Trees carry `Attachment`s (`util/Attachment.scala`), a natural sparse place for provenance on the few nodes a transform touches.
- **Mutation is rare and localised.** Tree types (`overwriteType`, `Trees.scala:70`), lazy `preRhs`/`preBody` (`LazyTree`), and denotations, which are per-period (`core/Periods.scala`) and rewritten by `InfoTransformer`s (`core/DenotTransformers.scala`). These are exactly the places the recorder must hook.
- **Phases are explicit.** `Phase.runOn`, `MegaPhase` (fused mini-phases, which hides intermediate states unless we log per-miniphase), `Run.runPhases`.
- **Typer is demand driven.** `Namer.Completer` (`typer/Namer.scala:877`) types signatures lazily, so typing order is data-dependent and crosses files. That order is itself the most educational thing to show and the main source of cross-file edges.
- **Tracing scaffolding exists.** `reporting/trace.scala` (indented `trace` blocks in typer/TypeComparer) and `profile/ChromeTrace.scala` (span events). Neither records tree or symbol identities.

## Prior art (shallow search; verify before citing in docs)

Nothing found that covers typechecker decisions *and* lowering *and* project scale. Pieces exist:

- **[Graal IGV](https://www.graalvm.org/latest/tools/igv/)** (Ideal Graph Visualizer): per-phase graph dumps with diff and phase slider. Closest to the "phase axis" half; no typechecking.
- **[JITScope](https://arxiv.org/abs/2505.21599)**: JIT IR nodes across phases, normalised into SQLite, D3 frontend. Same architecture as what I propose (log to relational store, browser viewer).
- **[Calyx pass explorer](https://docs.calyxir.org/dev/calyx-pass-explorer.html)** and the **[LLVM IR VS Code RFC](https://discourse.llvm.org/t/rfc-ir-visualization-with-vs-code-extension-using-an-lsp-server/87773)**: pass-by-pass stepping on IR.
- **Compiler Explorer's LLVM pipeline view** and GHC's `-ddump-*` family: textual per-pass diffs.
- **DrRacket Macro Stepper**: the best precedent for "scrub through expansion", with hygiene shown by colour. Event-driven, operates on syntax objects with provenance.
- **Lean infoview / trace classes**: collapsible elaboration trace tree attached to source positions.
- **rustc trait-solver proof trees** ([`solve::inspect`](https://doc.rust-lang.org/nightly/nightly-rustc/rustc_infer/traits/solve/inspect/index.html)) and **Argus** ([Debugging Trait Errors as Logic Programs](https://arxiv.org/pdf/2309.05137)): a recorded solver tree rendered as an explorable UI. Direct precedent for implicit search and subtype tiers; the paper also warns that raw proof trees are too big to skim, so we need filtering and "why-this-one" views.
- **Aquascope** (Brown): interactive borrow-checker/ownership visualisation hooked into rustc; educational framing.
- **TypeScript `--generateTrace`** with the trace analyzer: Chrome-trace format of checker events plus a `types.json` id table. Good model for "events refer to interned type ids".
- **SML/NJ replay debugger** (Tolmach & Appel) and **TARDIS**: replay by checkpoint plus deterministic re-execution. Relevant to the "re-run with more detail" escape hatch below.
- Scala-side: scalac 2's `TreeBrowsers`, [s3te](https://users.scala-lang.org/t/releasing-s3te-a-scala-3-tree-explorer-to-help-you-inspect-scala-code-as-seen-by-the-reflect-module/10337) (static HTML tree of reflect trees), scalameta AST explorer, `-Vprint`/`-Xprint-diff`. All single-snapshot or text.

Novel here: one data model spanning typer events, tree lineage and symbol dependencies, and the scale-out.

## Core idea

Do not snapshot trees. Record an **append-only arena of nodes** (persistent, so unchanged subtrees cost nothing), a **lineage relation** (new node derived from old node(s) by transform T), and a **causal event log** (everything that is not a tree rewrite). Scrubbing is a fold over the log.

### Layers

**L0: Arena.** Dense integer ids, struct-of-arrays, append-only.
- `Node`: kind, child ids, span, name/constant, `symId`, `typeId`.
- `Type`: interned by structure (dotc already hash-conses most types); `Symbol`: `symId` with owner, flags-at-time, name.
- A tree is added to the arena the first time the recorder sees its identity (`IdentityHashMap[Tree, NodeId]`, weak). A node reached again through structure sharing is not re-added. That is the dedup.

**L1: Versions.** `(phaseId, unit) -> rootNodeId`, plus the phase's own metadata (name, miniphase list, whether it is a denot/info transformer). Snapshotting at every phase boundary is cheap because of sharing and gives the coarse scrub axis for free.

**L2: Lineage.** `Derive(newId, oldIds[], transform, reason)`. Recorded where trees are rebuilt: `TreeCopier`, `MegaPhase` per-miniphase `transform*`, `TreeTypeMap`, `Desugar`, inliner. Kinds: `Copy` (same shape, child changed), `Rewrite` (e.g. `TailRec`), `Split` (one old → many new, e.g. `Constructors`, `LambdaLift` hoisting), `Merge`, `Synth` (no ancestor; with the phase that made it), `Elide`. Lineage is what lets the UI animate a morph and answer "where did this node come from".

**L3: Events.** Totally ordered per unit, tree-structured by `parentSeq`:

```
Event(seq, parentSeq, kind, phase, unit, span, refs: [Id], payload)
```

Kinds, in tiers by cost:
- Tier 0, phase: `PhaseBegin/End`, `MiniphaseBegin/End`, `Version`.
- Tier 1, typer judgments: `Typed(untpdNode → tpdNode, pt)`, `Adapt(step: widen|etaExpand|applyImplicit|insertConversion|…)`, `Resolve(identNode → sym, scopesTried, importsConsidered, ambiguity)`, `Complete(sym, forcedBy)`, `Desugar(old → new, rule)`, `Inline(callsite → expansion)`, `MacroExpand`.
- Tier 2, search: `ImplicitSearch(pt)` with `Candidate(sym, accepted|rejected, why)` children (this is the proof tree that Argus/rustc render).
- Tier 3, solver: `SubtypeQuery(tp1, tp2, result)` nested, `ConstraintAdd/Instantiate(tvar, bound)`, `Capture-set` steps for cc. Enormous; only recorded under a focus (below).
- Denotation timeline: `InfoChange(sym, phase, oldInfo → newInfo)` from `InfoTransformer`s, `SymCreated(sym, phase, owner, by)`, `FlagChange`. This is how "erasure turned `List[Int]` into `List`" or "lambdalift moved this def to the outer class" gets shown against the symbol, not just the tree.
- Diagnostics: `Report(severity, msg, span)` linked to the event that produced it, so an error can be scrubbed *to*.

Event order is the compiler's real execution order, including lazy completion detours; a `Complete` event nests inside whatever forced it, which is how cross-file edges get their causality.

### Scrub semantics

Two axes, one cursor.
- **Coarse axis**: phase. Jump = pick `Version(phase)`.
- **Fine axis**: event `seq`. State at `seq` = phase input version + all `Derive` events ≤ `seq` applied as substitutions (`oldId → newId` overlay). Rendering is: walk input tree, at each node follow the overlay. Cost is proportional to the visible region, not the program.
- Typer is the same fold with a different base: base is the untyped tree, `Typed` events turn nodes into typed ones in execution order, `Resolve`/`Adapt` children decorate them. Types appear on nodes as they are assigned; `overwriteType` shows up as a second `Typed` for the node.

Stepping granularity is a viewer setting: step by phase, miniphase, top-level def, tree node, or event.

## Piggybacking on real trees

Options considered:

1. **Side table + observer hooks (recommended for the prototype).** No change to `Trees`; `ctx.replay: Recorder | Null` read at the hook sites (`TreeCopier`, `Typer.typed`/`adapt`, `Phase.runOn`, `MegaPhase`, denot-transformer driver, `Namer.Completer`, `ImplicitSearch`, `TypeComparer` under `trace`). When null the cost is a field read plus branch per hook; measure this against `bench-lab` before upstream. Keeps the production tree layout unchanged.
2. **Provenance attachment.** `putAttachment(Origin, oldNode)` on rewritten nodes only. Sparse, zero cost for untouched nodes, survives being passed through code that doesn't know about the recorder. Useful where the *transform* knows the ancestry but the copier doesn't (e.g. `Desugar`, `Inlines`). Likely a small, targeted complement to (1), not a replacement.
3. **Dedicated `origin` field in `Positioned`.** 8 bytes per node on every compile. Rejected: pays always for a diagnostic feature.
4. **Reuse TASTy as the snapshot format.** TASTy gives stable addresses and sharing for typed trees, but only exists after `Pickler`, and has no lineage. Fine as an *input* for replaying a library's trees (unpickled trees get `Synth(from-tasty)` provenance), not as the recording format.
5. **Post-hoc diff (GumTree-style) between `-Xprint` outputs.** Needs no compiler change but guesses lineage and cannot see typer events. Useful as a fallback for phases we haven't instrumented yet.

Hook points are deliberately at the *constructors of change* (copier, typer entry/exit, denotation transform), not on every tree accessor.

Constraints that shape the recorder:
- **Determinism and threads.** Record with parallel pickling/backend off (`-Yno-parallel-pickling` style flags) and one thread. Per-unit `seq`; cross-unit order comes from `Complete`/forcing events (happens-before).
- **Fused mini-phases.** Log per-miniphase transforms inside `MegaPhase`, otherwise ~10 logical phases collapse into one scrub step.
- **Tree identity vs. copies by `withSpan`/`withType`.** These copy lazily; the recorder interns on first observation by identity, so a `Copy` lineage edge with reason `retype`/`respan` is emitted and the viewer can collapse trivial ones.
- **Mutation of already-recorded nodes** (`overwriteType`, lazy RHS forcing): record as an event against the existing id, never rewrite arena rows. The arena stays append-only, so a recording can be streamed and replayed while the compile is still running.

## Verbosity and the "re-run for detail" escape hatch

Tier 3 for a whole project is not feasible to store. Because the compiler is deterministic once single-threaded, a recording header stores (sources hash, options, classpath hash, compiler version). The UI can ask "give me Tier 3 for the `Typed` event at span X": re-run, record only the subtree under that focus, splice into the store. This is the SML/NJ-style replay idea applied at coarse grain, with no checkpointing machinery. Needs a repro-stability test (two runs, identical event stream) as a CI gate.

## Storage and size

Rough, to be measured (not yet instrumented): typed trees for a 100 kLoC project are plausibly low millions of nodes; lowering rewrites a minority per phase but concentrated in a few phases (desugar-ish, `PatternMatcher`, `Erasure`, `LambdaLift`, `Constructors`, `Mixin`). Persistent sharing keeps total arena at a small multiple of the initial typed tree. The event log, especially Tier 2, will dominate.

Format: columnar files (Parquet or Arrow IPC), one table per concept: `nodes`, `types`, `symbols`, `versions`, `lineage`, `events`, `edges`. Benefits: compresses well (dense int ids, repeated kinds), streams, is queryable from the browser via DuckDB-WASM, and is **directly queryable by an LLM with SQL**. "Why was this given selected", "which phases touched this def", "what was the longest completion chain" become queries; so does generating a narrated walkthrough of one definition from its event subtree. The JITScope paper made the same SQLite choice for the same reason.

Pretty-printing is *not* stored; the viewer renders from node tables, with a shared printer in the UI so output matches `-Xprint` closely enough to be familiar.

## Scale-out: whole project view

Treat the recording as a graph that grows over event time.

**Nodes**: packages > files > top-level classes/objects > members (Tier of detail by zoom). Trees only materialise at the deepest zoom.

**Edges**, each stamped with the `seq` at which the compiler established it, so edges *appear* as the cursor advances:
- `resolves-to`: from `Resolve(ident → sym)`; aggregated to the enclosing member/class/file.
- `forces`: from nested `Complete` (A's typing demanded B's signature). Shows laziness and cycle risk.
- `extends/overrides/refines`: from class completion and `OverridingPairs`.
- `implicit-used`: from `ImplicitSearch` accepted candidates (long-range edges into givens/extension containers).
- `inlined-from`, `macro-from`: cross-file code flow.
- `lowered-into`: later phases, e.g. nested class → flattened top-level, lambda → anonymous class, hoisted local def → owner. The graph itself changes across phases because symbols move (Flatten, LambdaLift, Mixin); this is where `InfoChange`/`SymCreated` earn their keep.

**Layout and clustering**: compute once, offline, on the *final* weighted graph so the picture is stable while scrubbing:
1. Hierarchy from package/file structure as a prior.
2. Community detection (Leiden) on edge weights to find clusters that cut across packages; show both the declared and the discovered grouping so differences are visible.
3. Per-cluster force-directed layout (ForceAtlas2 style), clusters placed by a coarse pass; positions stored in a `layout` table with a level-of-detail pyramid.
4. Edge bundling along the hierarchy so thousands of edges stay legible.
Time only controls *visibility and heat* (edges fade in, active symbols pulse, size by events spent), never position. Re-layout on demand for the lowering phases where the node set changes (layout per "symbol-universe epoch", keyed to phases that create/move symbols).

**Rendering**: WebGL/WebGPU graph renderer (Sigma/deck.gl class) for the overview, DOM/SVG or canvas for the focused tree view. Semantic zoom swaps representation: cluster blob → file card → class outline → annotated tree with typer events.

**Navigation**: the project view and the tree view share the cursor. Clicking a member opens its tree at the current `seq`; clicking a `Resolve` edge jumps the cursor to the event.

**Where LLMs help** (secondary, after the data model works): cluster labels and short summaries from member signatures; narration of an event subtree for the educational mode ("here the compiler tries three givens; the first is rejected because…") grounded in structured event payloads rather than free text; answering questions over the SQL store. Keep the factual layer deterministic; the model only narrates it.

## Review of the draft (second pass)

Problems found, in order of how much they change the plan:

1. **Tree identity does not capture meaning.** The same `tpd.Tree` node prints differently in different phases because `tree.symbol.info`, flags and owner are period-dependent (`Denotations`), and `Typed`-phase types reference symbols whose later denotations differ. A node snapshot keyed by identity therefore shows the wrong types when viewed at a later phase unless the viewer also consults the denotation timeline. Consequence: `InfoChange` is not an optional symbol-centric view; it is required for correct rendering of *any* version. The v0 record set must include a `sym_info(symId, fromPhase, infoTypeId, flags, owner)` table and the node's rendered type must be resolved per (typeId, phase).
2. **No shared interchange schema** between compiler-side and UI-side work, which blocks parallelism. Fix: freeze a small JSONL schema (v0, below) before spawning. Parquet/DuckDB is deferred; JSONL is fine for a PoC on single files.
3. **"Viewer renders from node tables" is too much for a PoC**: a JS re-implementation of `RefinedPrinter` is weeks of work and a fidelity trap. Instead have the compiler emit, per version, the printed text plus `nodeId → [start, end)` offsets, by instrumenting the printer (`Texts.Text` has no node identity; wrap `toTextCore` in `RefinedPrinter` to push/pop markers). The viewer then does rendering by offsets. Node tables stay for structure/diff/lineage, not for pretty printing.
4. **P0 does not need a compiler fork.** `fusePhases` (`core/Phases.scala:74`) fuses by the grouping in `Compiler.phases`, not by `-Vprint`; a `ResearchPlugin` (`plugins/Plugin.scala:71`) receives the whole phase plan and returns a new one, so it can (a) split every `MiniPhase` into its own group, unfusing every `MegaPhase`, and (b) interleave a `Record` phase after every phase. Research plugins require a SNAPSHOT/NIGHTLY/nonbootstrapped compiler (`config/Properties.scala:88`), which we have locally. So the phase axis, versions and per-phase pretty text are achievable with zero compiler changes. Lineage and typer events (P1/P2) still need in-tree hooks; that becomes the fork's first real diff.
5. **Unfusing changes semantics slightly** (miniphases assume `runsAfterGroupsOf`; the `MegaPhase` fusion contract allows cross-miniphase `prepareFor*` ordering). Unfused runs should be `-Ycheck:all`-ed to confirm equivalence; if not equivalent, fall back to a per-miniphase hook inside `MegaPhase.transformNode`.
6. **Lineage from `TreeCopier` is weaker than stated.** The copier sees `tree → copy` but a miniphase that constructs fresh trees via `tpd.*` factories (most lowering) produces nodes with no copier edge. The pragmatic rule: a `Record` pass diffs version N vs N+1 by identity and span; new nodes whose span equals an old node's span get a `Rewrite` edge by heuristic, and only phases that need better get explicit `Origin` attachments. GumTree-style matching is the fallback, not a separate option.
7. **Scrubbing the typer in event order is ambitious**; the untyped→typed fold crosses files via completers and the typer rebuilds the whole tree. For the PoC, typer replay should be *event-indexed annotations on the typed tree* (click a node → its `Typed`/`Resolve`/`ImplicitSearch` subtree, cursor highlights the node current at seq), not a morphing untyped tree. Morphing is P3+.
8. Missing: an explicit budget/size experiment, and a fixture corpus (3–5 small programs that exercise desugaring, implicits, pattern matching, lambdas, mixins) so compiler and UI work converge on the same data.

## v0 interchange schema (JSONL, one record per line; freeze before parallel work)

```
{"t":"meta","compiler":"3.x","args":[...],"units":[{"id":0,"path":"..."}]}
{"t":"phase","id":12,"name":"erasure","mini":true,"megaOf":"erasure-group"}
{"t":"node","id":n,"kind":"Apply","span":[s,e],"unit":0,"children":[ids],"sym":symId|null,"tpe":typeId|null,"name":"..."}
{"t":"version","phase":12,"unit":0,"root":n,"text":"<printed>","offsets":[[n,s,e],...]}
{"t":"sym","id":s,"name":"...","owner":s2,"createdPhase":p,"createdBy":evSeq|null}
{"t":"syminfo","sym":s,"fromPhase":p,"info":"<printed>","flags":"...","owner":s2}
{"t":"type","id":k,"text":"<printed at creation>"}
{"t":"derive","phase":p,"new":[ids],"old":[ids],"how":"copy|rewrite|split|synth|elide|heuristic"}
{"t":"ev","seq":q,"parent":q0|null,"kind":"Typed|Adapt|Resolve|Complete|ImplicitSearch|Candidate|Report",
   "phase":p,"unit":u,"span":[s,e],"nodes":[ids],"syms":[ids],"payload":{...}}
{"t":"edge","kind":"resolves|forces|extends|implicit|inlined","from":symId,"to":symId,"seq":q}
```

Ids are dense ints per recording. Everything is append-only. UI work starts from a hand-written fixture in this format; the compiler agent's done-criterion is "emits valid v0 for the fixture corpus".

## PoC plan: parallel tracks

Three independent tracks, one subagent each, all starting from this doc and the v0 schema. Each track returns a short report into `local/replay/<track>/REPORT.md`; nothing goes upstream. Expected elapsed: one working session per track.

**Track A, compiler integration (research plugin, no fork).** Goal: `-Xplugin:replay-record` on the fixture corpus emits v0 JSONL with `meta`, `phase`, `node`, `version` (text + offsets), `sym`, `syminfo`, and heuristic `derive`. Steps: (1) `ResearchPlugin` that unfuses all `MegaPhase`s and inserts a `Record` phase after every phase; run `-Ycheck:all` on the corpus unfused vs. fused and report differences. (2) `Record` phase: `IdentityHashMap`-based arena, per-unit walk, emit nodes new since last version. (3) Printer instrumentation: subclass `RefinedPrinter`, override `toTextCore` to wrap output in marker `Text`s, flatten to text + offsets. (4) `syminfo` by iterating symbols reachable from the unit's tree at each phase and emitting when `info`/`flags`/`owner` differ from the last emission. (5) Span-equality `derive` heuristic. Report: node counts per phase, share ratio, wall-clock overhead, which miniphases misbehave unfused. Lives under `local/replay/plugin/` as a small sbt or scala-cli project depending on the locally published compiler.

**Track B, data model and typer events (in-tree, exploratory fork).** Goal: validate that Tier 1/2 events can be captured with a handful of hooks, and measure their volume. Steps: (1) add a `Recorder` slot on `ContextBase` (null by default); (2) hooks in `Typer.typed`/`adapt` entry/exit, `Namer.Completer.complete`, `Implicits.inferImplicit` (search + each candidate's result), `report.*` for diagnostics; (3) emit `ev` and `edge` records for the corpus; (4) count events per tier per kLoC, estimate project-scale volume, and check two consecutive runs produce identical streams (reproducibility for re-run-for-detail). Also prototypes the `TreeCopier` hook to check how much lineage it recovers vs. Track A's heuristic. Report: hook list with diff size, event volumes, reproducibility verdict, recommendation on which hooks are worth upstreaming behind a null check. Must be its own worktree; it edits `compiler/src`.

**Track C, UI concepts (no compiler needed).** Goal: prove the three views on a hand-written v0 fixture, in a static HTML/JS page (no build step). (1) Phase scrubber: slider over `version`s, render text, highlight spans whose node ids are new in this version, with a "where did this come from" hover using `derive`. (2) Event inspector: click a span → collapsible `ev` subtree (`Typed`/`Resolve`/`ImplicitSearch` with candidates) for that node; cursor on the event axis highlights the current node. (3) Project graph: 30–50 synthetic `sym`+`edge` records with `seq`, force layout computed once, edges fade in as the cursor moves; one Leiden-style cluster colouring (any simple community algorithm is fine for the fixture). Report: which v0 fields were missing or awkward, proposed schema changes, screenshots.

**Integration step (after A and C):** run Track A's plugin on the corpus, load the real JSONL into Track C's page, record what breaks. Then decide fork vs. plugin and revise this doc.

**Fixture corpus** (`local/replay/corpus/`), written first by me, shared by all tracks: `ForDesugar.scala` (for-comprehension, string interpolation), `Givens.scala` (ambiguity, extension method, conversion), `Patmat.scala` (sealed match, guards), `Closures.scala` (lambdas, local defs → LambdaLift), `Mixins.scala` (trait with concrete members, super calls). Each under 40 lines.

## PoC results (2026-10-18)

Tracks A, B, C ran in parallel on the corpus. Reports: `local/replay/plugin/REPORT.md`, `local/replay/REPORT-trackB.md` (worktree `replay-typer-hooks-c5e1db`, branch `claude/replay-typer-hooks-c5e1db`, local commits), `local/replay/ui/REPORT.md`. Everything below is measured, not estimated, unless marked.

### What held up

- **No-fork phase recording works.** A `ResearchPlugin` unfuses miniphases and interleaves a `Record` phase; recordings validate, are byte-identical across runs, and leave `.class`/`.tasty` output unchanged. Printed text with node offsets via a marker-emitting `RefinedPrinter` subclass verified on 146k slices with 0 mismatches.
- **Few hooks suffice for typer events.** Eight sites, ~40 lines outside a 356-line `Recorder`. `Typed`, `Complete`, `ImplicitSearch`/`Candidate`, `Report`, `TreeCopier` lineage, `resolves`/`forces`/`implicit` edges. Disabled overhead not measurable by crude timing (needs bench-lab).
- **The stream is reproducible**, so re-run-for-detail is viable. Two fixes were needed: content hashes for `Name`/`NameKind` (identity hashes made `memberNames` iteration order depend on JVM state, visibly changing which implicit candidates were tried), and never printing types at hook time (printing forces completions, which hides `Complete` events). Gate: record with `REPLAY_PERTURB_IDHASH` 0 and 1000, compare.
- **The three views work on a fixture** with one shared cursor; the loader survives malformed input. **Track A's real output loads** (5–30 ms per file, ~1 ms per version render) with no exceptions. Needed UI changes: a phase `<select>` for 92 versions, "skip no-op versions" (92 → 13–69 depending on file), "collapse pure copies" recomputed viewer-side from derive+node tables (matches Track A's counts within a few points), and parenthood from offset nesting rather than `node.children`. Only the scrubber view is exercised; inspector and graph need events with ids aligned to the arena.

### What did not

- **Unfusing is not semantically safe.** Two miniphase pairs depend on fusion order: `mixin → memoize` (fused output is *wrong*; it is the cause of scala/scala3#25108) and `lazyVals → capturedVars` (fused is right by accident). Reports and reducers in `local/replay/bugs/`. Nothing in the build tests the fusion-is-only-an-optimisation invariant. For recording, keep that group fused (one scrub step for five miniphases) or hook `MegaPhase.transformNode` in the fork.
- **Arena is 4.6–11× the typed tree, not a "small multiple".** Cause is copy noise, not lowering: `TimeTravellingTreeCopier` re-runs `assignType` for `TypeApply`, instantiating a fresh uncached `MethodType`, so `withType` sees `ne`, clones the node and its whole ancestor spine once per phase. 9–76% of post-typer nodes are pure copies. Fix at the source in the fork (keep the tree when the new type `=:=` old), and it may be a small allocation win for ordinary compiles; check with bench-lab.
- **Phase-id budget.** `PhaseWidth = 8` → 255 phases; unfused + Record is ~215, so `-Ycheck:all` on top overflows.
- **Volume.** Typer-side: 15k events/kLoC, 5.7 MB/kLoC JSONL → ~570 MB per 100 kLoC uncompressed, half of it Erasure/Inliner re-typing `Typed` events that are lineage, not judgments. Node/derive/ev rows are small ints and repeated kinds; columnar should be ~10× smaller (not measured). Recording costs 4–8× compile time in the PoC (strong identity maps, full-tree walks, printing every version); 23–77 of 92 versions add no nodes and need no print.
- **Rejected-candidate reasons are mostly invisible.** Only 5 of 445 `Candidate`s in `util` were rejections at `tryImplicit`; the rest are pruned earlier in `eligible`/`compareEligibles`/rank pruning. The Argus-style view needs hooks there.
- **Lineage.** Copier accounts for 5–84% of new nodes per phase group (median ~50%); the span heuristic matches 73–100% but is ambiguous for 23–74% of matches. Union leaves 0–25% unexplained, worst in `patternMatcher` (25%) and `constructors` (15%), which build with `tpd.*` factories. Keep copier for precision, heuristic for coverage, add `Origin` attachments in those two phases first.
- **Review point 1 confirmed in practice**: node `tpe` recorded at first sight prints wrongly after erasure. Fix is per-version type text for shared nodes, or trusting only `version.text`.

### Decisions

1. **Architecture: compiler owns a small observer interface; the recorder is a plugin.** Upstream candidates, in order: deterministic `Name` hashes (independent value); `recorder` slot plus `Typer.typed`, `Completer.complete`, `inferImplicit`/`tryImplicit` hooks as a `TyperObserver` trait; `TreeCopier.finalize` hook (measure separately). `adapt` and `Reporter.report` wait.
2. **Keep `mixin..capturedVars` fused** until `MegaPhase.transformNode` hooking exists; file the two fusion bugs upstream separately.
3. **Schema v1** merges A/B/C proposals: `units[].text`; global `seq`; `version.seq`; `textSameAs`/`offsetsSameAs`; `retype` and per-version type overrides; `ev.nodes` roles positional and frozen per kind; `ev.phase` = running phase group; separate kind for re-typing `Typed`; `derive` = kind + confidence, pairs for split/merge; `sym.kind/unit/fullName`; `syminfo.name`; `edge.weight`, optional `ev`/`phase`; `null` spans; offsets pre-order, UTF-16, end-exclusive. One recorder must produce both node ids and events; A and B ids do not align.
4. **Storage** moves to columnar (Arrow/Parquet) before any project-scale attempt; JSONL stays for fixtures.
5. **Recorder performance** before scale: weak/per-phase identity maps, skip printing for versions with no new nodes, lineage walk on request only.

## Merged recorder and real-data UI (2026-10-18, later)

Branch `claude/replay-merged-221743` (worktree `replay-merged-221743`, local commits; `local/replay/SCHEMA-v1.md`, `REPORT-merged.md`). UI: `local/replay/ui/REPORT.md` "v1 integration", screenshots `shots/m-*.png`.

- **Fork is +148/−12 in `compiler/src`**: `typer/TyperObserver.scala` (99 lines, null-default slot on `ContextBase`), hook sites, `Name` content hashes, and the `TimeTravellingTreeCopier` `TypeApply` fix. Everything else is the plugin.
- **`TypeApply` fix** shrinks arenas 11–59% (Mixins 0%) and printed versions from 38–70 to 16–21 of 85. The equality test had to be `eq || (== && =:=)`: `=:=` alone broke `-Ytest-pickler` on `tests/run/i15302b.scala` (types `=:=` but print differently), `==` alone broke `-Ycheck` on `tests/run/i1284.scala` (`wrapRefArray[A | B]`: structurally equal `MethodType`s where `=:=` is false; cause not found). Full `testCompilation` with the final check (commit 8c2d5b8326): 21/22 pass, the one failure being the JDK-dependent `i24553`. The `i1284` `=:=` failure on structurally equal `MethodType`s is unexplained and worth a look on its own. `tests/generic-java-signatures/i24553.scala` fails on JDK 21 regardless of this branch (CI uses 17).
- **One id space works.** 77k events on `util` with the same per-kind counts as Track B; byte-identical across runs and under identity-hash perturbation; `-Ycheck:all` fused green; bytecode unchanged with the plugin. Recording cost: 19 s vs 3.7 s on `util` (5×); 72 MB JSONL.
- **UI at scale**: `util.jsonl` streams in 0.35–1.1 s, 140–230 MB JS heap, every interaction at the 60 Hz frame limit, via lazy version decoding (header regex, 24-entry LRU), windowed text, incremental graph updates. Class-level graph for `util` is 261 nodes / 1124 edges, i.e. project-scale aggregation is cheap; the cost was SVG attribute writes, not data.
- **What real data exposed** (fixture had hidden it):
  1. *Rejected implicit candidates are not recorded*: 440/445 `Candidate`s in `util` are accepted, because pruning happens in `eligible`/`compareEligibles`/rank before `tryImplicit`. The "why this given" view, the point of the educational UI, needs hooks there.
  2. *Typer events hang off nodes no version prints* (untyped inputs, intermediate typed results) and nest to depth 39; the inspector reaches them only via the derive chain. `Complete` carries no node. The typer fold stays invisible: versions exist only at `PhaseEnd`.
  3. *Printed types are unreadable for learners* (`?{ + : ? }`, `[applied to () returning ?]`); event payloads need a compact or structured printer.
  4. Graph edges are only `resolves/forces/implicit`; `extends`, `inlined`, `lowered-into` absent; library symbols have no unit, so "file" level is mostly singletons.
- **Next recorder work, in priority order**: (a) candidate-level hooks in implicit eligibility/ranking with reasons; (b) per-top-level-definition typer snapshots (text + offsets, with `seq`) so the event cursor drives a real tree during typer, and `Complete.nodes = [defining tree]`; (c) learner-grade type printer for payloads and `version.types`, `sym.kind` with package/def/val, units for TASTy-loaded symbols; (d) `extends/overrides/inlined` edges; (e) bench-lab for disabled hooks and the `TypeApply` fix.

## Learner evaluation (2026-10-19)

An evaluator session used the viewer alone to answer ten compilation questions (`local/replay/ui-eval/EVAL.md`, screenshots in `eval-shots/`). The tool answered the lowering questions well (pattern matcher output, resolveSuper/mixin/constructors sequence, capturedVars boxing, lambdaLift) and the implicit questions only as far as the winner: "what else was considered" cannot be answered because only winners are recorded. Findings that change priorities:

- **Recorder**: (1) all implicit candidates with reasons and scope; (2) per-miniphase versions inside fused groups, or `phase` on every derive, since learners attribute lazy-val holders and `IntRef` boxing to "mixin"; (3) backend decisions per `Closure` (indy vs class) and final bytecode, now in progress; (4) tentative typing markers (`tryEither`) with discarded diagnostics: a successful compile of `Signatures.scala` contains an `<error …>` type from an abandoned alternative with nothing saying so; (5) lineage taxonomy: `retype` distinct from structural rewrite and from parent-rebuilt, generated patmat branches attributed to their source `case`, desugaring as its own step.
- **UI**: phase pick showed the previous phase's output (cursor at `PhaseBegin`), deep links were one-way, clicks landed on the innermost span with no way to widen, versions of fused groups carried one miniphase's name, retype noise was highlighted like real rewrites, no event filtering (a warning `Report` was found only by accident), graph unusable for a specific node at `util` scale (no search, no neighbour list, click rewinds the cursor). Fixes are queued in the UI session in that order.
- **Wanted and impossible today**: side-by-side phase diff, a "filmstrip" of one expression across phases, the tree halfway through typer, bytecode of the final version.

## Viewer state (2026-10-19, `local/replay/ui/`, `REPORT.md`)

Single-page v1 viewer, no build step. Loads v0/v0+trackA/v1 streams incl. JVM records, streamed and lazily decoded (72–136 MB files, 60 Hz). Recordings panel (manifest, multi-select merge, drag-and-drop), deep links both ways. One timeline: phase-equalised event axis (minimum band width, remainder by visible events), band zoom with minimap, phase pick = phase output, version stepping through event-less phases. Tree pane: syntax highlighting, node-kind colouring, structural/retype/rebuilt highlight taxonomy with "structural only" filter, removed-node list, lineage chain as links, breadcrumb + Alt-click widening, source pane. Inspector: kind/unit/symbol filters, Reports chip, defined/used-here tabs. Graph: search, neighbour table, ego focus, class/file/package grouping, seeded layout. IR view: bytecode pane per class, tree↔instruction-range linking, optimiser-step scrubbing with elided instructions struck through, `inlined-from` links. Transitions: 220 ms line-level FLIP (slides, fades, struck-through ghosts, retype tint), replay and 0.25×, reduced-motion aware; 2–5 ms set-up per step. Not animated per node (inline spans can't be transformed); a per-node morph would need absolutely positioned overlays.

Round 3 (after Jason's use): one-row header with a view-options popover; per-(unit, phase) change scores (structural/retyped/rebuilt/removed/lines ±/instruction ranges) as a heat strip under the bands, a sortable phases table, ●-marks in the phase select, ★ on the top five and `i`/`I` to jump; a collapsible project explorer (unit → class → member, with change counts for the current phase and in total, bytecode classes included) replacing the dropdowns; multi-select of units/classes shown side by side (≤3) or stacked with one shared cursor; and the project graph replaced by a **dependencies panel** (scope = selected unit, kind tabs, summary line, neighbour table with per-edge sparklines over the seq axis) with the force graph demoted to a collapsed ego-network section, since it repeated the table without adding anything.

Data: `ui/data` → canonical recorder output. Serve `local/replay/` on 8766; `ui/index.html` with no params shows the recordings panel.

## Hosting a demo on Cloudflare

The viewer is static (one HTML, ~170 KB of JS, d3 from cdnjs) and loads recordings by `fetch` with streaming, so it fits Cloudflare's static hosting as-is. The only constraint is file size: Workers/Pages static assets cap a single file at 25 MiB, and the two `util` recordings are 87 MB and 139 MB raw (13.7 MB and ~22 MB gzipped; JSONL compresses ~6.5×).

Shape:
- **One Worker with static assets** (`wrangler deploy`, `[assets]` binding): `index.html`, `js/`, `recordings.json`, and every recording under 25 MiB raw (all corpus files and `-opt` variants, the fixture, `Ranking`). Cloudflare compresses these on the edge; nothing to do.
- **R2 for the big recordings**, uploaded pre-gzipped with `content-encoding: gzip` and `content-type: application/x-ndjson` as object metadata; the same Worker serves `/data/<name>.jsonl` from an R2 binding, passing those headers through so the browser's streaming `fetch` decompresses transparently (no CORS, same origin). Cache: `Cache-Control: public, max-age=31536000, immutable` keyed by recording content hash in the file name (`util.<sha8>.jsonl`), `recordings.json` short-lived.
- **Routing**: `replay.<your domain>` or the default `*.workers.dev`; optionally Cloudflare Access in front if the demo should not be public.
- **Pipeline** (`local/replay/deploy/`): `build.sh` copies `ui/` into `deploy/site/` minus dev files (`shots/`, `*.mjs`, `fixture.js` stays for the no-network fallback), regenerates `recordings.json` with hashed names, splits recordings by size into assets vs R2, gzips the R2 set, uploads with `wrangler r2 object put --content-encoding gzip`, and `wrangler deploy`. Idempotent: unchanged hashes are skipped.
- **Cost**: well inside free tiers for a demo (R2: a few hundred MB, egress free; Workers: static requests are free).
- **Out of scope for the first deploy**: recording from the browser (would need a backend running dotc; a later step could be a Worker that enqueues a compile to a container), auth, analytics.

Checklist before first deploy: `wrangler login` (Jason's account), choose domain and public/Access, confirm the recordings to publish (util-opt is 139 MB raw; fine via R2 but maybe not needed for a demo), add a `README` paragraph on the page about what the data is.

## Extension: post-tree phases (JVM bytecode, Scala.js IR)

After `genBCode`/`genSJSIR` the program is no longer a `tpd.Tree`, but the same model applies: a version is a root in a different IR, lineage links tree nodes to IR nodes, events are what the backend decided.

**JVM.** `BCode.genClassNode(typeDef)` (`backend/jvm/BCode.scala:19`) yields one ASM `ClassNode` per class, plus mirror classes; `backend/jvm/opt` then runs local/global optimisations (inliner, closure elimination, DCE) over those `ClassNode`s. Design:
- `version` gains `ir: "tree" | "jvm" | "sjs"`. JVM versions are per class, not per unit; `text` is ASM `Textifier` output (javap-like, familiar), `offsets` map to IR node ids: `ClassNode`, `FieldNode`, `MethodNode`, and instruction ranges (`AbstractInsnNode` index ranges per method). ASM's tree API is mutable, so each snapshot after an optimiser pass is a copy (`ClassNode.accept(new ClassNode)`) and dedup is structural per method (hash of the instruction list), not by identity.
- Lineage tree → bytecode: `DefDef`/`ValDef` symbol → `MethodNode`/`FieldNode` by (owner, name, descriptor), exact and cheap from `BCodeSkelBuilder`. Tree node → instruction range: hook `BCodeBodyBuilder.genLoad(tree, …)` entry/exit to record `(treeNodeId, insnStart, insnEnd)`; this is far finer than `LineNumberNode` and is what makes "click an `Apply`, see its `invokevirtual`" possible. The hook sits on the same observer interface (`onGenLoad`).
- Optimiser passes become phases with `derive` edges per method (`inlined-from` callsite → inlined body range; `elide` for DCE'd ranges). Scrubbing the inliner is the JVM-side analogue of scrubbing `Inlining`.
- Constraint: `-Ybackend-parallelism:1`; the recorder already assumes one thread.
- Beyond the compiler: the same viewer could take a `.class` directory and show C2's view (JFR/`-XX:+PrintInlining`), but that is a different recorder; note only.

**Scala.js.** `JSCodeGen.genCompilationUnit` produces immutable `ir.Trees.ClassDef`s (`backend/sjs/JSCodeGen.scala:205`), so identity-based interning and a marking printer work exactly as for `tpd` (the IR has its own `Printers`). Lineage via `genStat`/`genExpr` entry/exit hooks, analogous to `genLoad`. The Scala.js optimiser and linker run outside dotc (`scalajs-linker`); recording them is a second plugin on the linker's `IRFile` pipeline with the same schema, which would also show dead-code elimination and inlining across the whole program, i.e. the project-graph view for free.

**Schema**: `version.ir`, `node.ir`, IR-specific `kind` vocabularies, `derive.how = "codegen"`; one new `ev` kind `GenLoad` is enough for the JVM side. The viewer needs a second syntax mode for the text and an "IR" step on the phase axis after the last tree phase.

Priority: JVM `MethodNode` text per class with symbol-level lineage first (one afternoon on the existing plugin: `genClassNode` is already called per `TypeDef`), instruction-range lineage second, optimiser-pass snapshots third, Scala.js after the JVM path is stable.

## Hook delivery: upstream interface vs. bytecode agent

Only a few hook sites are plausible upstream: `Typer.typed`, `Completer.complete`, `inferImplicit`/`tryImplicit`, phase boundaries, and a `ClassNode`-complete callback, all one-liners behind a null check. The rest of what the views need is chatty or internal: `TreeCopier.finalize`, `adapt1` steps, implicit `eligible`/`compareEligibles`/rank pruning (the rejected-candidate reasons), `MegaPhase.transformNode` per miniphase, `BCodeBodyBuilder.genLoad`, `TimeTravellingTreeCopier`. Those have no business in the compiler's source.

Proposal: deliver them with a JVM agent (ByteBuddy `Advice` on method enter/exit, `ClassFileTransformer` with retransformation), shipped inside the plugin jar.
- **Attach**: `-javaagent` on the compiler JVM is the clean path and the only one that can touch classes loaded before plugin init (`Name`, `Names`, the typer). Dynamic self-attach from `ResearchPlugin.init` (`ByteBuddyAgent.install()`, needs `-XX:+EnableDynamicAgentLoading` on JDK 21+) works for retransforming already-loaded classes but can only change method bodies, not add members, so e.g. a deterministic `Name.hashCode` must come from `-javaagent` or upstream.
- **Join points**: Scala 3 erasure and name mangling make the matcher the fragile part: `using Context` becomes a trailing positional parameter, local/nested classes get `$` names, `inline` methods do not exist to hook. Pin by (class, method name, descriptor), fail loudly on mismatch, and keep a per-compiler-version table. Hot sites (`finalize`, `genLoad`) must use `Advice` inlining, not method delegation, to stay cheap.
- **What it buys**: the tool runs against *released* compilers (3.3 LTS, 3.7) with zero fork, which is what an educational deployment (Scastie-style) needs, and lets us measure what each hook is worth before asking upstream for an interface. The upstream `TyperObserver` becomes the stable, documented subset; the agent fills the gaps and can also supply the upstream hooks on a stock compiler.
- **Risks**: `-Ycheck`-style invariants are unaffected (advice only observes), but advice on `Typer.typed` runs ~30k times/kLoC and perturbs identity-hash order if it allocates before the compiler does (Track B's lesson), so advice must not allocate on enter and must not call back into the compiler.
- **Acceptance experiment**: agent-instrumented stock `main` build, no in-tree hooks, produces a v1 stream byte-identical to the `TyperObserver` fork's on the corpus (modulo the `Name` hash fix, applied via `-javaagent` class redefinition). If that holds, the fork shrinks to the interface plus the `TypeApply` and `Name` fixes.

## Results: JVM bytecode and agent delivery (2026-10-19)

**JVM bytecode** (branch `claude/replay-jvm-44e61d`, `local/replay/REPORT-jvm.md`). Hooks: `classNodes`, `classWritten`, `genMethod`, `genLoadEnter/Exit` (genStat/genLoadTo), `optimizerPass` via the existing `traceIfChanged`, `inlined` in the inliner; +52/−3 in `compiler/src`. Per-class Textifier versions with offsets for ClassNode/FieldNode/MethodNode/InsnRange; `codegen` lineage from tree nodes; optimiser passes as synthetic phases `jvm:r<n>:<pass>`, `jvm:inliner` (with `inlined-from`), `jvm:classfile`. Coverage: 98% of MethodNodes and 86–94% of instructions have a tree ancestor; the rest is backend-synthesised (forwarders, `<clinit>`, `$deserializeLambda$`). Verified: `shown` Apply → range ending `INVOKEVIRTUAL`, literals → `ICONST`/`LDC`; reproducible; bytecode identical with recording on/off; `-Ycheck:all` and `testCompilation` as baseline. Cost 0.25 s on `util` (1.0 s with `-opt`, re-textifying per pass). Gap: optimised `util` is 136 MB because every changed pass repeats whole-class text and copies every range; per-method `textSameAs` needed. The optimiser requires `-Ybackend-parallelism:1` (no `Context` on worker threads); the plugin checks and records it.

**Agent delivery** (branch `claude/sad-booth-255608`, `local/replay/REPORT-agent.md`). A Byte Buddy `-javaagent` on stock `main` (ae374e9583) produces v1 streams **byte-identical** to the fork's on the corpus and `dotc/util`, including the two behaviour fixes patched in by the agent (`TypeApply` copier via exit advice; `Name` content hashes via load-time `defineMethod`, which also had to replace the synthesized `hashCode` of `UniqueNameKind` and three `AvoidNameKind` enum cases). API jar on the bootstrap path with an erased `Observer`; sites pinned by (class, name, descriptor), verified at plugin init. No hooked method gains an allocation site (checked from Byte Buddy dumps). Cost: ~0.6 s fixed per JVM for transformation; disabled hooks within noise. Self-attach works for all advice sites but cannot add `Name.hashCode`, so streams are then non-reproducible (candidate order shifts). Two agent-only hook groups that the fork lacks: `Scope`/`Rejected` (reasons `notEligible`, `comesTooLate`, `lowerPriorityThan`, `notBetterThanAmbiguous`, `notTried`, `lostTo`; 12k on `util`) and `Tentative`/buffered `Report` (`tryEither` outcomes; 110 discarded on `util`, the `<error …>` class the evaluation found).

One lesson for the educational view: `pickLocal` is not a ranking decision at all. The contextual search succeeds, so dotc never consults the implicit scope; the global given is not a losing candidate but an unrun search. The UI should say that rather than invent a comparison.

**Decisions.**
1. Upstream candidates (the agent had to reconstruct control flow for these): phase boundaries, `Typer.typed` (inside the `else`), `Completer.complete`, `inferImplicit` plus a per-candidate hook, a ranking-level per-candidate *verdict*, a `ClassNode`-complete callback, and a `Reporter` hook that also fires for TyperState-buffered diagnostics. Plus, as ordinary compiler fixes with their own justification: `Name` content hashes, the `TypeApply` copier fix.
2. Agent-only: `adapt`/`adapt1`, `TreeCopier.finalize`, `searchImplicit`/`rank` internals, `tryEither`/`commit`, `genLoad`, `MegaPhase.transformNode`, optimiser passes.
3. ~~The three branches must be reconciled~~ Done: **`claude/funny-chaplygin-0b0e4a`** (worktree `funny-chaplygin-0b0e4a`) is the canonical recorder branch: one plugin, `REPLAY_VARIANT=fork|agent`, JVM recorder in both variants, fork and agent byte-identical on 12/12 runs (corpus and `util`, default and `-opt`), `jvm-verify` and `testCompilation` as baseline. Output in its `local/replay/out/` is what the viewer loads. Report: `local/replay/REPORT-reconcile.md` there.

## Later phases

Each step compiles and is committed on its own.

- [ ] **P0 Spike (phase axis only).** `Recorder` that, after each phase, walks each unit's tree and appends new nodes to an arena by identity; emits `Version` per phase. Dump to JSON/Parquet. Throwaway viewer: phase slider with changed-subtree highlighting. Answers: node counts, share ratio, overhead of identity interning.
- [ ] **P1 Lineage.** Hook `TreeCopier` and `MegaPhase` miniphases; add `Origin` attachments in `Desugar`/`Inlines`. Viewer morphs old → new.
- [ ] **P2 Typer events.** `Typed`, `Adapt`, `Resolve`, `Complete`, `Desugar`. Scrub typer on one file.
- [ ] **P3 Symbols and denotations.** `SymCreated`, `InfoChange`, flags; symbol-centric view across phases.
- [ ] **P4 Search and solver tiers.** `ImplicitSearch` candidate tree; `SubtypeQuery`/constraints under focus; re-run-for-detail with the stability CI gate.
- [ ] **P5 Project graph.** `edges` table, aggregation, Leiden + layout, overview renderer, shared cursor.
- [ ] **P6 LLM narration and SQL Q&A** over the store.
- [ ] **Overhead gate.** Measure disabled-recorder cost with `bench-lab` before proposing any hook upstream; prefer keeping the recorder as an out-of-tree plugin/fork if hooks can't be made free.

## Open questions

1. Can the hook set live in a compiler plugin plus `-Xprint`-style phases, or do `TreeCopier`/`Typer`/`ImplicitSearch` need in-tree hooks? (Plugin gets us P0/P1 only.)
2. Granularity of `Derive` for `TreeTypeMap`-heavy phases (inliner, `LambdaLift`): per node is exact but chatty; per subtree with a substitution map may be enough.
3. Is the event stream reproducible enough across runs (hash-ordering in sets/maps, `uniqueId` assignment) to rely on re-run-for-detail?
4. cc (capture checking) and `Recheck`: second typer-like pass over typed trees. Same event vocabulary, or its own tier?
5. Scope: educational UI wants curated small examples first; diagnostic UI wants big inputs. Which drives P0's viewer?
6. Where does the viewer live (separate repo, `scaladoc-js`-style subproject, Scastie-hosted)?
