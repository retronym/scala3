# Track C report: UI on the v0 schema

Everything is in `/Users/jzaugg-virtus/code/.worktrees/scala3/replayable-compiler-design-780592/local/replay/ui/` (gitignored, nothing committed). Authored with LLM assistance.

## What exists

| File | Role |
|---|---|
| `index.html` | page; d3 7.9.0 from cdnjs is the only external dependency; no build step |
| `js/loader.js` | v0 JSONL loader and indexes (`R.load`), lineage walks |
| `js/app.js` | shared state (one cursor), file input, keyboard, `#phase=&seq=&node=&sym=` deep links |
| `js/scrubber.js` | view 1: phase scrubber |
| `js/inspector.js` | view 2: event tree and event stack |
| `js/graph.js` | view 3: project graph (label propagation, force layout computed once) |
| `gen_fixture.py` | generates `fixture.jsonl` (+ `fixture.js`, an embedded copy so `file://` works) from objects modelled on `corpus/Givens.scala` |
| `shoot.sh` | headless Brave screenshots via deep links (needs `python3 -m http.server 8765` in this dir) |
| `shots/*.png` | screenshots (01 typer + events, 02 erasure lineage, 03 lambdaLift split, 04 graph mid-replay, 05 graph at end) |

Run: open `index.html` from `file://` (falls back to the embedded fixture) or serve the directory; "load v0 JSONL" picks any file. Keys: ←/→ event (⇧ ×10), `[` `]` phase, Esc clears selection.

Fixture: 6 phases (typer, inlining, patternMatcher, erasure, lambdaLift, constructors), 89 nodes, 6 versions with real offsets, 36 derive records (heuristic/copy/rewrite/split/elide/synth), 61 syms, 63 syminfo, 173 events (Givens `render`/`viaConversion`/`pickLocal` with nested ImplicitSearch/Candidate/Adapt/Report/Complete; then ~90 synthetic Complete/Resolve/ImplicitSearch events for 36 symbols in 4 clusters), 97 edges. `inlining` and `patternMatcher` are deliberate no-ops (identical root) to exercise "no new nodes".

What each view does:
- **Scrubber**: version text rendered from offsets as nested spans; nodes not in the previous version are underlined, the frontier of new nodes (no new child) is shaded; hover tooltip = node kind/name/type, symbol and its syminfo at that phase, and the derive chain with the old node's text taken from the earlier version; click selects. Selection survives phase changes by following derive records forward (descendants), else backward. Side panel shows the same details plus span/children/event count.
- **Inspector**: roots = events whose `nodes` contain the selected node or any of its derive ancestors (or `syms` for a selected symbol), rendered as collapsible trees by `parent`; cursor row highlighted and its path opened; separate stack panel with the parent chain of the cursor event; the scrubber outlines the nearest enclosing event that names a node.
- **Graph**: layout from d3-force run 500 ticks once on all edges and frozen; label propagation for fill colour, ring colour = declared owner (two syms are deliberately declared in the "wrong" package so the two groupings differ); size = √(events touching the sym); edges are invisible until `seq` ≤ cursor, then fade in (CSS transition) and are thicker for 4 events; unreached nodes dimmed; click jumps the cursor to the first event touching the sym and selects that event's first node.
- **Loader**: skips bad lines, ignores unknown `t`, coerces numeric strings, reports dangling node/sym/parent references and out-of-range offsets as a warning bar (tested on a deliberately broken file; no exceptions).

## v0 fields that were missing, ambiguous or awkward

1. **No source text.** `meta.units` has a path only, node `span`s point into text the viewer never receives, so there is no source/untyped view and `Typed(untpd → tpd)` has no untyped side to show. Propose optional `units[].text` (or a `unit` record).
2. **Versions have no position on the event axis.** The cursor is a `seq`, the phase axis is `version.phase`; nothing relates them. I used `ev` records of kind `PhaseBegin` (not in the v0 kind list) so the cursor can drive the phase slider. Propose either `version.seq` or add Tier-0 kinds (`PhaseBegin/End`) to the frozen list.
3. **`seq` scope.** Plan text says "per-unit seq", but `edge.seq` and the graph cursor need one total order across units. I used a single global `seq`. Propose: state that `seq` is global.
4. **`ev.payload` is unspecified**, yet the inspector depends on it. Shapes I assumed: Typed `{pt}`, Adapt `{step, pt?, found?, expected?}`, Resolve `{name, scopesTried[], importsConsidered[], ambiguity?}`, Complete `{forcedBy, reason}`, ImplicitSearch `{pt, scope}`, Candidate `{accepted, why}` with the candidate in `syms[0]`, Report `{severity, msg}`. Track B should either adopt these or tell us theirs; the viewer falls back to a JSON dump per unknown kind.
5. **`ev.nodes` has no roles.** `Typed` and `Adapt` really relate an old node and a new node (`insertConversion`: `i` becomes `Conversion.apply(i)`), and `Resolve` relates ident to symbol. A flat `nodes` list loses which is which. Propose `payload.old/new` node ids or `nodes` as `[{id, role}]`.
6. **Events name typer-phase node ids only.** For a lowered node the inspector must walk `derive` backwards to find events; this works only if every lowering step has derive coverage. Untyped nodes (desugar output) belong to no `version`, so events on them cannot be placed in any text.
7. **`forcedBy` duplicates `parent`** and is a name in my fixture (a symId in generated events). Probably drop it; the parent chain already carries causality.
8. **`syminfo` has no name** and no type ids. `lambdaLift` renames a symbol (`given_Show_Int` → `given_Show_Int$1`); `sym.name` is fixed at creation, so I smuggled the new name into the printed `info`. Propose `name` on `syminfo`. `flags` is a free-form string; fine for display, useless for filtering. There is no `toPhase`, so "valid until" is implicit (next entry); acceptable but should be stated.
9. **Node `tpe` is creation-time only** while review point 1 says types are period-dependent. Shared nodes keep their typer `tpe` forever. The UI shows `tpe` as recorded plus `syminfo` at the phase, which can contradict (e.g. `shown` has erased info but its Select node still shows the generic type). Propose a per-version override list `types:[[nodeId,typeId],...]` or let `version` carry `retyped`.
10. **`derive` semantics.**
    - Not stated whether `phase` is the phase that produced the new nodes; I assumed version N is the output of phase N and `derive.phase = N`.
    - `how` overlaps: `heuristic` vs `rewrite` vs `copy` (is `heuristic` a lineage confidence or a kind?). Suggest splitting into `how` + `confidence`.
    - `split`/`merge` carry unordered `new[]`/`old[]` with no pairing, so the UI cannot say which part of the old node became which new node. Pairs or per-new `role` needed.
    - `elide` has `new: []` and `synth` has `old: []`; fine, but no `unit` on derive.
    - Unchanged (shared) nodes get no record, so "no derive record" is ambiguous between "shared" and "untracked".
11. **Offsets order.** When two nodes have identical ranges (an `Apply` and its only child) nesting is decided by input order. I rely on offsets being pre-order (parent first); this should be a rule. Printer markers can also yield non-nested ranges (modifiers, annotations); the loader clamps rather than fails.
12. **Null spans.** Synthetic nodes have no source span; I emitted `[pos,pos]` (zero-width) or `[0,0]`. Should be `null`, and the schema should say what zero-width means.
13. **`sym` lacks kind and unit/file.** The graph needs class vs method vs package and the owning file to aggregate to class/file/package level (plan section on scale-out); owner chains give only package structure. Propose `sym.kind`, `sym.unit`.
14. **`edge`**: no phase (so no `lowered-into`, which is not in the kind list either), multiplicity is implicit (duplicate records), and it is unstated that `seq` is the seq of the establishing `ev` (I assumed it). Propose an optional `ev` field and `weight`.
15. **Versions repeat full text per phase**, even when identical (`inlining`, `patternMatcher` repeat the typer text byte for byte). Fine for a PoC; add `sameAs` or skip emitting identical versions (the viewer would then need to carry the previous version forward).
16. `phase.id` ordering is assumed numeric; `mini/megaOf` not used by any view.

## Proposed schema changes (summary)

`units[].text`; `version.seq` (or Tier-0 `PhaseBegin/End` in the kind list); `seq` declared global; payload shapes frozen for Tier 1/2 kinds; `ev.nodes` roles or `payload.old/new`; drop `forcedBy`; `syminfo.name`; per-version type overrides; `derive` pairing for split/merge and a separate `confidence`; offsets in pre-order; `null` spans; `sym.kind`, `sym.unit`; `edge.weight` and optional `phase`/`ev`.

## What the views could not express

- **The typer fold.** The cursor only highlights the current node and stack; the text does not change between events within typer (plan item 7 defers morphing, so deliberate). No untyped version, no desugar step.
- **Lowering phases have no events**, so scrubbing past typer only changes the version text; only `PhaseBegin` markers exist.
- **Lineage display** follows one chain when a node has one ancestor; `split`/`merge` show all olds but without pairing; no old→new morph animation.
- **Graph at project scale**: ~60 nodes only. Layout runs in the browser synchronously (500 ticks, fine for hundreds, not for thousands; needs the offline `layout` table and canvas/WebGL). No semantic zoom (file/package levels), no edge bundling, no lowering-phase symbol universe (the layout is one epoch).
- **Graph edges for `inlined`/`macro`** exist in the legend only; nothing in the fixture checks them against real inlining.
- **Cross-unit events**: the scrubber shows one unit at a time; selecting a symbol whose events live in another unit moves the cursor but not the unit.
- **Community detection vs Leiden**: plain label propagation (deterministic, ties to smallest label); gave 5 communities matching the planted 4 + the real cluster, but it is unstable on weakly separated graphs.
- **Hover is verified only interactively** (the in-app browser); headless screenshots cannot hover, so tooltips are not in `shots/`; the side panel shows the same information.
- **Not tested** with real Track A/B output or with large files (all text is rendered into one `<pre>`; thousands of lines of printed tree and tens of thousands of events would need virtualisation).

## Integration with Track A (`out/*.jsonl`, schema `v0+trackA`)

Run: serve `local/replay/` (`python3 -m http.server`) and open `ui/index.html#src=../out/Givens.jsonl&skip=1&collapse=1&pname=erasure` (or pick the file with the file input). Hash options: `src`, `phase` (index), `pname`, `skip`, `collapse`, `seq`, `node`, `sym`. With no `ev`/`edge` records the page shows the scrubber alone (`main.single`). Screenshots: `shots/a-givens-{typer,patternMatcher,erasure,lambdaLift}.png` (skip + collapse on) and `a-givens-erasure-nocollapse.png`.

### What broke
Nothing threw: all five files load in 5–30 ms with no warnings and render every version in about 1 ms. Layout and usability problems found:
- **Phase buttons** don't fit 92 versions. Now a `<select>` ("k/n name") above 12 versions, plus the slider.
- **Versions that add nothing** (23–77 of 92) make the slider mostly dead steps. Added "skip no-op versions" (Givens 92 → 69, → 61 with collapse; the other four files 92 → 13–14 with collapse on).
- **Copy noise defeats the no-op test.** Without collapse, a version whose text is unchanged still shows e.g. 37 new nodes (Givens patternMatcher); with collapse 16.
- **Spine highlighting.** My "frontier" shading used `node.children`, which skips nodes that print inside their parent (Track A's `TypeDef` bodies), so whole classes were shaded. Parenthood is now taken from offset nesting. Remaining effect: ancestors of a real change are "new" (not pure copies) and get dotted underlines up to `PackageDef`; they are not collapsed.
- Panels with no data (inspector, graph) wasted 60% of the screen; the layout is now single-column when there are no `ev`/`edge` records.
- Unit selector shows the absolute path (`meta.units[].path`); cosmetic.

### What changed in the viewer
- Loader: `version.textSameAs`/`offsetsSameAs` resolved by phase id within the unit (chains work, offsets/index shared by reference, so memory stays flat); `retype` records (type of a node at phase p via `D.tpeAt`, used in tooltip/side panel); `sym.fullName/kind/local/unit`, `syminfo.name` stored (name not yet shown in the UI); `meta.units[].text` stored (unused, see below); null spans accepted.
- "Collapse pure copies": a new node is a pure copy when it has a single-source derive whose source has the same kind, name, symbol, span, printed type text and child count, and each child is the same node or itself a pure copy (memoised). Collapsed nodes are not counted or highlighted as new. My counts on the corpus, as a share of nodes that are new in some version: Closures 59%, ForDesugar 69%, Givens 37%, Mixins 6%, Patmat 19%, close to Track A's 62/76/40/9/21 (I include span equality and count only nodes new relative to the previous version).
- New-node counts are relative to the previous *shown* version, so with skip on a version's "N new" is cumulative since the last visible one.

### Remaining schema gaps seen with real data
1. **Per-version type text** (Track A proposes `version.types`): tooltips still show the type printed at first sight; `retype` fixes object changes only.
2. **Pure-copy flag.** I recompute it from derive + node tables; cheap here, but a recorder-side `how:"copy"` (Track A's proposal) lets viewers skip loading `node` rows entirely for such nodes.
3. **`derive` is per node but many records are fan-in groups** (`synth` grouped per phase/unit): hover shows "derived from: no ancestor" for each member with no way to tell the group's origin or reason.
4. **No version↔event link** still (no `ev` in these files): the `PhaseBegin` workaround from the first section is untested on real data, as is the cursor-drives-phase logic.
5. **Fused groups**: the group's version carries the last member's id, so the phase name on the slider is an arbitrary member of the megaphase. Needs `version.group` or the list of member phase ids.
6. **Source text** (`units[].text`) is loaded but nothing shows it; node spans (parser positions) could now drive a source pane with highlight of the selected node's origin. Not done.
7. Size: Givens loads 1.2 MB in 30 ms but all text stays in one `<pre>` (up to ~10 kB per version here, fine); no check with larger inputs.

### Three things I'd want the recorder to emit next
1. **Typer events with node ids that match these arenas** (Track B alignment): `Typed/Resolve/ImplicitSearch/Candidate/Complete` using the same node and sym ids as `v0+trackA`, plus a `PhaseBegin/End` ev (or `version.seq`) per phase. Without it the inspector and graph, two of the three views, can't run on real data.
2. **Per-version retyped types and symbol-aware rendering**: `version.types` for nodes whose printed type changed since emission, so tooltips and the side panel stop contradicting `version.text`.
3. **Lineage with kind/confidence and pairing**: `derive.how` = `copy|rewrite|split|merge|synth|elide` plus `confidence:"exact|heuristic"`, and for split/merge an explicit old→new pairing (or role). Currently almost everything is `heuristic`, and the viewer can't tell a noisy copy from a real rewrite except by recomputing equality.

## v1 integration (`replay-merged-221743/local/replay/out/*.jsonl`, `SCHEMA-v1.md`)

Run (from `local/replay/ui`; `data` is a symlink to the merged worktree's `out/`): serve `local/replay/` with `python3 -m http.server 8766`, then open `ui/index.html#src=data/Givens.jsonl&skip=1&collapse=1`. `src` takes a comma-separated list, which merges files into one recording (ids and seqs offset per file; non-local symbols are unified by `fullName`), and the file input accepts several files. More hash options: `retyped=1`, `level=class|file`, `unit=<file suffix>`, `pname`, `seq`, `node`, `sym`. `shoot.mjs` takes screenshots through CDP (headless `--screenshot` captured before streaming loads finished); `shoot.sh` is only good for small non-streamed data.

### What changed in the viewer
- **Loader** (`js/loader.js`, rewritten): line-by-line streaming from `fetch`/`File.stream()` with progress, one loader for v0, v0+trackA and v1 (the old fixture and `../out/*.jsonl` Track A files still load: shots `m-v0-regress-*.png`). v1 parts handled: `version.seq`, per-version `types` (node type text = last entry at or before the version), `derive.confidence`, `retype`/`Retyped`, `edge.weight/ev/phase`, `PhaseBegin/End`, null `unit`, `textSameAs`/`offsetsSameAs` naming any earlier version, `sym.firstPhase/firstSeq`.
- **Lazy versions**: a version line is only header-parsed (regex on the first/last bytes: phase, unit, seq, root, `nodes`, `new`); text, offsets and types are decoded on first use and kept in a 24-entry LRU (the raw line stays in memory). A version that shares text/offsets reuses the referenced version's decoded object and `Set`/`Map`.
- **No-op detection without decoding offsets**: `version.new` from the header, and for "collapse pure copies" the count of non-`copy` derive nodes per (unit, phase). Pure copies in v1 are just `derive.how == "copy"`.
- **Cursor drives phase and unit**: with `version.seq` the visible version for a cursor at seq q is the last one with `seq <= q`, so scrubbing events through typer shows the parser output until the typer `PhaseEnd`, then the typed tree. The cursor also switches the displayed unit to the event's unit (merged recordings: to the unit of the event's file for unit-less events). Phase pick moves the cursor to that version's `seq`.
- **Retyped hidden by default** (toggle "show Retyped events"); the event axis and graph cursor walk only visible events.
- **Inspector**: payloads of v1 kinds (type ids resolved to text, `Complete.forcedBy` sym, `ImplicitSearch.result`, `Candidate.ref/reason`, `Report.level`), children rendered lazily (capped at 120 per level with "more"), the branch to the cursor always opened.
- **Scrubber**: windowed rendering above 500 lines (spacer plus a window of about 100 lines with spans clipped to the window, `white-space: pre`), selection or cursor node scrolled into view; derive tooltip shows `confidence` and looks old nodes up in the unit's previous version only.
- **Graph**: levels class / file (grouped by `sym.unit`; unit-less syms stay individual), min-weight filter, node size ~ √incident weight, incremental edge/node updates between the previous and new cursor, no per-edge `<title>`/curvature above 1500 edges, fewer labels above 120 nodes.

### What the real data shows that the fixture hid
- **Typer events nest deeply and hang off nodes the versions never print.** `Typed` nests to depth 14 (Givens) / 39 (util), `Complete` sits inside the `Typed` that forced it (e.g. `render`'s completion under its `DefDef`), and event nodes are untyped inputs and typed results interned only by the events. A click in any version reaches them only through the derive chain, so inspector coverage depends on derive quality (`rewrite/exact` from `Typed` works well: clicking an erased `Apply` finds its typer events through `Retyped` then `Typed`, `m-givens-retyped.png`).
- **ImplicitSearch trees are mostly one-armed.** In Givens all 5 `Candidate` events are accepted; in util 440 of 445 are accepted and only 5 rejected (reasons `MismatchedImplicit`, `NoMatchingImplicits`). So the view the plan wants ("the compiler tries three givens; the first is rejected because…") cannot be shown: rejected candidates are not recorded, only the winner. `ImplicitSearch.nodes[1]` (chosen tree) is correct and links to the node (`m-givens-implicit.png`).
- **`Complete` events have no nodes** (`span` is a zero-width position), so they appear for a node only through its symbol. The inspector does pull in `Complete/Candidate/ImplicitSearch` events via the node's symbol, but a dedicated `Complete.nodes=[defining tree]` would be simpler.
- **Printed types are unreadable for learners**: protos and wildcards print as `?{ + : ? }`, `<?>`, `[applied to () returning ?]`, `a type that can be selected or applied`, and `pt` is mostly noise for `Typed`. Event rows are truncated and show the first node's kind and id only.
- **Versions are printed after phase groups, not typer steps**: the typer version appears at the typer `PhaseEnd`, so "scrub the typer" still shows only parser output until the end of typer.
- **Graph at class level is already aggregated per phase**, so a project graph is 261 nodes / 1124 edges for util, not tens of thousands; the cost of `seq` cursor movement was dominated by SVG attribute writes, not data volume. Edges carry only `resolves/forces/implicit`; `extends`, `inlined` and `lowered-into` are absent.
- **File level is mostly library classes**: only syms with `unit != null` collapse into files (240 nodes for util at "file" level, of which 36 are real files); everything compiled from TASTy/classfiles stays as individual classes.
- **Merging across files works** (symbols unified by `fullName`, 5 corpus files give 52 class nodes and 167 edges in one graph, `m-combined.png`) but the five compiles are separate runs with their own seq ranges, so the merged cursor is a concatenation, not a real interleaving.

### Measurements (util.jsonl, 72 MB, 77,390 events, 133,759 nodes, 3,060 versions; built-in browser on this Mac, localhost, JS heap from `performance.memory`)
| | |
|---|---|
| Load (stream, parse non-version lines, lazy versions) | 0.35–1.1 s depending on cache temperature (`ms` column in the load line); index 45–250 ms |
| JS heap after load | 138–233 MB (about 3× file size, lazy version lines are about 33 MB of that), 183 MB after 300 interactions |
| Interaction, frame time (`requestAnimationFrame` after the state change), median/p95/max | cursor ±37 events 17/17/18 ms; cursor jumps ±3000 17/19/33 ms; phase step in `Signatures.scala` (8.8k nodes, 3.3k lines) 17/17/19 ms; node select + inspector 17/18/18 ms; text scroll 17/17/18 ms (all at the 60 Hz frame limit) |
| Script time per `R.render` | 4–18 ms (scrubber 0–3, inspector 0–6) |
| Graph layout (computed once) | 110–500 ms for 240–261 nodes / 970–1120 edges; re-run when level or min-weight changes (127–390 ms) |
| Windowed text | about 220 spans in the DOM for 7.4k nodes, window rebuild 1 ms |

Not done: virtualised inspector (only lazily-opened subtrees, capped lists); an `evByNode` index is built eagerly (about 77k events, tens of ms inside the 45–250 ms index step) rather than lazily; the first load is not incremental (nothing is shown until the stream ends); no check beyond util's size.

### Remaining schema gaps (v1)
1. **Rejected `Candidate`s** are missing (see above). Needed: one `Candidate` per tried given with `accepted:false` and a reason, or a rejection summary on `ImplicitSearch`.
2. **`Complete.nodes`** (the definition tree) and `forcedBy` as the *event* parent rather than `ctx.owner`; the latter says who owns the completer, not what demanded it.
3. **Version/event relation inside typer**: `version.seq` ties versions to `PhaseEnd` only; no intermediate typed snapshots per top-level definition, so the typer fold remains unvisualisable (per-def versions or `Typed` results carrying the printed subtree/offsets).
4. **Type text for event payloads** is printed at the end of the interning phase (`type.text`); protos print as prose. A compact printer for `pt`/`tpe`, or a structured form (`kind`, `args`), is needed to show them.
5. **`sym.kind` is `class|type|term`** and `unit` is null for library symbols: no file/package hierarchy to aggregate by beyond `unit`. `owner` chains exist, so a `fullName`-prefix or package level could be derived, but declared `package` kind would help.
6. **`edge` has no `extends/overrides/inlined/lowered-into`**; weights are per phase so a total needs summing (done here).
7. **`derive`**: `split` is mostly heuristic (21k of 22k in util) and `synth` groups per (phase, unit) lose the origin; `merge` still absent.

### Top three recorder changes I'd want next
1. **Record every implicit candidate** (accepted or rejected, with the reason string and the candidate's `ref` type) and attribute `Complete` to the tree that was typed: that is the data behind the "why this given" and "who forced this completer" views, which are the point of the educational UI.
2. **Per-definition typer snapshots**: a printed version (text + offsets) per top-level member at the end of its `Typed`, with `seq`. Then the event cursor in typer drives a real tree, and events carry nodes that exist in some visible text.
3. **A learner-friendly type printer for event payloads and `version.types`** (no protos, no `<?>`/prose, same style as `-Xprint`), plus `sym.kind` extended with `package`/`def`/`val` and a real `unit` for TASTy-loaded symbols, so the graph can group by package/file instead of only by compiled unit.

## Axis UX (phase bands on the event axis)

Screenshots: `shots/u-axis-givens-typer-start.png`, `-typer-mid.png`, `-typer-end.png`, `u-axis-util-typer.png`, `u-axis-util-lowering.png`.

- **One timeline**: event slider, a band track directly under it on the same horizontal scale (thumb inset matched), and the phase selector below. Bands run from a phase group's `PhaseBegin` to its `PhaseEnd`, alternate shading, carry a label when ≥ 56 px wide (name, seq range, event count and, with timestamps, duration on hover), highlight the phase the cursor is in, and show an orange cursor marker. Under the band track an equal-width strip lists every phase group, because a dominant band hides the rest (util: typer is 99.5% of the 38.5k visible events, so the other 84 bands share about 5 px).
- **Bands by time**: widths are proportional to wall time when every `PhaseBegin`/`PhaseEnd` carries a timestamp (`ev.ts`, `ev.ns` or `payload.ts/ns`; files are concatenated). **The v1 recorder does not emit any timestamps yet** (checked `Givens` and `util`: no timing on `ev`, `phase` or `stats`), so real files fall back to event-count widths and the label says "bands by event count (no timestamps in file)". The time path was only tested with a synthetic copy of `Givens` with invented timestamps (not shipped; no screenshot of it). Recorder change needed: `ts` (ns since run start, `System.nanoTime` delta) on `PhaseBegin`/`PhaseEnd`; ideally on every `ev`, so the interior of a band can be scaled by time too. The event slider itself still moves by event index, so in time mode the band track and the slider no longer share one scale; the marker interpolates the cursor inside its band by event index.
- **Phase pick = start of the phase**: the selector (slider, buttons, dropdown), a band click and the equal-width strip all move the cursor to that phase's `PhaseBegin`. Dragging the event cursor updates the selector (it shows the cursor's phase, not the displayed version) and highlights the band.
- **In-progress banner** in the scrubber: "typer in progress (seq 330); showing parser output. The typer tree appears at seq 463. [jump to end of phase (e)]" whenever the cursor is inside a phase whose version appears later.
- **Keys**: `[`/`]` previous/next phase start (a first `[` inside a phase goes to its start; with "skip no-op versions" only phases in the version list), `b` start of phase, `e` end of phase (again: end of the next phase), `n`/`p` next/previous event within the phase, arrows as before. Checked on `Givens`: `e` from seq 330 lands on 463 with the typer tree shown; `]` goes to 464 (inlinedPositions in progress, typer still shown); `n` to 465; `[` back to 464.
- Limits: with Retyped events hidden the erasure band shrinks to a few events; merged multi-file recordings show one band set per file (cursor and `[`/`]` stay within the current file); band widths are recomputed on resize only when the container width changes.

## Polish

Screenshots: `shots/p-recordings.png` (first run), `p-merge-from-panel.png` (three recordings ticked and merged from the panel), `p-syntax-kinds.png` (syntax, colour-by-kind, hover tooltip), `p-source-pane.png`, `p-cheatsheet.png`, `p-util-syntax.png`.

- **Loading without URL hacking**: with no hash the page opens a recordings panel listing the built-in fixture plus `recordings.json`, each entry with Load and a checkbox (several ticked, "Load selected", merges them). `gen_index.py [dir …]` scans `.jsonl` files (default `data/`) and writes `recordings.json` (name, size, schema, unit names, phases, events, nodes, versions, edges; `data/index.json` is also tried). Drag-and-drop of one or many files anywhere on the page, a "choose files" link and a `webkitdirectory` folder picker (lists the `.jsonl` files in the panel to tick) are fallbacks. The toolbar has `recordings…`, `copy link` (builds `src`, unit, phase, seq, node/sym and toggles into a deep link; for local files it says the link cannot reload the recording) and `?`. Hash deep links still work; a hash without `src` loads the fixture (used by the old screenshots).
- **First run and help**: the panel carries a six-line "what this is" and the key/mouse cheat sheet; `?` toggles the same cheat sheet any time, `Esc` closes dialogs.
- **Syntax highlighting** (toggle "syntax", on): a stateless regex tokenizer (keywords, strings, numbers, comments) applied to the text chunks between node spans, so it sits under the new-node, selection and cursor classes. Cost on `util` (windowed, `Signatures.scala`, 3.3k lines): phase step 4–6 ms with it, 4–5 ms without; window rebuild 1 ms; about 450 spans in the DOM.
- **Kind colouring**: every span has a `k-<Kind>` class; hover outlines the span in its kind colour, "colour by kind" underlines all spans, and a legend row under the status line lists DefDef, ValDef, Class/Module/TypeDef, Apply, TypeApply, Select/Ident, TypeTree, Literal, Block/If/Match, Package/Template. Nodes with no source span (width 0, created by a phase) are italic with a hatched background.
- **Source pane** (collapsible, under the text): the unit's source from `units[].text`; green = source spans of nodes new in this version, blue = selected node's origin span, orange underline = nodes of the event cursor; scrolls to the selection; a click selects the innermost node of the current version whose span contains the clicked position. Rebuilt only when open and when the version, selection or cursor nodes change (30 kB source: 9 ms).
- **Small things**: unit dropdown shows basenames with the path as tooltip; phase buttons/select show "typer (198 new)" (v1 from the version header, minus pure copies when collapsing; the first version shows its node count); the status line terms "baseline", "new", "pure copies hidden" and "shared" have explanatory tooltips; a hash with only `pname`/`phase` now also moves the event cursor to that version so the "in progress" banner is not shown spuriously.

Found while doing this: a deep link that only picks a phase left the cursor at seq 0, which made the banner claim the parser was in progress; fixed as above. The in-app browser and headless Brave behave the same for drag-and-drop only in principle; drop was not exercised by a real drag (file input and folder picker were not driven either, only the panel's own Load/Load selected path).

What I'd do next:
1. A real `Esc`/focus model for the dialogs and keyboard focus on the panel list (currently mouse-first), and remembering the last recordings and view state in `localStorage`.
2. Tokenise by node kind instead of regex when the offsets carry `TypeTree`/`Ident` in type position, so type names (not just keywords) are coloured; the regex pass cannot tell `String` the type from a term.
3. Source pane as a side-by-side diff: map the previous version's removed nodes back to source too, and show source for the selected node's *typer* ancestors (derive chain) rather than only nodes present in the shown version.
4. Persist `copy link` state into the hash continuously (so reload keeps the view), and make local-file recordings linkable (e.g. via a File System Access handle).
5. A virtualised inspector list and an incremental first paint during streaming load for the 70 MB case.

## Post-evaluation

Work against `ui-eval/EVAL.md` (Part 2 and the Part 1 "Stuck" notes); `ui-eval/` untouched. Screenshots: `shots/e-*.png`. Tours: `TOURS.md` (URLs updated; `tours-check.mjs` opens every link headless: all 20 steps load the named phase, `interpolators` once flaked because the checker read the page before the hash was applied, fixed by waiting for `R.hashApplied`). Also verified with scripted interactions: phase pick, shift-click on a band, `hashchange`, seq-only deep link, arrow key after focusing an inspector `summary`, structural-only filter, Reports chip, problems filter, splitter drag.

1. **Phase pick = output.** The phase select/buttons/slider, a band click, an equal-width strip cell and `[`/`]` put the cursor at that phase's `PhaseEnd` (Givens erasure → seq 948, tree = erasure). Start of phase: shift-click on a band or cell, the new "⇤ start" button, or `b`. `pname=` deep links land at the output. The in-progress banner only appears while scrubbing events inside a phase.
2. **Deep links both ways.** `hashchange` re-applies the state (a different `src` reloads); the address bar is rewritten with `history.replaceState` (debounced) with `src, unit, pname, seq, node|sym, level, skip, collapse, retyped`. On load `seq` alone selects the unit and the version visible at that seq (`seq=463` → typer; `seq=334` → parser output with the banner); an explicit `pname`/`phase` overrides the version. Local-file recordings do not write a hash (it could not reload them).
3. **Selection.** Breadcrumb of ancestors (by offset nesting) above the side panel, each clickable; Alt+click selects the parent. Hover now follows `mousemove` and uses the same node function as click (the old `mouseover` tooltip went stale after a click re-rendered the text). For a node with a symbol the inspector has two tabs: **used here** (events on the node, its derive ancestors, and search/adapt/report events on enclosing trees) and **defined** (all events on the symbol). Selecting the `given_Show_List` identifier now lists the 324 ImplicitSearch / 330 Candidate chain (`e-given-used-defined-tabs.png`).
4. **Fused groups.** `D.phaseName` is the label from the `phase` records' `group` (e.g. "mixin + lazyVals + memoize + nonLocalReturns + capturedVars") in the select, buttons, status line, banner, band/strip tooltips and event rows; bands show "mixin +4"; `pname` accepts the first member's raw name (`D.phaseRaw`) or the full label.
5. **Highlight taxonomy.** New nodes are classified from `derive` (latest record at or before the version): structural (rewrite, split, synth: green underline, lowest ones shaded), retype-only (`how=retype`: blue dotted), parent rebuilt (rewrite with same kind/name/symbol/span and child count, only a child id differs: grey dotted), pure copy (`how=copy`: faint; hidden by "collapse pure copies"). "structural only" shows just the first group. The "N new … shared" sentence became chips (e.g. Givens erasure: 93 structural, 106 retyped, 1 rebuilt, 21 pure copies hidden, 1 removed) with tooltips, plus "since X, 31% shared".
6. **Lineage and removed nodes.** Each "derived from" step is a link (`pre.lk`) that switches to that phase's output with the old node selected, and the old node's text comes from the latest printed version that contains it (searching back up to 12 distinct versions), labelled with that phase. A "removed" chip and collapsible list show top-most nodes of the previous shown version that are gone (click one to jump there).
7. **Event queries.** Filter row in the inspector: kind, free text (symbol, name, message, reason, result), unit scope, "problems only" (Reports, failed ImplicitSearch, rejected Candidate, failed Complete, aborted Typed). Any filter switches to a flat list over all events (click a row: cursor and node). "Reports (n)" chip in the header jumps to the next Report; Reports (red) and other problems (orange) are ticks on the phase/event track.
8. **Graph.** Search box with match chips (also matches member names inside grouped nodes), neighbour table for the selected node (direction, node, kind, weight, first seq; click: cursor to first seq; double-click: select that neighbour), "focus" with 1 or 2 hops (non-neighbours hidden), single click selects without moving the cursor, double click jumps. At file level classpath symbols are grouped by package (`dotty.tools.dotc.core.*`): util 240 → 120 nodes. Node order is sorted and the force layout is seeded, so a reload reproduces the layout. The ego graph keeps the frozen layout (it hides, it does not re-layout).
9. **Layout.** Draggable splitters between the columns and between events and graph (persisted in `localStorage`), "All | Tree | Events | Graph" buttons and keys `0 1 2 3` for full-height single views. Inspector indentation is capped (flat from depth 8), rows wrap, Report messages are shown in full.
10. **Text.** The parser version is the real source text with nodes' source spans (`s"hello $name…"` intact; "source text" tag in the status line). Qualified type names are shortened with the full name as tooltip; prototype-like types (`<?>`, `?{…}`, "a type that can be selected or applied", `<error …>`) are omitted from event rows and the side panel.
11. **Keyboard.** The global handler is only suspended for text-like inputs (text/search/number, textarea); checkboxes, buttons, summaries no longer swallow arrows, selects blur after a change; `?` toggles the overlay. Every input and select has an `aria-label` (sliders named "event axis", "phase axis").
12. **Defaults.** "bands by time" is off and disabled when the recording has no timestamps (tooltip says why); zero event counts are not printed; page and heading say "v1 viewer"; the fixture is listed as "synthetic fixture (v0, hand-written, not a real compile)".

Not done or limited:
- **Not data-fixable in the viewer**: heuristic lineage misattributions (the `+` after `interpolators`), no rejected implicit candidates, no desugared intermediate tree, no backend step, fused miniphases still one version (only the label changed).
- The structural/retype/rebuilt classes depend on v1 `derive.how`; v0 data only gets structural vs pure copy.
- "Used here" climbs at most 8 tree ancestors and only picks up search/adapt/report events there; a use site whose search is attached to a distant tree would be missed.
- The ego graph hides nodes instead of re-laying out; with many selected neighbours the remaining cluster stays small.
- Splitter dragging, Alt+click and hover were driven with synthetic events in headless Brave, not with a physical mouse.
- Reports/problem ticks are capped at 800 per recording; the flat filter list at 300 rows.

## IR view (JVM bytecode, `ir: "jvm"`)

Data: `data-jvm` → `replay-jvm-44e61d/local/replay/out/` (second symlink next to `data`); `gen_index.py data data-jvm` lists everything in the recordings panel (entries from the second directory are prefixed `data-jvm/`, with "+ JVM bytecode: N classes, M class versions"). Run like before (`python3 -m http.server` in `local/replay/`), e.g. `ui/index.html#src=data-jvm/Givens-opt.jsonl&skip=1&collapse=1&unit=Givens.scala&cls=Givens$&pname=jvm:inliner&ir=1`. Screenshots: `shots/j-*.png`.

### What was added
- **Loader** (`js/loader.js`): `version.ir == "jvm"` versions are kept per class (`D.jvmClasses`, sorted by phase), not mixed into the per-unit tree versions (their `unit:phase` key would collide), and are lazy like tree versions (header regex, text/offsets decoded on first use, same LRU). JVM `derive`s (codegen, rewrite, copy, elide, inlined-from, with `callee`) go to `D.irDerives`, indexed both ways, plus `irFromTree` / `irToTree` (codegen) and `irClassOf` (node → class via ClassNode children and `InsnRange.method`). Node records keep `ir, desc, method, range, insns, mirror`. `util-opt.jsonl` (136 MB, 944 class versions, 255k nodes, 186k derives) loads in about 0.5 s with a 280–290 MB heap, and stepping JVM phases there costs 3–5 ms per step.
- **IR segment on the phase axis** (`app.js`): after the last tree phase come pseudo versions, one per JVM phase that changed some class of the current unit (`genBCode (bytecode)`, `jvm:inliner`, `jvm:r1:dce`, …), shown in their own `IR · JVM bytecode` group of the phase select with the number of classes changed. They take no part in the event cursor (their `seq` is genBCode's PhaseEnd plus an epsilon, so a cursor never lands on them); `]` steps from the last tree phase into the IR segment and through it, `[` back out; hash: `ir=1&cls=<class>&pname=jvm:...`.
- **Class selector** next to the unit selector (grouped by unit; `class` hash parameter). The class shown is the selected one, or the one holding the selected node, or the unit's first class.
- **Bytecode pane** (`js/ir.js`): the class text of the latest version with phase ≤ the chosen JVM phase goes through the same span/selection/windowing machinery as tree text (`paint`), with a bytecode tokenizer (opcodes, labels, descriptors, `LINENUMBER`-style directives, strings, numbers; "syntax" toggle). Spans are ClassNode/FieldNode/MethodNode/InsnRange (kind outline colours); a class not changed by a step says so and shows its older text.
- **Tree ↔ bytecode**: selecting a tree node (any version; it is followed to the genBCode input through the tree derives) outlines its InsnRange(s) with a dashed orange outline in the bytecode, mapped to the shown phase through the JVM derives (successors, else predecessors); selecting a range finds its tree nodes by walking back through rewrite/copy/inlined-from to the codegen derive, outlines them in the tree, and lists them in the side panel ("generated from tree") as links; a tree node's side panel has "show bytecode". "bytecode beside tree" (header checkbox) puts the tree's final phase and the genBCode class text side by side (windowed around the linked range, class selectable), with hover linking in both directions (checked: hovering `"x"` outlines `LDC "x"` and the reverse). **Givens.render**: `shown("x", Show.given_Show_String)` maps to `GETSTATIC Givens$package$.MODULE$ … LDC "x" … GETSTATIC Show$given_Show_String$.MODULE$ … INVOKEVIRTUAL Givens$package$.shown`; the literal `"x"` to exactly `LDC "x"` (`j-link-tree-to-bytecode.png`).
- **Optimiser scrubbing**: the status line counts rewritten / inlined / elided / unchanged ranges for the class at the step; rewritten ranges are green, inlined ranges purple; the "elided" list shows the ranges of the previous text struck through. The **method steps** panel is per method (select, marked ● when changed at this step): "previous/next change" jump to the JVM phase where that method's text changed, and a collapsed LCS line diff from the method's previous distinct text to the current one shows removed instructions struck through and added ones (e.g. `jvm:inliner` replacing `INVOKEVIRTUAL Show$.given_Show_List` by the callee's code, `j-optimiser.png`). Because a method's history skips steps where it did not change, rounds that differ per method (`jvm:r2:dce` holding only some methods) read correctly. "inlined into this method" lists each copied range with its callee name and a "show callee" link to the callee's genBCode method/range (other class and other unit allowed; `(not compiled in this run)` when `old` is empty); a selected inlined range shows the same in the side panel.

### Schema gaps seen
1. **No `ir` on the genBCode phase record**: the `phase` record keeps its tree id and has no `ir` marker, so the viewer treats the lowest JVM version phase as genBCode; a tree `genBCode` version may also exist (same phase id), and `pname=genBCode` is ambiguous (hence `ir=1`).
2. **Versions carry no method key**: matching a method across class versions needs name + descriptor from the MethodNode offsets; `MethodNode` ids change when a method changes. A stable `method` id (or a per-method `textSameAs`) would let the viewer skip parsing class texts to build per-method histories (now it decodes every version of the class on first use of the method panel).
3. **Per-method steps are class versions**: a class version at a JVM phase is the whole class; the method panel compares method text slices. A per-method version record would be exact and smaller (REPORT-jvm "Known gaps").
4. **`inlined-from` callee is a genBCode state**: the link opens the callee as generated, not as it was when inlined; the callee name (`callee`) is only on the derive record, not a field of the node.
5. **No `ev` records / seq for the backend**, so JVM phases cannot sit on the event axis; the IR segment is reachable only through the phase axis.
6. **Ranges are chatty**: 1454 InsnRange nodes for Givens-opt, most `copy`; the copies carry no information for the viewer beyond identity (only the count of "unchanged ranges" is shown).
7. **Mirror classes and `$package$` forwarders** are separate classes with their own ranges but no tree origin below the method level (as REPORT-jvm says); they show "no tree origin" in the side panel.

### Limits and what I did not do
- Elided instructions are shown struck through in the "elided" list and in the method diff, not inline at their old position in the class text (the class text only has the surviving instructions).
- The class selector lists all classes (139 for util), grouped by unit; the IR segment shows only phases at which a class of the **current unit** changed; with units that share optimiser phases the pseudo-version labels repeat per unit.
- The split pane renders about 300 lines around the linked range, not the whole class; the "tree" side is the final tree version only.
- Hover linking, `]`/`[` stepping into the IR segment and the "show callee"/"generated from tree" links were exercised with synthetic events in headless Brave, not with a physical mouse.
- Recorder wishes: (a) `ir:"jvm"` plus a stable method id on MethodNode records and a `method` field on class-version offsets (so per-method histories need no text slicing); (b) per-method versions for optimiser passes with `textSameAs`; (c) an `ev`-like marker (or `seq`) per JVM phase so the cursor can drive the IR segment, and a `callee` node reference on `inlined-from` that resolves to the callee's *inlined-time* state.

## Animation

Stepping to an adjacent shown version (phase select/slider by one, band or button click, `[`/`]`, event keys that cross a `PhaseEnd`) animates the text pane for 220 ms (one easing, `cubic-bezier(.2,.7,.2,1)`). The same code path serves the bytecode pane for optimiser steps (`paint()` is shared). Controls in the header: "⟲ replay" (also key `r`, replays the last transition from its stored snapshot), "animate" checkbox, and a speed selector 1× / 0.25×. `prefers-reduced-motion: reduce` turns it off (instant). Jumps of more than one version, a different unit or class, a re-render of the same version (selection, cursor) and the first paint are instant. A new step cancels the running animations and removes the ghosts first, so a second step during an animation starts from the final layout of the first; input is never blocked (Web Animations API, no timers on the interaction path).

**What animates**: the printed text is rendered as one block per line (the nested node spans are re-opened on each line; hover/click/selection still go through the same spans). Before the new version replaces the DOM, the lines in the visible region plus 60 lines of margin (at most 300) are measured and cloned; after it, the new visible lines are measured, equal lines are matched in order (greedy, monotone), and
- matched lines whose position changed slide from the old to the new position (FLIP, `translateY`);
- new or changed lines fade in (their existing green/blue/grey highlight is already in place);
- lines that left are drawn as ghosts at their old position, struck through in red, fading out (at most 60);
- retype-only spans (`nc-retype`) get a brief blue tint, no movement (at most 80);
- for the bytecode pane the same applies to instruction lines (rewritten ranges fade in, elided instructions fade out struck through, lines below slide).

**What does not, and why**
- **Not per-node.** The brief asked for FLIP on span rectangles with `derive` as the correspondence. Spans are inline boxes (a CSS transform does not apply to them) and moving them would need absolutely positioned per-node overlays; I matched whole lines by equal text instead, so a line whose text changed fades in rather than morphing, and derive records are not used for the animation (they still drive the highlight classes). Far-away matches (> 600 px) fade instead of sliding.
- **Elided lines do not collapse their height**: they are overlay ghosts, the surrounding lines slide into the gap while the ghost fades.
- Content outside the visible region ± margin, the source pane, graph, inspector and the split bytecode pane are not animated.
- There is no time guard ("skip if measuring takes over 20 ms"): the cost is bounded by the 300-line cap instead, and measured below.

**Measured cost per transition** (script time for taking the snapshot, matching and starting the animations; `R.animStats`, headless Brave, one browser tab on this Mac; the animation itself runs on the compositor): `Givens.jsonl` tree steps 2.2–4.8 ms (14 consecutive steps from typer: median 2.5 ms; snapshot 0.6–0.8 ms; 67–91 lines considered; the heavy steps are erasure, mixin, constructors and flatten with 21–54 lines moved and up to 74 lines fading and 60 ghosts); `Signatures.scala` (4,866 lines, windowed) 1.8–3.1 ms with 75 lines considered (many steps change nothing in the visible region, 0 moved); bytecode, `Givens-opt` class `Givens$`, JVM phases: 3.6–4.8 ms (74 lines, mostly 0–16 moved because the changed methods lie outside the visible region). Whole-version render wall time stays 5–11 ms including the animation set-up (it was 4–18 ms before).

**Evidence**: motion cannot be shown in a screenshot and there is no GIF encoder here (no ffmpeg or PIL), so `shots/anim-filmstrip-getters-to-erasure.png` is a five-frame strip captured at 0.25× (0, 240, 480, 720, 960 ms) from `anim-frames.mjs`: old text, then lines sliding and fading in with struck-through ghosts and the blue retype tint, then the settled new version. Frame capture timing is approximate (screenshot latency).

Not tested with a physical display (only headless Brave); reduced-motion was implemented via `matchMedia` but not exercised.

## Axis scale (phase-equalised event axis, zoom)

Screenshots: `shots/ax-givens-global.png`, `ax-givens-zoomed.png` (+ `ax-givens-zoom-erasure.png`), `ax-util-global.png`, `ax-util-zoom-typer.png`.

Chosen: options 1 + 3, with 4 and 5 as part of them; 2 (log/sqrt widths) not used.
- **1. Phase-equalised scale.** Every phase band gets at least 6 px (the minimum is capped so that all minimums together take at most 60% of the axis, which matters with merged recordings that have 85 × N bands); the remaining width is shared by *visible* event count (or time, when timestamps exist). The band track and the event slider use the same piecewise-linear mapping: the slider now runs over axis position (0–10000) and is converted to an event index through the band it falls in, the cursor maps linearly inside a band, and the marker and ticks use the same mapping. On a 1218 px axis: Givens typer 633 px, every other phase ≥ 7 px (85 bands); util typer 707 px, minimum 6 px. Justification: log/sqrt scales would still let typer (about 50,000 of 92,000 events in util) swallow most of the space and make widths hard to read as event counts, while a floor per phase keeps every phase hittable with the slider and by click and keeps widths honest above the floor.
- **3. Zoom into a band.** Double-click a band or the "⤢ zoom phase" button: the band spans the whole axis, a breadcrumb "all phases › erasure · 367 visible events · seq 800–1168" appears above it, the equal-width strip below becomes the minimap (current phase highlighted), and every visible event is a tick coloured by kind (problems full height; sampled to about 900 ticks when a band has more events, e.g. util's typer with 53,000). Clicking in the zoomed band puts the cursor at that x; the slider covers only the band. `Esc` or "⤡ zoom out" (also the "all phases" link) goes back; stepping the cursor out of the band moves the zoom to the new band.
- **4. Visible events size the bands.** `evl()` (Retyped hidden by default) is what the bands count, so the axis already follows the toggle. Zooming into a band that has Retyped events turns "show Retyped" on for as long as the zoom lasts (Givens erasure: 850 → 1217 visible events, 367 in the band) and restores the previous setting on zoom out.
- **5. Arrow keys step by version across empty phases.** A step of one event into a band with no visible events other than its PhaseBegin/PhaseEnd lands on the next phase's output instead (the `[`/`]` rule), so `→` from the end of typer visits posttyper, firstTransform, expandSAMs, … each as its version (checked on Givens; in util the keys walk typer's 53,000 events one by one, as before). Shift-arrows (±10) and `n`/`p` are unchanged.
- The data directories now point at `funny-chaplygin-0b0e4a/local/replay/out/` (`data` and `data-jvm` are the same target; tree and JVM records are one stream per file, so the old `data-jvm/…` links still work); `recordings.json` was regenerated from it (14 recordings, including the new `Ranking.jsonl`).

Limits: the axis mapping is rebuilt when the width, the visible event list or the zoom changes (a resize re-renders on the next interaction, not live); with merged recordings each file's bands get their own floor, so the minimum can shrink below 6 px; ticks in a very large zoomed band are sampled, so single events are not individually addressable there except by the slider or arrow keys.

## Round 3

Screenshots (1500 px wide): `shots/r3-header-options.png`, `r3-timeline-heat.png`, `r3-explorer.png`, `r3-multi-units.png`, `r3-multi-bytecode.png`, `r3-deps.png`, `r3-deps-graph.png`.

1. **One-row header.** The header is now: recording name (opens the recordings panel), view switch (All / Tree / Events / Deps), explorer and phases buttons, "⚙ view options" and "?". Every toggle moved into the view options popover (unit/class selects and copy link, structural only, syntax, colour by kind, show Retyped, skip no-op, collapse copies, cursor drives phase, bands by time, bytecode beside tree, animate/replay/speed, interesting-phase metric); element ids are unchanged so deep links and key handling work as before. The key strip is only in the `?` overlay; the stats line (load time, counts, heap, render ms) is a footer. The timeline (event axis, phase bands, phase axis) is the second row; it was left as it is.
2. **Phase change scores.** Per (unit, phase): structural, retyped, rebuilt, copy and removed nodes from the derive records (nodes classified with the same rules as the scrubber chips), changed lines from a lazy chunked line diff of consecutive printed versions (computed for the current unit and, for JVM phases, the current class; instruction lines only), JVM phases: rewritten + inlined ranges and elided ranges. Shown as (a) a heat strip under the bands (alpha by the chosen metric), (b) a sortable "▦ phases" table (phase, structural, removed, retyped, rebuilt, lines ±, a score bar; click a row to go to that phase), (c) `●` to `●●●●` after the phase name in the phase select, relative to the unit's maximum. The top 5 phases by the chosen metric (structural / removed / retyped / changed lines / all) get a ★ on their band and `i` / `I` jump to the next / previous one. On Givens the top five by structural nodes are typer, firstTransform, erasure, mixin(+4) and lambdaLift. No timing column: recordings still have no timestamps.
3. **Project explorer** (left, collapsible with `x` or the button, draggable width, collapsed/expanded state and width kept in `localStorage`): unit → its top-level classes/objects → members (taken from the tree shown), plus the unit's bytecode classes when the recording has them. Each row shows the number of non-copy new nodes in the current phase (with a bar) and the total over all phases; nodes are attributed to the innermost class and the outermost method below it by climbing the arena's parent links (the latest parent when a node has several), keyed by symbol so counts survive phases. Selecting a unit switches unit and keeps the phase, a member selects its tree node (the scrubber scrolls to it), a bytecode class sets the class. The unit filter box narrows a long list (util: 36 units). The unit and class selects stay in the popover.
4. **Multi-select.** Checkboxes (or Ctrl/Cmd-click) on units and bytecode classes; "N selected · show together" switches to the multi view (and to the Tree view layout): up to 3 panes side by side when the area is ≥ 1000 px wide, otherwise stacked with sticky headers; one shared phase, selection (selecting in a pane switches the primary unit) and cursor; panes use the same highlighting classes (structural/retyped/rebuilt, selection, link outlines) and hover tooltips. Events: with a selection and nothing else selected the inspector lists events of the selected units (flat list); the dependencies panel and graph centre on the selected units together. **Simplified panes**: no windowing (the first 1500 lines of each version, with a note) and no animation; the single-pane view keeps both.
5. **Dependencies instead of a project graph.** The panel answers "what does this unit depend on, and when": default scope is the selected unit (or the selection), grouped by file/package, 1 hop (2 hops selectable); kind tabs (all / resolves / forces / implicit / extends, with counts); a summary ("Pulls in 71 library/classpath classes in 32 packages (dotty.tools.dotc.core 14, …) and 4 other compiled files (Spans.scala 69, …) · 2130 references resolves 2056 implicit 68 forces 6 · used by other files 0×"); and the neighbour table as the primary UI: direction, node, kind, weight, first seq and a per-edge sparkline of when the edges were established over the whole seq axis (orange = cursor); click a row: cursor to its first seq, double-click: make that node the centre, "open": select that unit. The force graph is a secondary, collapsed-by-default section ("graph of this neighbourhood", ego network only, file/package level by default, with the old level / min-weight / search controls). **Verdict**: the table with the summary and sparklines earns the space; the graph, even restricted to the ego network, only repeats the same edges in a layout (38 nodes / 140 edges for Signatures.scala) and tells nothing the table does not, so I demoted it to a collapsed section; it is one click away.

Limits: change scores for classes/members count derive nodes, not source lines; the "lines" metric is computed lazily after the first use, so the strip and table fill in over the first second for large units; multi view panes are not windowed; the dependencies sparkline resolution is 24 buckets over the whole recording, so edges established inside typer share one or two buckets; the explorer's member attribution can mis-assign nodes that are shared between several parents (it picks the latest); data was only exercised in headless Brave at 1500 px.

## Merged recordings: one timeline

Loading several recordings together (recordings panel with several ticked, comma-separated `src`, multi-file input) used to concatenate their seq ranges, so every phase appeared once per file. Now `mergeTimeline` (`js/loader.js`) builds one phase timeline: events are ordered by phase (ids ascend in execution order, identical across compiles with the same plan), then file (load order), then original seq; each phase keeps one PhaseBegin (first file's) and one PhaseEnd (last file's), so a phase is a single band holding every file's events (typer: file A's events, then B's, then C's: seen switching at event 310 in the Closures+Givens+Mixins test); parents are remapped, versions (tree and JVM) take the merged PhaseEnd of their phase as `seq` (the three typer versions all get seq 1261, the band's end), edges follow their first event. Original file index is kept as `ev.of`. Bands, the heat strip, the phases table, `i`/`I`, the explorer counts and the dependencies panel need no change (they work off bands and derives). The header shows "3 recordings, merged by phase; not a joint compile" as a badge. The old concatenated behaviour was removed rather than kept behind an option (it never reflected a real run).

Checks (Closures + Givens + Mixins merged): 85 bands (one typer band), 3 units, 2,219 events with contiguous seq, every parent before its child, all edge seqs inside the range, `]` and the phase table work. Screenshot `shots/merge-three-files.png`.

`data/corpus.jsonl` (the five corpus files compiled in one run) loads as a single multi-unit recording like util: `merged` is false, no badge, 5 units in the explorer (Closures, ForDesugar, Givens, Mixins, Patmat), 85 bands with one typer band, events carry their unit (completions cross files), JVM classes present (`corpus-opt.jsonl`: 5 units, 31 classes, no warnings). Screenshot `shots/merge-corpus-single-run.png`. `recordings.json` was regenerated (16 entries).

Limits: ordering inside a merged band is by load order, not a real interleaving (cross-file completion order is lost, the one-run corpus recording has it); phases missing in some recordings are simply absent from those files' share of the band; `sym` ids are unified by `fullName` as before (local symbols stay separate).

## Interesting phases and the JVM segment

Cause of the missing stars: the scores only looked at tree derives and `irScore` ignored `codegen` derives and the instruction text of classes other than the current one, so genBCode scored almost nothing and the strip had no IR cells at all. Fixed (`js/scores.js`, `js/app.js`); screenshot `shots/star-jvm.png`.
- **JVM scores per unit, summed over its classes.** genBCode: `codegen` derives (nodes emitted) plus instructions emitted (opcode lines of every class's genBCode text, shown as `+N`); optimiser phases: rewritten + inlined ranges, elided ranges, and instruction lines +/- against the class's previous state; `jvm:classfile`: lines +/- against the previous class state. The line counts are computed lazily and chunked for all classes of the unit. Givens-opt, genBCode: 331 nodes, +566 instructions; `jvm:inliner` 54 rewritten/inlined, +253 −93; `jvm:r1:simplifyJumps` +238 −255.
- **Phases table**: JVM rows are tagged "JVM" (and genBCode "⟐ bytecode"), with a separate "instructions ±" column (tree rows keep "lines ±"); the metric picker applies to both ("changed lines" uses instruction lines for JVM rows).
- **Stops**: genBCode and `jvm:classfile` are always interesting phases whatever the metric (plus the usual top 5), so `i` / `I` stop at both; on Givens-opt and corpus-opt the stops are typer, erasure, mixin(+4), genBCode, jvm:inliner, jvm:classfile.
- **Timeline**: the equal-width strip under the bands now continues with one cell per JVM phase after a small gap, on a purple background shaded by the chosen metric, a "JVM" caption, outlined when starred, current one filled; clicking a cell shows that phase. The genBCode band carries a "⟐ bytecode" tag under the heat strip (the heat strip gets a ★ outline there like other starred phases). Optimiser phases have no events or bands, so they exist only as strip cells and in the phase select/table.

Limit: the heat strip proper (aligned under the proportional bands) still covers only phases with bands; the JVM heat is on the strip's purple cells.

## The event axis reaches the JVM phases

Screenshots `shots/jvm-axis-mixins-opt.png`, `jvm-axis-corpus-opt.png`.
- **Axis.** The genBCode bytecode appears at genBCode's own PhaseEnd (its class versions already carry that seq), and every later JVM phase (optimiser passes, `jvm:localVariables`, `jvm:lineNumbers`, `jvm:classfile`) gets one synthetic `JvmPhase` event after the last recorded event, with a band of its own (single event, 6 px minimum on the equalised axis, purple "JVM" styling, caption on the strip, heat shading per phase). The class versions of that phase take the synthetic seq (`D.irSeq`), so the slider, `→`/`←`, `]`/`[`, `e`/`b`, merged timelines and the phases table all reach them; the event inspector shows these as "JVM phase … (synthetic step)". Mixins-opt: the slider at its far right is `jvm:classfile` (seq 626); `]` from erasure walks mixin → constructors → moveStatic → selectStatic → genBCode (bytecode) → jvm:r1:staleStores → pushPop → simplifyJumps → jvm:r2:dce → jvm:classfile. Corpus-opt: same end-to-end (17 JVM phases, 4099 events), `←` from the end gives `jvm:lineNumbers`, `[` steps back.
- **Bytecode by default.** The cursor at or past genBCode's PhaseEnd shows the class text (the tree genBCode version, a no-op, is overridden by the IR genBCode entry that follows it in the list). The class is the explorer's selected one, else the unit's first module class (name ending in `$`, not `$package$`), else its first class; the header line reads "genBCode · class [selector] · N instructions · M nodes" (N = opcode lines of the class text) and the class selector is in the pane header, replacing the old "no changes since …" tree text. The tree stays one step back, or beside the bytecode with "bytecode beside tree".
- **Animation.** Steps between JVM phases go through the same adjacent-version path as optimiser steps (same unit and class), so they animate; the step from the last tree version to genBCode (different content) is instant. Measured on Mixins-opt: 2.4 ms for a JVM step.
- Not changed: recordings without JVM data behave as before; per-class synthetic steps (finer than one per phase) were not added.

## Legible bytecode highlighting

Screenshot `shots/bytecode-legible.png` (Mixins-opt, class `Service`, method `log` selected, an instruction hovered).
- **Cause.** A highlighted MethodNode wraps nested InsnRange spans; every span got its own outline/underline/fill, so a 15-range method showed 15 boxes. A second, accidental cause: the `lnk` class used for "linked from the tree" collided with the generic `.lnk` link style (underline, accent colour) used in the recordings and dependencies panels, which underlined every linked bytecode line.
- **Fix, bytecode panes** (`.bc` on the main pane, the beside-tree pane and bytecode multi-view panes): no underline, outline, per-node background or box shadow on spans at all. State is drawn on whole *lines* with `:has()`, so nested spans cannot stack: selected = solid left bar plus a faint fill on the range's lines; hover = fainter version of the same (all line fragments of the hovered node, not only the first); linked from the tree = dotted orange bar, no fill; a rewritten / inlined InsnRange = a 2 px green / purple left bar. Text keeps full contrast.
- **Syntax colours in bytecode**: three at most: opcodes (blue), and one dim grey for labels, descriptors, comments, `LINENUMBER`/`LOCALVARIABLE`-style directives; numeric operands and strings are plain.
- **Tree pane**: when a node and its descendants share a change class (structural, retyped, rebuilt, copy) only the outermost is underlined; the frontier shading for the lowest structural nodes is unchanged, and different classes still show where they nest (e.g. a retyped type inside a rewritten call).
- Checked on Mixins-opt: 0 underlined/outlined spans in the pane with a method selected, 14 selected lines, 15 hovered lines. Not changed: the node-kind legend still mentions "colour by kind" underlines, which applies to the tree pane only.

## Stack overlay

Screenshots `shots/stack-overlay-signatures.png` (util, Signatures.scala, 27 shown versions, erasure in front), `stack-overlay-trace.png` (Givens, the selected `viaConversion` DefDef traced on open), `stack-overlay-givens.png`.
- **Entry.** A "Stack" button in the view switch and key `s` open a full-window overlay (dimmed backdrop, `Esc` or ✕ closes; `Esc` first drops an active trace) showing the current unit's tree versions as cards in depth (newest in front). Deep link `stack=1`; `local/replay/stack/index.html` is now a redirect to the viewer with `stack=1` (the old standalone page is kept as `stack/poc.html`, with its own copy of the model code in `stack/js/`, no longer used and safe to delete).
- **Sync.** Opening puts the viewer's current version in front (bytecode shown: the last tree version). Moving the stack sets the viewer's phase and cursor to that version's seq (throttled to 90 ms, trailing; closing flushes it), so closing leaves the viewer where the stack was. The viewer's selected node is traced on open (next tick, 24–27 ms through 85 versions), clicking a line traces that node and selects it in the viewer; the unit selector in the overlay calls the explorer's unit switch, and a unit/option change underneath rebuilds the cards. The viewer's heavy views do not render while the overlay is open (only the timeline controls and the overlay itself); one full render on close.
- **Shared model.** The stack consumes `R.ST.D` and the viewer's version list (`R.vers()`), no second load. New `js/diff.js` holds the one line diff (common prefix/suffix trimmed, LCS for small middles, greedy monotone matching above 1.5 M cells), cached per (version, previous version) pair; `scores.js` now takes its lines ± from it too (and instruction-line counts via `R.countDiff`), replacing its greedy count, so the heat strip, phases table and the stack's barcodes/headers agree. ★ cards are `R.interesting()` (the same top-5 plus the forced stops). The loader's version LRU grew from 24 to 96 so all 85 printed versions of a unit stay decoded.
- **Options.** Skip no-ops decides which versions are cards (off: no-op versions are 4 px slivers); structural only marks added lines without a structural new node as weak (muted, not counted: Signatures erasure +3303 → +1085, 2218 lines muted); animate / reduced motion (`R.animActive()`) and the speed selector drive the stack's follow (jump instead of glide, 4× slower at 0.25×).
- **Code layout.** `js/stack/model.js` (cards, lazy rows, tracing), `js/stack/stack.js` (rendering and input, listeners only while open), `js/stack/overlay.js` (glue, minimap, chronicle); classic scripts like the rest (the viewer keeps working from `file://`; the PoC used ES modules). Text tiers: rows for a card are computed only when it comes near (≤ 5 ms per frame), and text DOM is windowed to the visible rows ± 24 (the PoC built every row, which was fine at 420 lines but not at 5,000).
- **Measured** (headless Brave, 1500×950; util Signatures.scala, 85 versions of 3–5 k lines, no-ops shown): opening the overlay takes 8 ms synchronously (4–8 ms in all runs, 6 ms on Givens); script work per frame while scrolling 0.5 ms avg / 1.3 ms p95 / 1.8 ms max with 11 text cards; frame interval 17.7 ms avg, one 33 ms frame in the sweep (the timer fallback in this headless run, no GPU); card rows appear progressively as cards come near (18 of 27 ready after open on the skipped list). I did not measure real trackpad input in a visible window.
- **Help.** The `?` overlay lists `s`, the depth keys, click-to-trace, text scrolling and the hover/minimap/`i` gestures, and the overlay header carries a one-line hint.
- Limits: tree versions only (bytecode classes are out of scope); the overlay is always dark; the structural-only line test uses each added line's first node; thread and traces are as accurate as the derive records (heuristic splits can fan out, up to 80 nodes per version).

## Stack: bytecode segment, quiet no-ops, card layout

- **JVM segment.** After the last tree version the stack continues with the unit's JVM versions for one class: genBCode (★), then each `jvm:*` phase that changed that class, ending at `jvm:classfile`. Cards show the Textifier text with the same line diff (instruction lines only counted; unchanged methods muted), barcodes, a "JVM bytecode · class X" divider. A **class selector** in the overlay header picks the class (`ST.cls`, shared with the viewer). Chosen over one segment per class because units can have dozens of classes, which would swamp the tree phases; the selector keeps depth proportional to phases.
- **Lineage.** A traced tree node continues into its codegen InsnRange(s); clicking an instruction line traces back through its range to the tree (checked on Closures-opt: tree node 823 → 19/19 cards; Mixins-opt: ClassNode 1230 back to parser). Scrolling into a JVM card sets the viewer's phase and class.
- **No-ops.** Slivers are 2 px, one faint tint, no text/barcode/dashed outline; runs of ≥2 get one "n phases, no change" label; a sliver expands to a labelled card on hover. ±2-line changes count as no-ops (from the heat-strip line counts) with the "±2 lines = no change" checkbox to turn that off.
- **Bug fixed:** a no-op card that became front was clipped to its header height and translucent, so the previous card's diff showed through (looked like a pane that "can't settle"). Front no-op cards now get a full opaque body with their own unchanged text; stale rows are recomputed when a card flips to no-op.
- **Layout.** Header 28 px with its own padding; text padded 16 px top / 20 px left / 16 px right+bottom; line height 18 px (1.45); gutter with a separator; scrollbar thumb on each card (draggable on the front card; positions are linked across cards through lineage / proportional sync); horizontal wheel scrolls long lines. First cards (every unit in corpus-opt, first JVM card) verified to carry no diff rows.
- Screenshots: `/Users/jzaugg-virtus/code/.worktrees/scala3/replayable-compiler-design-780592/local/replay/ui/shots/stack-jvm-closures.png`, `stack-jvm-trace.png`, `stack-jvm-back.png`, `stack-noop-corpus.png`, `stack-noop-mixins.png`, `stack-first-cards.png`.
- **Recorder wishlist:** the parser version prints `module object Closures`; print `object` for module classes (untyped printer modifier rendering, not a viewer issue).

### Stack glitch fixes (Jason's Closures screenshot)
Reproduced at Closures / `inlinedPositions` (skip-no-ops off). Causes and fixes:
1. A no-op card that was in front kept the sliver height (28 px) while its body was laid out full size, and had no body rendered → front no-op cards now get full height, an opaque background, and their own unchanged text; the sliver styling applies only to `:not(.front)`.
2. Ghost (removed) rows were drawn half-clipped: the empty line-number `inline-block` shifted the row baseline by half a line; `.ln` is now `vertical-align: top; height: 18px`.
3. A generic `.card` rule from the viewer (`padding: 14px 18px`) leaked into the overlay cards, pushing the header/barcode 15 px down so the barcode overlapped row 1 and the card behind showed above the header → `#stackov .card { padding: 0; margin: 0 }`.
Checks: first card of each of the 5 units in corpus-opt has no diff rows and a 16 px gap between barcode and row 1; scroll-link: scrolling the front card by 15 rows moves the card behind (0 → 11.8 rows, proportional; by lineage when a node is traced). Screenshot: `/Users/jzaugg-virtus/code/.worktrees/scala3/replayable-compiler-design-780592/local/replay/ui/shots/stack-glitch-fixed.png`.

### Stack: overlay isolation, label culling, trace into bytecode
- **Overlay isolation:** `#stackov` is z-index 100 and covers the whole viewport with an opaque backdrop. It owns wheel/touch (`preventDefault` on the overlay root, passive: false, even at the stack's ends; `overscroll-behavior: none`); `body`/`html` overflow is hidden while open, and the page scroll position is restored on close (checked: body overflow `hidden` and scrollTo has no effect while open).
- **Label culling:** "n phases, no change" labels are drawn nearest-first and dropped when within 13 px (one line) of a label already drawn; card-header text on receding cards is hidden (`.cull`) under the same rule. Screenshot `/Users/jzaugg-virtus/code/.worktrees/scala3/replayable-compiler-design-780592/local/replay/ui/shots/stack-labels.png` (Closures-opt at jvm:classfile).
- **Trace into bytecode:** at the last tree version the thread continues through `codegen` derives: a node with a range in the selected class uses it; otherwise it climbs to the nearest ancestor that has one ("via <kind>" in the chronicle); if the range lives in another class the JVM segment switches to that class and the chronicle says so. The traced row is the range's first instruction. Then the range derives (copy/rewrite/elide/inlined-from) carry it through the optimiser cards to jvm:classfile (or ✕ at elide). Clicking an instruction traces back range → tree node → tree lineage.
- Verified: Closures-opt `n += 1` (parser line) reaches the `ALOAD 0 … IADD … PUTFIELD IntRef.elem` ranges in `counter$$anonfun$1` of Closures$ and stays alive through classfile; Givens-opt `shown(...)` reaches its range in Givens$ (first line `GETSTATIC Givens$package$.MODULE$`, the call's INVOKEVIRTUAL follows in the same range); Givens-opt DefDef #1444 switches class Show$given_Show_String$ → Show$given_Show_Int$. Over every node of the last tree version: Givens-opt 16 direct / 21 via-ancestor / 508 in another class / 20 none; Closures-opt 75 / 74 / 98 / 8 (one elided before classfile).
- Screenshots: `shots/stack-trace-bytecode.png`, `shots/stack-trace-bytecode-givens.png`, `shots/stack-trace-switch.png`.

### Stack: line-range selection ribbon
- **Select:** click a line-number gutter cell (front card) selects a line, drag across the gutter or shift-click extends. Tokens still trace on click; the gutter only selects. Esc clears the range first, then the trace, then closes.
- **Propagation:** the lines map to the outermost tree nodes inside them (`nodesInLines`; for a range inside a single node, its innermost cover). `M.trace` now takes a node set, so the lineage (derive kept/copy/rewrite/retype, codegen into InsnRange on JVM cards, elide → nothing) of the whole set is followed through all cards, and each card's covered rows are the union of the nodes' line spans (ghost rows between included). Rows get a blue band, the barcode strip a blue marker, the placeholder canvas a tint; each card scrolls to the band when it comes forward. Side panel (right): per phase `lines a–b · n rows · +x −y · moved from c–d`; click flies to the card. More than 500 nodes → highlight by trimmed line text only, panel says so.
- **Sync:** `R.ST.range` is viewer state: the scrubber highlights the nodes (`.n.rg`) in the current version, the bytecode pane highlights their instruction ranges, and `range=<phase>:<lo>-<hi>` is in the deep link (tree-origin ranges; 1-based lines of that phase's text). Reopening the stack restores the selection.
- **Cost:** 40-line range on util-opt Signatures (29 versions): 14 nodes, 130 ms for node resolution plus trace over all cards (done once per selection; row spans are computed lazily per card when it has rows). Closures-opt lines 4–12: 2 nodes, 1.8 ms.
- Verified: Closures-opt parser lines 4–12 → lambdaLift (rows 12–19, `+3 −5`) and genBCode (ranges at rows 60–102); after closing the overlay `.n.rg` spans appear in the scrubber and the hash carries `range=parser:4-12`; loading `#range=parser:4-12&pname=lambdaLift` restores it (2 nodes, 8 highlighted spans). Not done: hash for ranges selected on bytecode cards; IR-origin ranges highlight only in the bytecode pane.
- Screenshots: `shots/stack-range-closures-lambdalift.png`, `stack-range-closures-genbcode.png`, `stack-range-signatures.png`, `stack-range-viewer.png`.

### Pages: chapters, granularity, ◀ ▶
- **Granularity** (view options → Pages, hash `gran=chapters|top|all`, default chapters): chapters, ★ top 5 by the chosen metric (previous behaviour), or every version with a change. `R.interesting()` follows it, so ★ on the bands/heat strip, the phases table, the stack's ★ cards and `i`/`I` all follow. Chapter boundaries are marked on the heat strip (blue left edge) and on the stack minimap.
- **Chapters** (`R.chapters()` in `js/scores.js`): consecutive versions are coalesced until the accumulated structural change (new/rewritten/rebuilt/removed nodes, retypes excluded; bytecode phases: instructions added + removed) reaches max(N nodes, P % of the unit's nodes at that point; bytecode phases are measured against the instructions emitted at genBCode). A chapter always starts at parser, typer, erasure, genBCode and jvm:classfile. Named by its dominant phase, "(+ n minor phases)" counts the other members that changed anything. The two numbers are in view options (`chn=`, `chp=` in the hash).
- **The thresholds you specified (8 nodes, 3 %) give 23–30 chapters per unit here** (Givens 26, Closures 23, Signatures 30), not 6–10: almost every phase is above 3 % of the nodes on its own, so nothing coalesces. I set the default to **8 nodes / 80 %**, which gives Givens 10, Closures 9, Signatures 17 (counts at 60 %: 10/11/18, 100 %: 8/7/16). Signatures stays high because each bytecode phase there scores 7–10k instructions against 6.4k emitted at genBCode; those scores look inflated by the greedy diff fallback used above 1.5 M cells, which I have not chased. Please judge the lists below and tell me the numbers you want.
**Givens.scala** — 10 chapters (spec thresholds 8 nodes / 3 %: 26)
1. parser — parser (score 0)
2. typer — typer (score 247)
3. firstTransform (+ 1 minor phase) — posttyper → firstTransform (score 225)
4. expandSAMs (+ 4 minor phases) — expandSAMs → getters (score 105)
5. mixin+… (+ 2 minor phases) — erasure → mixin+… (score 431)
6. lambdaLift (+ 5 minor phases) — constructors → selectStatic (score 202)
7. genBCode (bytecode) — genBCode (bytecode) (score 566)
8. jvm:inliner (+ 13 minor phases) — jvm:inliner → jvm:localVariables (score 457)
9. jvm:lineNumbers — jvm:lineNumbers (score 10)
10. jvm:classfile — jvm:classfile (score 11)

**Closures.scala** — 9 chapters (spec thresholds 8 nodes / 3 %: 23)
1. parser — parser (score 0)
2. typer — typer (score 99)
3. firstTransform (+ 2 minor phases) — posttyper → expandSAMs (score 145)
4. patternMatcher (+ 2 minor phases) — elimRepeated → specializeFunctions (score 84)
5. erasure (+ 4 minor phases) — erasure → flatten (score 205)
6. moveStatic — moveStatic (score 1)
7. genBCode (bytecode) — genBCode (bytecode) (score 226)
8. jvm:inliner (+ 9 minor phases) — jvm:inliner → jvm:lineNumbers (score 155)
9. jvm:classfile — jvm:classfile (score 3)

**Signatures.scala** — 17 chapters (spec thresholds 8 nodes / 3 %: 30)
1. parser — parser (score 0)
2. typer — typer (score 3720)
3. patternMatcher (+ 6 minor phases) — posttyper → patternMatcher (score 6047)
4. vcInlineMethods (+ 6 minor phases) — interpolators → collectNullableFields (score 739)
5. erasure (+ 10 minor phases) — erasure → selectStatic (score 6566)
6. genBCode (bytecode) — genBCode (bytecode) (score 6390)
7. jvm:inliner — jvm:inliner (score 8296)
8. jvm:r1:nullness — jvm:r1:nullness (score 8462)
9. jvm:r1:boxUnbox (+ 1 minor phase) — jvm:r1:dce → jvm:r1:boxUnbox (score 7402)
10. jvm:r1:copyProp — jvm:r1:copyProp (score 9968)
11. jvm:r1:staleStores — jvm:r1:staleStores (score 9930)
12. jvm:r1:pushPop (+ 1 minor phase) — jvm:r1:redundantCasts → jvm:r1:pushPop (score 8565)
13. jvm:r1:simplifyJumps (+ 1 minor phase) — jvm:r1:storeLoadPairs → jvm:r1:simplifyJumps (score 8509)
14. jvm:r2:staleStores (+ 3 minor phases) — jvm:r2:dce → jvm:r2:staleStores (score 8164)
15. jvm:localVariables (+ 3 minor phases) — jvm:r2:redundantCasts → jvm:localVariables (score 8917)
16. jvm:lineNumbers — jvm:lineNumbers (score 32)
17. jvm:classfile — jvm:classfile (score 9020)


- **UI.** Stack overlay: pager above the legend (large ◀ ▶, "chapter k/n · name", dots; ★ dots are chapters that end on a top-5 version), keys ← / → (and `i`/`I`), horizontal two-finger swipe (accumulated, threshold 60, debounced against trackpad inertia; ⌥+horizontal wheel scrolls long lines instead) and touch swipe turn pages and fly the stack to the chapter's last version. Viewer: same pager at the bottom of the explorer panel, setting the phase. **In the viewer ← / → still step events** (changing them would break the event cursor workflow); use ◀ ▶, `i`/`I` or `[`/`]` there — say if you want ←/→ remapped there too.
- Screenshots: `shots/pages-stack.png` (Closures-opt, chapter 2/8), `shots/pages-viewer.png`.

### Pages v2: metric, boundaries, keys (supersedes the chapter lists above)
- **Metric:** chapter score = changed printed lines (added + removed from the shared line diff); bytecode phases: ranges rewritten + elided + inlined-from from the range derives, against the ranges emitted at genBCode. `chm=nodes` (view options → chapter metric) keeps a node metric (new / rewritten / removed; rebuilt spine and retypes excluded). Threshold max(6 lines, 4 % of the unit's current line count) (`chn=`, `chp=`).
- **Boundaries:** parser, typer, erasure, genBCode and jvm:classfile are chapters on their own and coalescing restarts after them; a chapter that ends under the threshold joins the previous one; a forced phase scoring < 2 (not parser) joins the previous one too. Header cue: "chapter 5/9 · erasure · +43 −40 lines" (instr for bytecode).
- **Diff fix found on the way:** above 1.5 M cells `R.diffRows` used a greedy first-match fallback that matched `}` / blank lines against the wrong places and inflated Signatures (posttyper showed +1397 −1197, now +206 −6). It is now a patience diff (unique-line anchors + LIS, LCS on small gaps), so all line counts, the heat strip and the stack barcodes on big units changed.
- **Keys, one scheme everywhere:** ← / → = page (chapter / ★ / change), ⇧← / ⇧→ = small step (viewer: one event; stack: one version). Stack and viewer both; `i`/`I` are aliases for →/←. Cheat sheet updated.
- **The numbers do not reach your expectation (Givens ~9, Closures ~8, Signatures ~10–11):** at 4 % / 8 % I get Givens 26 / 23, Closures 20 / 18, Signatures 24 / 20 chapters, because individual phases are big relative to the unit (Givens typer +48 −18 of 226 lines; erasure +43 −40; lambdaLift +42 −33; a typical phase is 5–30 % of the lines). The percentage needed for ~9–10 chapters is about 50–75 % (Givens 50 %: 11, 75 %: 10; Closures 75 %: 8; Signatures 75 %: 16, 150 %: 13, bytecode phases dominate there), which no longer reads as "a few percent of the unit". The default stays at your 4 %; tell me which numbers (or a different rule, e.g. relative to the median phase) you want. Lists at 4 % and 8 %:

**Givens.scala** at 4 % — 26 chapters
1. parser — parser (+0 −0 lines, score 0)
2. typer — typer (+48 −18 lines, score 66)
3. posttyper — posttyper (+12 −0 lines, score 12)
4. firstTransform — firstTransform (+9 −14 lines, score 23)
5. expandSAMs — expandSAMs (+9 −1 lines, score 10)
6. elimRepeated (+ 1 minor phase) — elimRepeated → interpolators (+3 −5 lines, score 8)
7. getters (+ 1 minor phase) — seqLiterals → getters (+8 −7 lines, score 15)
8. erasure — erasure (+43 −40 lines, score 83)
9. arrayApply — arrayApply (+7 −2 lines, score 9)
10. mixin+…  — mixin+… (+102 −16 lines, score 118)
11. constructors — constructors (+49 −16 lines, score 65)
12. lambdaLift — lambdaLift (+42 −33 lines, score 75)
13. flatten — elimStaticThis → flatten (+86 −80 lines, score 166)
14. moveStatic — moveStatic → selectStatic (+90 −82 lines, score 172)
15. genBCode (bytecode) — genBCode (bytecode) (+566 −0 instr, score 331)
16. jvm:inliner — jvm:inliner (+170 −10 instr, score 54)
17. jvm:r1:nullness — jvm:r1:nullness (+18 −9 instr, score 23)
18. jvm:r1:dce — jvm:r1:dce (+0 −19 instr, score 26)
19. jvm:r1:boxUnbox (+ 1 minor phase) — jvm:r1:boxUnbox → jvm:r1:copyProp (+5 −7 instr, score 15)
20. jvm:r1:staleStores — jvm:r1:staleStores (+17 −17 instr, score 22)
21. jvm:r1:pushPop (+ 1 minor phase) — jvm:r1:redundantCasts → jvm:r1:pushPop (+4 −58 instr, score 49)
22. jvm:r1:simplifyJumps — jvm:r1:simplifyJumps (+32 −49 instr, score 41)
23. jvm:r2:dce — jvm:r2:dce (+0 −4 instr, score 14)
24. jvm:r2:pushPop (+ 2 minor phases) — jvm:r2:staleStores → jvm:r2:storeLoadPairs (+1 −5 instr, score 15)
25. jvm:localVariables (+ 1 minor phase) — jvm:localVariables → jvm:lineNumbers (+16 −16 instr, score 33)
26. jvm:classfile — jvm:classfile (+0 −0 instr, score 11)

**Closures.scala** at 4 % — 20 chapters
1. parser — parser (+0 −0 lines, score 0)
2. typer — typer (+14 −12 lines, score 26)
3. firstTransform (+ 1 minor phase) — posttyper → firstTransform (+5 −5 lines, score 10)
4. expandSAMs — expandSAMs (+24 −5 lines, score 29)
5. patternMatcher (+ 2 minor phases) — elimRepeated → specializeFunctions (+20 −11 lines, score 31)
6. erasure — erasure (+25 −22 lines, score 47)
7. mixin+…  — mixin+… (+7 −5 lines, score 12)
8. constructors — constructors (+13 −5 lines, score 18)
9. lambdaLift — lambdaLift (+61 −42 lines, score 103)
10. flatten (+ 1 minor phase) — flatten → moveStatic (+30 −30 lines, score 60)
11. genBCode (bytecode) — genBCode (bytecode) (+226 −0 instr, score 146)
12. jvm:inliner — jvm:inliner (+55 −3 instr, score 19)
13. jvm:r1:nullness (+ 1 minor phase) — jvm:r1:nullness → jvm:r1:dce (+4 −6 instr, score 6)
14. jvm:r1:copyProp — jvm:r1:copyProp (+6 −6 instr, score 21)
15. jvm:r1:staleStores — jvm:r1:staleStores (+10 −10 instr, score 17)
16. jvm:r1:pushPop — jvm:r1:pushPop (+0 −26 instr, score 21)
17. jvm:r1:storeLoadPairs — jvm:r1:storeLoadPairs (+0 −2 instr, score 7)
18. jvm:r1:simplifyJumps — jvm:r1:simplifyJumps (+4 −9 instr, score 8)
19. jvm:localVariables (+ 1 minor phase) — jvm:localVariables → jvm:lineNumbers (+0 −0 instr, score 14)
20. jvm:classfile — jvm:classfile (+0 −0 instr, score 3)

**Signatures.scala** at 4 % — 24 chapters
1. parser — parser (+0 −0 lines, score 0)
2. typer — typer (+1842 −522 lines, score 2364)
3. posttyper — posttyper (+206 −6 lines, score 212)
4. expandSAMs (+ 2 minor phases) — inlining → expandSAMs (+222 −79 lines, score 301)
5. patternMatcher (+ 2 minor phases) — elimRepeated → patternMatcher (+3427 −1103 lines, score 4530)
6. vcInlineMethods (+ 1 minor phase) — interpolators → vcInlineMethods (+146 −49 lines, score 195)
7. seqLiterals (+ 3 minor phases) — seqLiterals → collectNullableFields (+139 −81 lines, score 220)
8. erasure — erasure (+1687 −3208 lines, score 4895)
9. arrayApply (+ 1 minor phase) — elimErasedValueType → arrayApply (+70 −121 lines, score 191)
10. tailrec — tailrec (+398 −283 lines, score 681)
11. mixin+…  — mixin+… (+99 −47 lines, score 146)
12. lambdaLift (+ 1 minor phase) — constructors → lambdaLift (+1941 −1849 lines, score 3790)
13. flatten — elimStaticThis → flatten (+1447 −1256 lines, score 2703)
14. moveStatic — moveStatic → selectStatic (+481 −481 lines, score 962)
15. genBCode (bytecode) — genBCode (bytecode) (+6390 −0 instr, score 4880)
16. jvm:inliner — jvm:inliner (+812 −234 instr, score 368)
17. jvm:r1:copyProp (+ 3 minor phases) — jvm:r1:nullness → jvm:r1:copyProp (+300 −320 instr, score 1092)
18. jvm:r1:staleStores — jvm:r1:staleStores (+257 −257 instr, score 612)
19. jvm:r1:pushPop (+ 1 minor phase) — jvm:r1:redundantCasts → jvm:r1:pushPop (+11 −526 instr, score 846)
20. jvm:r1:storeLoadPairs — jvm:r1:storeLoadPairs (+0 −88 instr, score 335)
21. jvm:r1:simplifyJumps — jvm:r1:simplifyJumps (+75 −148 instr, score 267)
22. jvm:r2:copyProp (+ 3 minor phases) — jvm:r2:dce → jvm:r2:staleStores (+30 −34 instr, score 215)
23. jvm:localVariables (+ 4 minor phases) — jvm:r2:redundantCasts → jvm:lineNumbers (+571 −592 instr, score 1641)
24. jvm:classfile — jvm:classfile (+0 −0 instr, score 14)

**Givens.scala** at 8 % — 23 chapters
1. parser — parser (+0 −0 lines, score 0)
2. typer — typer (+48 −18 lines, score 66)
3. posttyper — posttyper (+12 −0 lines, score 12)
4. firstTransform — firstTransform (+9 −14 lines, score 23)
5. expandSAMs — expandSAMs (+9 −1 lines, score 10)
6. elimRepeated (+ 1 minor phase) — elimRepeated → interpolators (+3 −5 lines, score 8)
7. getters (+ 1 minor phase) — seqLiterals → getters (+8 −7 lines, score 15)
8. erasure — erasure (+43 −40 lines, score 83)
9. arrayApply — arrayApply (+7 −2 lines, score 9)
10. mixin+…  — mixin+… (+102 −16 lines, score 118)
11. constructors — constructors (+49 −16 lines, score 65)
12. lambdaLift — lambdaLift (+42 −33 lines, score 75)
13. flatten — elimStaticThis → flatten (+86 −80 lines, score 166)
14. moveStatic — moveStatic → selectStatic (+90 −82 lines, score 172)
15. genBCode (bytecode) — genBCode (bytecode) (+566 −0 instr, score 331)
16. jvm:inliner — jvm:inliner (+170 −10 instr, score 54)
17. jvm:r1:dce (+ 1 minor phase) — jvm:r1:nullness → jvm:r1:dce (+18 −28 instr, score 49)
18. jvm:r1:staleStores (+ 2 minor phases) — jvm:r1:boxUnbox → jvm:r1:staleStores (+22 −24 instr, score 37)
19. jvm:r1:pushPop (+ 1 minor phase) — jvm:r1:redundantCasts → jvm:r1:pushPop (+4 −58 instr, score 49)
20. jvm:r1:simplifyJumps — jvm:r1:simplifyJumps (+32 −49 instr, score 41)
21. jvm:r2:dce (+ 3 minor phases) — jvm:r2:dce → jvm:r2:storeLoadPairs (+1 −9 instr, score 29)
22. jvm:localVariables (+ 1 minor phase) — jvm:localVariables → jvm:lineNumbers (+16 −16 instr, score 33)
23. jvm:classfile — jvm:classfile (+0 −0 instr, score 11)

**Closures.scala** at 8 % — 18 chapters
1. parser — parser (+0 −0 lines, score 0)
2. typer — typer (+14 −12 lines, score 26)
3. firstTransform (+ 1 minor phase) — posttyper → firstTransform (+5 −5 lines, score 10)
4. expandSAMs — expandSAMs (+24 −5 lines, score 29)
5. patternMatcher (+ 2 minor phases) — elimRepeated → specializeFunctions (+20 −11 lines, score 31)
6. erasure — erasure (+25 −22 lines, score 47)
7. mixin+…  — mixin+… (+7 −5 lines, score 12)
8. constructors — constructors (+13 −5 lines, score 18)
9. lambdaLift — lambdaLift (+61 −42 lines, score 103)
10. flatten (+ 1 minor phase) — flatten → moveStatic (+30 −30 lines, score 60)
11. genBCode (bytecode) — genBCode (bytecode) (+226 −0 instr, score 146)
12. jvm:inliner — jvm:inliner (+55 −3 instr, score 19)
13. jvm:r1:copyProp (+ 2 minor phases) — jvm:r1:nullness → jvm:r1:copyProp (+10 −12 instr, score 27)
14. jvm:r1:staleStores — jvm:r1:staleStores (+10 −10 instr, score 17)
15. jvm:r1:pushPop — jvm:r1:pushPop (+0 −26 instr, score 21)
16. jvm:r1:simplifyJumps (+ 1 minor phase) — jvm:r1:storeLoadPairs → jvm:r1:simplifyJumps (+4 −11 instr, score 15)
17. jvm:localVariables (+ 1 minor phase) — jvm:localVariables → jvm:lineNumbers (+0 −0 instr, score 14)
18. jvm:classfile — jvm:classfile (+0 −0 instr, score 3)

**Signatures.scala** at 8 % — 20 chapters
1. parser — parser (+0 −0 lines, score 0)
2. typer — typer (+1842 −522 lines, score 2364)
3. posttyper — posttyper (+206 −6 lines, score 212)
4. expandSAMs (+ 2 minor phases) — inlining → expandSAMs (+222 −79 lines, score 301)
5. patternMatcher (+ 2 minor phases) — elimRepeated → patternMatcher (+3427 −1103 lines, score 4530)
6. vcInlineMethods (+ 5 minor phases) — interpolators → collectNullableFields (+285 −130 lines, score 415)
7. erasure — erasure (+1687 −3208 lines, score 4895)
8. tailrec (+ 2 minor phases) — elimErasedValueType → tailrec (+468 −404 lines, score 872)
9. mixin+… (+ 1 minor phase) — mixin+… → constructors (+199 −84 lines, score 283)
10. lambdaLift — lambdaLift (+1841 −1812 lines, score 3653)
11. flatten — elimStaticThis → flatten (+1447 −1256 lines, score 2703)
12. moveStatic — moveStatic → selectStatic (+481 −481 lines, score 962)
13. genBCode (bytecode) — genBCode (bytecode) (+6390 −0 instr, score 4880)
14. jvm:inliner (+ 1 minor phase) — jvm:inliner → jvm:r1:nullness (+864 −260 instr, score 412)
15. jvm:r1:copyProp (+ 2 minor phases) — jvm:r1:dce → jvm:r1:copyProp (+248 −294 instr, score 1048)
16. jvm:r1:staleStores — jvm:r1:staleStores (+257 −257 instr, score 612)
17. jvm:r1:pushPop (+ 1 minor phase) — jvm:r1:redundantCasts → jvm:r1:pushPop (+11 −526 instr, score 846)
18. jvm:r1:storeLoadPairs (+ 1 minor phase) — jvm:r1:storeLoadPairs → jvm:r1:simplifyJumps (+75 −236 instr, score 602)
19. jvm:localVariables (+ 8 minor phases) — jvm:r2:dce → jvm:lineNumbers (+601 −626 instr, score 1856)
20. jvm:classfile — jvm:classfile (+0 −0 instr, score 14)


Screenshot: `shots/pages-stack.png`.
