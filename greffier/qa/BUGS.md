# Greffier exploratory test: bugs and confusions

Tested 2026-10-19 in the built-in browser at 1440×900 (plus 1024×768 and 375×812), deployed site https://greffier.zaugg.id.au/ primarily, local copy (`local/replay/ui`, served on :8767 because :8766 kept being killed) to check whether a bug is deploy-only. The local copy is ahead of the deploy (it has a load-error banner the site lacks); "local: yes" means the bug reproduces there too. Screenshots are 800 px wide downscales in `qa/shots/`. LLM-assisted (Claude) testing and write-up.

**Count: 0 blocker · 7 major · 21 minor · 7 cosmetic** (35 bugs), plus 15 confusions.

Deployed recordings use content-hashed file names; links below use the current hashes (stale hashes redirect, see M1 for unhashed ones). `D` = `https://greffier.zaugg.id.au/`.

## Major

### M1. Unhashed `src=data/<name>.jsonl` links 404 on the deployed site (every TOURS.md link fails)
- Severity: major · deploy only (local serves unhashed names)
- Repro: open `D#src=data/Givens.jsonl&skip=1&collapse=1&pname=parser` (the form TOURS.md uses), or `curl -I D/data/Closures-opt.jsonl`.
- Expected: the worker maps an unhashed base name to the current hashed file, as it already does for stale hashes (`data/Closures-opt.00000000.jsonl` → 302 to `Closures-opt.46c8cdef.jsonl`).
- Actual: 404. The only feedback on the deployed build is the footer text "cannot fetch data/Givens.jsonl" and the recordings panel opening; when the page already had a recording, it stays loaded behind the panel while the address bar says Givens. (Local has a proper error banner, not deployed yet.)
- Shot: `shots/unhashed-link-404.jpg`

### M2. Chapter pages land on the chapter's last version, which is usually a no-op phase, so the chapter's change is invisible
- Severity: major · local: yes
- Repro: `D#src=data/Closures-opt.46c8cdef.jsonl&pname=parser`, with "skip no-op versions" off (view options), press → three times.
- Expected: each page shows the chapter's change, i.e. the dominant phase named in the pager ("typer", "expandSAMs", "patternMatcher") with its diff underlined.
- Actual: lands on pickleQuotes / dropForMap / simplifySynchronized, each "no changes since …, 100% shared", nothing underlined; the pager says "chapter 2/14 · typer" over a pickleQuotes tree. With "skip no-op versions" on it lands on the last *changing* member instead (chapter "typer" shows posttyper's 6-node diff, not typer's 72). Same in Givens (`D#src=data/Givens.27d3ea29.jsonl&pname=parser`, → ×2 → simplifySynchronized). Cause: `R.interesting()` returns `vs[c.to]` for chapters.
- Shot: `shots/chapter-lands-on-noop-phase.jpg`

### M3. `i`/`I` and the ★ markers follow chapter ends, not the most-changed phases
- Severity: major · local: yes
- Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=typer`, press ⇧P (phases table); then load `D#src=data/Closures-opt.46c8cdef.jsonl&pname=parser` and press `i` repeatedly, compare with →.
- Expected (per `?`: "next / previous interesting phase (most changed by the chosen metric, ★ on the bands)"): ★ on typer, erasure, mixin…; `i` jumps between them.
- Actual: in the default "chapters" granularity `i` ≡ → and `I` ≡ ← (same stops, same wrap-around). The phases table stars parser and pickleQuotes (0 changes) but not typer (240 structural). Only granularity "★ top 5" gives the documented behaviour.
- Shot: `shots/phases-table-stars-on-noop.jpg`

### M4. `Rejected`, `Scope` and `Tentative` events are printed as raw JSON with unresolved ids
- Severity: major · local: yes (`inspector.js` has no case for them)
- Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=typer&seq=334`.
- Expected: e.g. "✗ rejected Show.given_Show_String (not eligible)", like `Candidate`; `Scope` with symbol names.
- Actual: `334 Rejected {"ref":122,"reason":"notEligible"}`, `453 Scope {"scope":"implicitScope","eligible":[168],"levels":[0],"result":"success","ambiguous":[]}`, `455 Tentative {"outcome":"kept","reports":0}`. The event's `syms` are not shown. Rejected has no chip colour (missing from `KCOL`) and is not counted by "problems only". Givens has 209 Rejected events, so the stack under most implicit searches is dominated by these lines. This is the data that would answer TOURS.md's "why the local given beats Show.given_Show_Int".
- Shot: `shots/rejected-raw-json.jpg`

### M5. Dependency graph labels every node `*`
- Severity: major · local: yes
- Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=typer`, press `3`, expand "graph of this neighbourhood".
- Expected: node labels Predef, Object, Int, scala.runtime …
- Actual: twelve nodes labelled `*` and one `scala` (the label is the text after the last `.` of `Predef.*`). Only the edge `<title>` tooltips carry names. The graph is also drawn as a ~60 px star in the middle of a 1156×280 canvas, not fitted (see m15).
- Shot: `shots/deps-graph-tiny.jpg`

### M6. After a drop or a file-picker load the address bar keeps the previous recording, and "copy link" yields a link to the synthetic fixture
- Severity: major · local: yes
- Repro: open `D#src=data/Givens.27d3ea29.jsonl&pname=posttyper`, drop `Mixins.jsonl` on the page (or "choose files" → tick → Load selected); press →; click view options → copy link; open the copied link in a fresh tab.
- Expected: the address bar stops claiming Givens (clear `src`, or say a local file cannot be linked); copy link says it cannot link a local file.
- Actual: the address bar stays `#src=data/Givens…&pname=posttyper&seq=330` forever (reload shows Givens), copy link gives `D#pname=pickleQuotes&seq=295`, and a hash without `src` loads the built-in synthetic fixture (`start()` → `loadFixture()`), which has no pickleQuotes and silently lands on constructors.
- Shot: none (the bug is in the address bar).

### M7. Loading a recording from the panel carries the previous recording's `pname`/`seq`
- Severity: major · local: yes
- Repro: open `D#src=data/jvm-fixture.d1cd7734.jsonl` (lands at genBCode, seq 0), open the recordings panel, Load Closures.
- Expected: Closures opens at its start (or at the same phase name with a consistent cursor).
- Actual: URL `#src=data/Closures…&pname=genBCode&seq=0`; banner "parser in progress (seq 0); showing genBCode output. The parser tree appears at seq 1."; the pager says chapter 8/10 while the phase axis says 1/87 parser.
- Shot: `shots/panel-load-carries-pname.jpg`

## Minor

### m1. Every phase reports "1 removed": the root PackageDef
- Local: yes. Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=erasure` (also typer, posttyper, firstTransform, getters, mixin, constructors, lambdaLift), open "removed since …".
- Expected: removed list only for nodes that disappeared. Actual: the single entry is always `PackageDef package <empty> { … }`; the red "1 removed" chip is on every phase header and means nothing.
- Shot: `shots/removed-root-every-phase.jpg`

### m2. "show together" keeps the hidden current unit's header, removed list and chapter pager
- Local: not checked. Repro: `D#src=data/corpus-opt.4581bf0c.jsonl&unit=Patmat.scala&pname=typer`, tick ForDesugar.scala and Mixins.scala in the explorer, click "show together", press →.
- Expected: header/pager describe the shown units (or are hidden). Actual: header "typer · 320 nodes …", "removed since parser (1) PackageDef sealed trait Shape …" and pager "posttyper · +120 −4 lines" are Patmat's, above ForDesugar | Mixins panes. The multi selection is not in the URL. Ticking the boxes (before "show together") already switches the Dependencies panel to ForDesugar while the tree still shows Patmat.
- Shots: `shots/multi-header-wrong-unit.jpg`, `shots/deps-follows-ticked-unit.jpg`

### m3. Dependency-row click from a later phase gives an impossible banner
- Local: not checked. Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=posttyper`, click the Conversion row in Dependencies.
- Expected: cursor at seq 330 and the tree visible at that seq (parser output), as the Reports chip does. Actual: tree stays pinned at posttyper; banner "typer in progress (seq 330); showing posttyper output" (posttyper does not exist yet at seq 330).

### m4. `jvm:classfile` counts as a change for every method
- Local: yes (data). Repro: `D#src=data/Closures-opt.46c8cdef.jsonl&pname=jvm:classfile&ir=1&cls=Closures$`, open the method dropdown; open ▣ graph on any method at classfile.
- Expected: flag-only rewrites (`access flags 0x1` → `0x1000001`) are not changes. Actual: every method is "(1 change)" at least, classfile is a ★/"1 rewritten" for every class, the lightbox says "6/6 changes of this method · +0 −0 instr".

### m5. Method-panel ◀/▶ moves the version but not the cursor; numbering disagrees with the lightbox
- Local: not checked. Repro: `D#src=data/Closures-opt.46c8cdef.jsonl&pname=jvm:classfile&ir=1&cls=Closures$`, method dropdown → demo()I, "◀ previous change" ×12.
- Expected: cursor/band label follow, as `]`/`[` in the lightbox do (they move seq 783, 784 …). Actual: phase axis says "16/34 jvm:inliner" but the event cursor stays at seq 798 and the band label says "phase jvm:classfile". The panel says "step 1/13" at jvm:inliner, the lightbox "2/14 changes" for the same version. The chosen method is not in the URL.
- Shot: `shots/method-steps-band-label-mismatch.jpg`

### m6. Elided list: overlapping ranges counted separately, conflicting causes
- Local: not checked. Repro: `D#src=data/Closures-opt.46c8cdef.jsonl&pname=jvm:r1:pushPop&ir=1&cls=Closures$`, open "elided in jvm:r1:pushPop (10)".
- Expected: each removed instruction once, one cause. Actual: demo 12–15, 12–13, 13–15, 13–14, 14–15 are listed (and counted) separately; 12–13 says "no earlier POP or inlining cause found" while 12–15 says "dead since jvm:r1:staleStores: its result was POPped"; the ALOAD 0 receiver of the inlined `applyTwice` gets "no cause found"; the summary line repeats the first item's cause as if it applied to all ten. With this list and the method panel open, the bytecode text gets about six lines.
- Shot: `shots/elided-list-overlapping.jpg`

### m7. CFG lightbox draws a switch's case table in the target block
- Local only (switches exist only in util, which is not deployed; Patmat-opt has none, its matches compile to IF_ICMPNE chains). Repro: `http://localhost:8766/ui/#src=data/util-opt.jsonl&unit=Chars.scala&pname=jvm:classfile&ir=1&cls=dotty/tools/dotc/util/Chars$`, method isLineBreakChar(C)Z, ▣ graph.
- Expected: `10: L3 … default: L4` inside the L0 block with the LOOKUPSWITCH. Actual: they head block L3 ("L3 2 insns · lines 10–17"). Switch edges themselves render (label "10,12,13,26", "default").
- Shot: `shots/cfg-switch-cases-in-target-block.jpg`

### m8. "bytecode beside tree" toggle jumps to genBCode, where there is nothing beside
- Local: not checked. Repro: `D#src=data/Closures-opt.46c8cdef.jsonl&pname=lambdaLift`, view options → tick "bytecode beside tree".
- Expected: lambdaLift tree with the bytecode beside it. Actual: jumps to genBCode (bytecode only, `#bcbody` width 0); the split appears only after navigating back to a tree phase. Not in the URL.

### m9. Expanding the dependency graph in the All view renders it below the fold
- Local: not checked. Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=typer`, All view, click "graph of this neighbourhood".
- Expected: graph visible (panel scrolls or grows). Actual: controls appear, graph box at y 716–996 in a 900 px window; looks like nothing happened. In the Deps view (`3`) it is a tiny unlabelled star, not fitted.

### m10. Scope and hops change nothing for single-unit recordings
- Repro: as m9, switch scope "whole project" / "2 hops". Header stays "14 dependencies · 14 total". Expected: controls hidden or disabled when there is one unit.

### m11. 1024×768 leaves about four lines for the tree; 375 px is unusable
- Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=typer&node=386` at 1024×768; then at 375×812.
- Actual at 1024: legend, header and node info eat the scrubber; the tree text area is ~75 px. At 375: header wraps, phase select overflows to the right, panels collapse to 30 px slivers, the page is a fixed 600 px with empty space below.
- Shots: `shots/w1024-tree-4-lines.jpg`, `shots/w375-broken.jpg`

### m12. Hover tooltip stays up across phase changes
- Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=erasure`, hover `given_Conversion_Int_String`, then without moving the mouse press → (or paste another link). Expected: tooltip hides. Actual: the "at erasure" tooltip stays over the constructors (later: parser) tree until the mouse moves.
- Shot: `shots/stale-hover-tooltip.jpg`

### m13. Node counts disagree between header, phases table and phase select
- Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=typer`: header "196 structural", ⇧P table "240", phase select "typer (242 new)"; erasure 93 vs 108; posttyper 31 vs 36. Closures parser: "112 nodes" vs "parser (122 new)". Expected one number per metric, or labels that say what differs (hidden pure copies? synthetic nodes?).

### m14. Back/forward never steps through what you looked at
- Repro: open any recording, navigate phases, select nodes, then Back. Actual: all navigation uses replaceState (`history.length` stayed 2 after dozens of steps); Back leaves the recording (or goes to the previous recording). Expected: at least phase jumps and node selections push entries.

### m15. Pasted link without `node=` keeps the previous selection
- Repro: with `node=408` selected, paste `D#src=data/Givens.27d3ea29.jsonl&skip=1&collapse=1&pname=erasure`. Actual: URL becomes `…&node=408…`, selection kept. Expected: a pasted link describes the whole state.

### m16. Synthetic fixture disappears from the recordings panel after the first load
- Local: yes (`start()` only unshifts it on a bare first run). Repro: open `D`, load anything, reopen the panel: the "synthetic fixture" row is gone.

### m17. Deployed `recordings.json` leaks local absolute paths; util not deployed
- Repro: `curl D/recordings.json` or hover a variant chip: `-classpath=/Users/jzaugg-virtus/code/.worktrees/scala3/funny-chaplygin-0b0e4a/library/…`. util/util-opt are in the local list but not on the site, so switch edges (m7) cannot be seen on the deploy.

### m18. Explorer lists the template self `_` as a member of every class
- Repro: `D#src=data/Closures-opt.46c8cdef.jsonl&pname=parser`: Closures › `<init>`, `_`, counter …. In parser, Givens' given classes appear as unnamed runs of `<init>`/`_`/`show` (`D#src=data/Givens.27d3ea29.jsonl&pname=parser`).
- Shot: `shots/merged-explorer-anon-givens.jpg`

### m19. CFG lightbox ignores ← →
- Repro: open ▣ graph on Closures-opt demo()I. `[` `]` step changes; ← → do nothing although the buttons read "◀ [" and "] ▶".

### m20. Swipe is asymmetric when the tree overflows horizontally
- Repro: `D#src=data/Givens.27d3ea29.jsonl&pname=typer`, two-finger swipe left over the scrubber (deltaX > 0): nothing (scrolls the text), swipe right goes back a page. Documented in code, not in `?`.

### m21. Tour A in TOURS.md is stale (see Tours below)
- Steps 3, 5, 6 point at seqs that are now `Rejected` events; "rejected candidates are not recorded" is no longer true.

## Cosmetic

### c1. Grammar: "(1 classes)" in the phase select (`jvm:r1:nullness ● (1 classes)`), "1 dependencies · 14 total" (Deps, implicit tab).
### c2. Header shows the hashed file name ("Givens.27d3ea29.jsonl") when a recording is opened by link, "Givens" when opened from the panel.
### c3. `?` overlay: says `3` = "graph" while the header button is "Deps"; omits Alt-click (only in the scrubber hint line), swipe, the ▣ graph lightbox and its `[` `]`, the stack gutter range select, the bytecode method panel. Shot: `shots/help-overlay.jpg`
### c4. Stack overlay overlaps: trace panel line "born via De…genBCode (bytecode)" is overprinted; sliver labels ("13 phases, no change") sit on receding card headers; receding card text shows between card headers; the line-range panel covers the minimap; header status is truncated ("103 versions (73 no …"). Shot: `shots/stack-trace-overlaps.jpg`
### c5. The recordings panel repeats the whole Keys list under the file picker.
### c6. Recording checkboxes have the accessible name "on"; variant chips are named by their stats line only.
### c7. CFG lightbox at "fit" for summary$lzyINIT1 is unreadable (blocks a few px tall, no labels); zooming in works. Shot: `shots/cfg-lzyinit-fit.jpg`

## Confusions (worked, but I misread)

1. Clicking a variant chip ("-opt …") only selects it; nothing loads until Load. → Make the chip load, or put the Load button next to the chips.
2. "/ 612" next to each explorer row: unlabelled ("non-copy new nodes in this phase / over all phases" is only a tooltip). → A column header.
3. Pager "chapter 2/14 · typer" over a pickleQuotes tree (M2). → Show the phase range of the chapter ("typer … pickleQuotes").
4. Node info "events 3" vs tab "used here (4)": the tab includes ancestors' events. → "3 on this node, 4 with ancestors".
5. Stack: Home = newest, End = oldest, wheel down = older. Logical for "newest in front", the reverse of every editor. → Say it in the stack hint.
6. Stack parser card shows the printed tree, the scrubber shows source text; the typer card's diff is against the printed tree. → Label the card "parser (printed tree)".
7. Split bytecode header "lines 25–325 of 358" looked like the selected node's range; it is the rendered window. → "showing 25–325 of 358".
8. "skip no-op versions" and "collapse pure copies" persist across loads and change where → lands. → Show them as chips in the header when on.
9. Picking a method in the method panel does not scroll the main bytecode; the panel is the method view. → Scroll or highlight the method in the main pane.
10. Inspector title "events at this use of render (node #408)" while the stack below shows the cursor's ancestry in viaConversion (after the Reports chip). → Separate titles for "cursor stack" and "selection events".
11. "Stack" sits among the view tabs (All/Tree/Events/Deps) but is an overlay and does not change the view. → Separate it.
12. "★ top 5" granularity has 6 stops (genBCode and classfile are forced). → "★ top 5 + bytecode".
13. "1 removed" on every phase (m1) made me look for a deleted node each time.
14. The JVM phases all sit at the right end of the event axis; moving between them leaves seq at 798 in the method panel (m5), so the event label and the phase label disagree.
15. Swiping right over the tree did nothing (m20); I thought swipe was broken.

## Tours (TOURS.md, followed literally)

- All three tours fail on the deployed site at step 1 (M1). On the local copy:
- Tour A (Givens): steps 1, 2, 7 hold (step 2's text is wrapped across lines). Step 3: seq 334 is `Rejected {"ref":122,…}`; the nested search is now `396 Candidate given_Show_List › 397/398 Adapt › 399 ImplicitSearch Show[Int] › 453 Scope {…} › 454 Candidate given_Show_Int`. Step 4 inherits that. Step 5: seq 363 is a Rejected event; the Report is at 582 (the Reports chip lands there). Step 6: seq 414 is Rejected; pickLocal's search is at 632–634. The closing note "rejected candidates are not recorded" is now false (recorded, shown as raw JSON, M4).
- Tour B (ForDesugar): all steps hold. Step 8 prints `closure(acc | <empty>.this.sum$$anonfun$1:` (with prefix and ascription), not the literal text.
- Tour C (Mixins): all steps hold; step 1 prints `class Service() extends Object(), Timestamped, Counting`, not the source form; "no bytecode" in the closing note is stale (Mixins has 6 classes of bytecode).

## Checked and fine

All 14 deployed recordings load in under a second with no console errors (the one `ERR_BLOCKED_BY_CLIENT` is a blocked beacon); multi-select merge (Givens+Mixins, and two picked local files) with the "merged by phase; not a joint compile" chip; stale-hash redirect; copy link; deep links with `node=`, `seq=`, `ir=1&cls=`, `stack=1`; view keys 0/1/2/3, `x`, ⇧P, `r`; `[ ] b e p n`; band click, zoom phase and Esc; phases table sort and row click; Alt-click (URL follows); breadcrumb; lineage hover and derived-from steps; event filters (kind, text, problems only), Reports chip, defined tab; tree → bytecode range linking in split view; CFG lightbox `[ ]`, wheel zoom, drag pan, block click → listing highlight, minimap, Esc; exception edges on Mixins-opt Service.summary$lzyINIT1 (dashed orange); stack overlay wheel at both limits (no page scroll behind), PgUp/PgDn/Home/End, trace through bytecode, gutter range, class selector, Esc order range → trace → close → clear selection; keyboard focus returns to the page after clicking inputs and panels. Not tested: `prefers-reduced-motion` (cannot emulate here; note the "animate" box stays ticked while the code disables animation).
