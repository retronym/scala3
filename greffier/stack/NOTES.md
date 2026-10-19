# Stack view (PoC)

A standalone page that shows the version history of one unit as a 3D stack of cards, newest version in front, earlier phases receding behind it (after macOS Time Machine). It reuses only `../ui/js/loader.js` (loaded as a classic script from the existing viewer, not copied). No build step, no libraries; CSS 3D transforms with one `perspective` on the stage.

- `index.html`: layout and styles.
- `js/model.js`: loading, per-version cards (text, offsets, line diff against the previous version, ★ selection), node-at-click and lineage tracing.
- `js/stack.js`: card DOM, depth cursor and frame loop, text/placeholder tiers, hover, projected lineage thread.
- `js/main.js`: unit selector, file picker, minimap, lineage chronicle, fps readout.
- `shoot.mjs` + `steps.json` / `perf.json` / `corpus.json`: headless Brave (CDP) driver that writes `shots/*.png` and prints frame timings.

Open http://localhost:8766/stack/ (serves `local/replay/`). `?src=../ui/data/corpus.jsonl` loads another recording; the file picker loads a local one.

## What works

- Cards along z with perspective; receding cards dim with depth. Wheel/trackpad, ↑/↓, PageUp/PageDown, Home/End move through depth. The cursor follows its target with an exponential (critically damped) follow and snaps to the nearest card 140 ms after the last wheel event. A trackpad flick goes many phases (a decaying 70-event flick from the front landed on version 48 of 85). Passed cards fly toward the camera and fade out. `prefers-reduced-motion` makes every move a jump.
- Diff: each card's lines are diffed (LCS after common prefix/suffix) against the previous version. The front card shows added lines in green and removed lines as struck-through ghosts in place. Every card has a change barcode under its header (one mark per added/removed row, file start on the left). The barcodes are what shows on receding cards, so the stack reads as a phase × position map of where changes happen (`shots/06-skip-noops.png` shows this best).
- Header on every card: index, phase (fused miniphase groups shown as `first +n`), `+add −del`. ★ cards (the 6 most changed by lines plus non-copy derives, the same idea as the flat viewer) get a gold border, brighter header and a stacked-edge shadow. No-op versions (printed text identical to the previous one) are 4 px translucent slivers with a quarter of the depth spacing, or are removed with "skip no-ops".
- Lineage: clicking a token (or the line's leading whitespace, for the outermost node starting on that line) on the front card picks a node from the version's offsets and traces it through all versions. Each step keeps node ids present in both versions and follows `derive` records (new→old backwards, old→new forwards) otherwise. Per version it records born / kept / copied / retyped / rewritten / reprinted (same node, printed line changed) / removed. The thread is an SVG overlay projected with the same perspective math as the CSS: from the clicked line up to the front barcode, then through the matching barcode position on every receding card, coloured by what happened at that step; a white-ringed dot marks birth and ✕ marks removal. Every receding card's header shows the traced line's text as of that phase, so the peeking strips read as that line's history. The chronicle panel lists the non-trivial steps and flies to them on click. Example: `def show(a: Int)` in Givens is born at parser, rewritten at typer and firstTransform, retyped at erasure, and lives through all 85 versions (`shots/02`–`04`).
- When a trace is active, every card's text is scrolled so the traced row sits where it sits on the front card, so placeholders and text line up as cards come forward. Otherwise scroll position is proportional. ⌥/⇧+wheel or j/k scrolls the front card's text.
- Hovering a receding card slides it forward and up and removes its dimming; clicking it flies to it.
- Minimap on the right: one tick per version (length ~ √change, ★ in gold, no-ops short and faint), lineage status dots when a trace is active, current-position marker, click/drag to jump, hover tooltip with the phase name.
- Unit selector for multi-unit recordings (corpus.jsonl: 5 units × 85 versions).

## Performance

Measured in headless Brave at 1500×900, dpr 1, GPU on (`node shoot.mjs perf.json`), Apple Silicon:

| case | frame interval avg / p95 / max | JS work per frame avg / p95 / max |
|---|---|---|
| Givens, 70-event flick from front | 16.7 / 16.7 / 16.8 ms | 0.69 / 1.8 / 3.4 ms |
| Givens, continuous sweep front→back in 4 s | 16.7 / 16.7 / 16.8 ms | 0.48 / 1.0 / 1.8 ms |
| corpus Patmat (85 versions, up to 420 rows), same sweep | 16.7 / 16.7 / 16.8 ms | 0.59 / 1.4 / 4.5 ms |

With `--disable-gpu` (software compositing) p95 drops to 33 ms, so the 60 Hz depends on GPU compositing of the ~30 visible card layers.

Other numbers: loading Givens.jsonl (1 MB) ~10 ms, corpus.jsonl (3.4 MB) ~35 ms (the loader's lazy mode); line diffs for all 85 versions of a unit 1–3 ms; a trace through 85 versions 1.5–2 ms. Only cards within 8 depth units of the cursor get text DOM; at most two are built per frame; text fades in over 2.5 depth units as a card approaches and the canvas placeholder (row blocks plus diff and trace bars, half resolution) fades out. Text DOM is dropped beyond depth 11. Cards beyond depth 30 are `display: none`.

Not measured: 100+ versions of a 1000-line unit (the largest at hand is 420 rows), and real trackpad input in a visible window (the built-in browser pane was hidden during this session, so rAF did not run there; the page falls back to a timer when rAF does not fire, which is what the in-pane screenshots used).

## Stack vs the flat scrubber

Good for:
- Seeing where in the file each phase changed things and how phases cluster. With no-ops skipped, the barcode stack is a compact phase × position heat map.
- Following one tree through the pipeline. The thread plus the per-card line text answers "where was this born, which phases touched it, when did it go" without stepping through 85 versions.
- Getting a sense of scale: how few of the 85 tree versions differ at all (67 of 85 no-ops in Givens, 71 in Closures).

Worse for:
- Reading. Only the front card is readable; the flat viewer shows the tree, types, events and bytecode side by side.
- Comparing two arbitrary versions (only adjacent diffs are shown).
- Long files: the barcode is one mark per row across ~1000 px, and the visible part of a receding card is a ~10–30 px strip.
- Discoverability: the click-to-trace, ⌥+wheel and hover gestures are invisible until explained.

## What integration would need

From the recorder:
- Per-definition versions (or a def → line-range index per version). The stack would be much more useful per method: smaller cards with readable text further back, and barcodes that mean something at member granularity. Today a whole unit is one card.
- A stable identity for "the same tree" across phases that is not a chain of derive heuristics. Tracing now walks `derive` records and treats `how: heuristic` pairs the same as exact ones; a pairing confidence per edge and a marker for nodes that are recreated without a derive (ids that disappear with no `elide`) would let the thread distinguish "rewritten" from "lost track".
- An explicit no-op flag per version. The page compares printed text; `textSameAs` covers most of it, but a flag saying "the phase did not touch this unit" would be cheaper and more precise.
- Bytecode: JVM class versions are not shown at all. They would need their own stack per class, or a codegen derive pairing strong enough to continue the thread from a DefDef into its method's instructions.

From the viewer:
- A shared line-diff/scores module. `model.js` has its own diff (LCS with ghost rows); `ui/js/scores.js` has a cheaper greedy count. One implementation should serve both.
- The trace should become a shared notion of "selected node" so the stack can be a mode of the main viewer (enter the stack from the current version/selection, leave it with the selection kept).
- The loader is a classic script that writes `window.R`; an ES-module export would make reuse cleaner.

## Known rough edges

- Lineage via ancestors can fan out: tracing a large node that gets split collects up to 80 nodes per version; the thread follows the earliest-starting one.
- The thread segment from the clicked line to the front barcode crosses the front card's text (drawn dashed).
- Clicking a no-op sliver is hard (4 px); use the minimap or keys.
- Light theme is not implemented.
