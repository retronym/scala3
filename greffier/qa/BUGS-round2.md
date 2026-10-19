# Greffier QA round 2

Re-test of every `BUGS.md` entry on the deployed site (https://greffier.zaugg.id.au/, 2026-10-20, built-in browser at 1440×900 plus 1024, 768, 680 and 375 widths), a regression hunt in the areas the UI session changed (outcomes in `ui/REPORT.md` § "QA round 1"), and a literal run of the updated TOURS.md. `BUGS.md` stays as the round-1 record. Screenshots are in `qa/shots/r2-*`. LLM-assisted (Claude) testing and write-up.

**Round 1 re-test:** 25 verified · 4 partly verified · 2 still broken (deferred) · 1 regressed differently · 1 not re-tested (m7 is local-only) · 2 "not a bug" (agree).

**New bugs this round: 0 blocker · 2 major · 8 minor · 0 cosmetic.**

`D` = `https://greffier.zaugg.id.au/`. Unhashed `data/<name>.jsonl` links now redirect, so the links below use them.

## Round-1 entries

| # | Status | Notes |
|---|---|---|
| M1 unhashed links 404 | VERIFIED | `D/data/Givens.jsonl` → 302 to `Givens.27d3ea29.jsonl`; the address bar keeps the unhashed name. Bad names get the new banner. |
| M2 chapters land on no-op phase | VERIFIED | Closures-opt from parser, → ×5: typer, expandSAMs, patternMatcher, erasure, constructors, each with its diff; the pager's second line shows the range ("typer … pickleQuotes"). |
| M3 i/I and ★ | VERIFIED (by redefinition) | `i` is still the same as →, but `?` now says so ("same as → ←"), and ★ marks the same stops: typer, firstTransform, erasure, mixin, constructors, lambdaLift, moveStatic, genBCode, classfile in Givens. parser and pickleQuotes are no longer starred. I agree with making them consistent. |
| M4 raw JSON events | REGRESSED DIFFERENTLY | Now readable, but the names are wrong (new bug N1). Tentative is correct. |
| M5 graph labels `*` | VERIFIED | Labels are Predef, Object, Int, Conversion, runtime…; the graph is larger. They are still small at 1440 px. |
| M6 drop leaves old src | VERIFIED | After a drop the address bar is `D` with no hash; copy link refuses; a link without `src=` opens the panel instead of the fixture. The messages only show in the 11 px footer (N7). |
| M7 panel load carries pname | VERIFIED | jvm-fixture at genBCode → panel Load Closures opens at parser, seq 0, chapter 1. |
| m1 root "removed" | VERIFIED | No "removed" chip or list in any of Closures-opt's 84 tree phases, nor in Givens. I can't tell whether genuine removals would still be listed: the bundled recordings seem to have none. |
| m2 show together header | PARTLY | Header, removed list and pager are hidden while panes show. Dependencies now shows both ticked units ("ForDesugar.scala, Mixins.sc…"), which reads fine. The multi selection is still not in the URL (`unit=Patmat.scala`). |
| m3 deps-row banner | STILL BROKEN (deferred) | `D#src=data/Givens.jsonl&pname=posttyper`, click the Conversion row: "typer in progress (seq 330); showing posttyper output". I disagree with the deferral reason: the cursor already moves to the edge's first seq, so dropping the `pname` pin, as the Reports chip does, fixes the impossible banner. Landing exactly on the edge's event can come later. |
| m4 classfile counts as change | PARTLY | Methods with no optimiser change no longer show "(1 change)". The per-class "1 rewritten" chip at classfile, and the trace's "reprinted jvm:classfile // access flags 0x1000001", remain (deferred; agree, cosmetic). |
| m5 method ◀/▶ | VERIFIED | Cursor and band label follow (seq 796 … 779); steps run 1/13 … 13/13 like the lightbox; ◀ is disabled at genBCode; `method=` is in the URL and reloads. |
| m6 elided overlaps | VERIFIED | "elided in jvm:r1:pushPop (4)": demo 3–4, demo 12–15, thunked 3–4, thunked 15–16. |
| m7 switch case table | not re-tested | util is still not deployed, so this only exists locally. |
| m8 bytecode beside tree | VERIFIED | Ticking and unticking at lambdaLift (real clicks) keeps lambdaLift; the split shows. |
| m9 graph below fold | VERIFIED | Expanding scrolls the panel to the graph; its bottom is still cut by ~80 px at 900 px height. |
| m10 scope/hops | VERIFIED | Disabled, title "only one unit in this recording". |
| m11 narrow widths | PARTLY + REGRESSED | At 1024×768 the tree now gets ~13 lines. The new stacked layout below ~790 px loses the tree entirely (N2). |
| m12 stale tooltip | VERIFIED | Hover, then →: tooltip gone. |
| m13 counts disagree | NOT A BUG: agree, with a nit | Header chip tooltips now explain "structural" and "rebuilt". The phase select's "(242 new)" vs the table's "240" is still unexplained in the select itself. |
| m14 history | VERIFIED | Back: firstTransform → typer → parser → previous recording (Closures-opt lambdaLift); Forward works. |
| m15 pasted link keeps node | VERIFIED | |
| m16 fixture row disappears | VERIFIED | |
| m17 local paths / util | PARTLY | No `/Users/…` paths in `recordings.json`. util/util-opt are still not deployed (404). |
| m18 `_` members | VERIFIED | |
| m19 lightbox ← → | VERIFIED | ← → turn pages (pushPop ↔ inliner) with no block focused. |
| m20 swipe asymmetry | NOT A BUG: agree | Documented in `?` ("hold ⇧ to turn pages"). |
| m21 tour A stale | VERIFIED as updated, but the new text has errors | See N10. |
| c1 grammar | VERIFIED | "(1 class)", "1 dependency". |
| c2 hashed header name | VERIFIED | Shows "Givens.jsonl" (from the panel: "Mixins -opt -opt-inline:<sources>"). |
| c3 help | VERIFIED | `?` lists Deps, Alt-click, swipe, `g`/graph keys, stack range, method panel. |
| c4 stack overprint | STILL BROKEN (deferred; agree, cosmetic) | Unchanged: "born via De…genBCode" overprint, sliver labels on headers (`r2-stack-range-link-wrong-version.jpg` shows it too). |
| c5 duplicate key list | VERIFIED | |
| c6 aria labels | VERIFIED | "select recording Closures", "load Closures variant -opt …". |
| c7 CFG fit | VERIFIED | demo opens with readable blocks; minimap present. |
| Confusions | 1, 3, 4, 5, 6, 8, 12, 13, 14, 15 verified | A variant chip now loads that variant. Deferred 2, 7, 9, 10, 11: fine as deferrals. |

## New bugs

### N1. Rejected and Scope events show the wrong symbol names
- Severity: major (it states false facts about implicit resolution) · local: yes (same code)
- Repro: `D#src=data/Givens.jsonl&pname=typer&seq=338`, look at the event stack; also seq 333, 334, 453, 634.
- Expected: `Rejected` names the candidate from `syms[0]`, or the type behind `payload.ref`; `Scope` names the eligible refs from the **type** table. `ref`/`eligible` are type ids, the same as `Candidate.ref`.
- Actual: both are looked up as **symbol** ids, which gives unrelated Predef members:

  | seq | shown | recorded (syms / type of ref) |
  |---|---|---|
  | 333 | ✗ rejected wrapUnitArray | given_Conversion_Int_String |
  | 334 | ✗ rejected byte2Byte | Byte2byte |
  | 335 | ✗ rejected Character2char | StringFormat |
  | 338 | ✗ rejected wrapDoubleArray | Integer2int |
  | 395 | Scope … eligible a | Show.given_Show_List |
  | 453 | Scope … eligible apply | Show.given_Show_Int.type |
  | 633 | Scope contextual eligible LazyVals | given_Show_Int.type (the local given) |

  Tour A step 3 has copied the wrong name ("byte2Byte").
- Shots: `shots/r2-rejected-wrong-name.jpg`, `shots/r2-w1024-and-rejected-names.jpg` (the "used here" list under 327 shows a column of wrong names)

### N2. Below ~790 px the stacked layout has no phase scrubber
- Severity: major (the central view disappears at tablet-portrait widths) · regression from the m11 fix
- Repro: `D#src=data/Givens.jsonl&pname=typer&node=386` at 680×900 (or 375×812). 768×1024 is still fine.
- Expected: the stacked layout shows the tree first.
- Actual: `#scrub` is 2 px wide: the page shows the header, event inspector and dependencies only. The header row also overflows: "zoom phase" sits outside the page background.
- Shot: `shots/r2-narrow-no-scrubber.jpg`

### N3. Opening the CFG at a phase where the method didn't change moves the whole viewer
- Severity: minor
- Repro: `D#src=data/Closures-opt.jsonl&pname=jvm:classfile&ir=1&cls=Closures$&method=demo()I`, click ▣ graph; then Esc. Also open the link `D#src=data/Closures-opt.jsonl&pname=jvm:classfile&ir=1&cls=Closures$&cfg=demo()I`.
- Expected: the viewer stays at jvm:classfile and the graph shows the method's state there (or says "unchanged since lineNumbers").
- Actual: the viewer, address bar and cursor jump to jvm:lineNumbers (seq 797) and stay there after Esc. A shared `cfg=` link with `pname=jvm:classfile` is rewritten to `pname=jvm:lineNumbers`; jvm:r1:nullness becomes jvm:inliner. On Mixins-opt summary$lzyINIT1, classfile becomes jvm:r2:dce.

### N4. A Stack link with `range=` reopens at a different version
- Severity: minor
- Repro: open `D#src=data/Closures-opt.jsonl&pname=staging&seq=366&node=172&stack=1&range=staging:5-13` (it is the address bar after a gutter drag on 12/103 staging) and reload.
- Expected: front card 12/103 staging with the range.
- Actual: the front card is 7/103 desugarSpecializedTraits, and the URL is rewritten to `pname=desugarSpecializedTraits&seq=356`; the range panel is restored. Reproducible on every reload. Without `range=` the same link lands correctly on 12/103.
- Shot: `shots/r2-stack-range-link-wrong-version.jpg`

### N5. "problems only" is flooded by routine rejections
- Severity: minor (consequence of the M4 fix)
- Repro: `D#src=data/Givens.jsonl&pname=typer`, tick "problems only".
- Expected: the one feature-warning Report (plus failed searches).
- Actual: 209 rows: 208 `Rejected … not eligible` and the Report. An error-free file looks like it has 208 problems. Suggest counting only Rejected events whose reason is not `notEligible` (ambiguity, divergence, type mismatch of an eligible candidate), or adding a separate "rejections" filter.

### N6. The recordings panel stays open after a pasted link loads behind it
- Severity: minor
- Repro: open bare `D` (the panel opens), then paste `D#src=data/Givens.jsonl&pname=typer&seq=338` into the address bar (or any `src=` link).
- Expected: the panel closes once the linked recording loads, as a panel Load does.
- Actual: the recording loads, but the "about / recordings" modal stays over it until Esc.
- Shot: `shots/r2-panel-stays-open.jpg`

### N7. Link-related messages only appear in the 11 px footer
- Severity: minor
- Repro: after a drop, click view options → copy link; or open `D#pname=typer` (no `src=`).
- Expected: a visible message; the red banner already exists for load failures.
- Actual: "a recording loaded from local files cannot be linked" flashes for 2.5 s in the footer's perf slot; "this link does not name a recording (no src=)" is footer text under the open panel. "link copied" uses the same slot, so a successful copy is also easy to miss.

### N8. After a failed load the old recording stays shown under a URL that names the failed file
- Severity: minor
- Repro: with Givens loaded, paste `D#src=data/nope.jsonl`.
- Actual: banner "Could not load the recording … HTTP 404", the panel opens, but Givens is still rendered and the header still says "Givens.jsonl" while the address bar says `nope.jsonl`; reload shows only the error. Expected: either restore the previous hash, or blank the viewer. Merged links with one bad file (`src=data/Givens.jsonl,data/nope.jsonl`) behave the same. Non-JSONL sources (`src=recordings.json`, `src=index.html`) get the clear "parsed but contains no recording data" message.
- Shot: `shots/r2-load-error-banner.jpg`

### N9. Unknown `pname=` falls back to parser without a word
- Severity: minor
- Repro: `D#src=data/Givens.jsonl&pname=nosuchphase` → parser, seq 0; the URL is rewritten and there is no banner. A typo or renamed phase in a shared link silently shows something else.

### N10. TOURS.md has new errors
- Severity: minor (docs). Tours B and C pass apart from what's listed; Tour A steps 1, 2, 5, 7 pass.
- Tour A step 3: says "453 Scope implicitScope eligible given_Show_Int"; the page shows "eligible apply" (N1). It also quotes "✗ rejected byte2Byte", which is the wrong name (recorded: Byte2byte).
- Tour A step 4: "press → a few times to watch the events that follow the search": → now turns chapter pages and leaves typer (453 → firstTransform seq 711 → erasure → mixin). It should say ⇧→ (or `n`).
- Tour A step 6 and the closing paragraph: "the Rejected events below the search name the givens that were eligible but lost". There are none. pickLocal's search is 632 ImplicitSearch → 633 Scope contextual, level 4 → 634 Candidate (local given) accepted. `Show.given_Show_Int` is never considered, because the contextual scope succeeds first. The page currently shows 633 as "eligible LazyVals" (N1). The honest answer to "why does the local given win" is "found in the contextual scope (level 4), so the implicit scope is never searched". The tour could say exactly that, pointing at 633.
- Tour C step 7: `pname=genBCode&ir=1&cls=Service` on **Mixins.jsonl** (base): "the method panel steps the optimiser" — the base recording has no optimiser; the lightbox says "1/1 changes". Use `Mixins-opt.jsonl` for this step (there summary$lzyINIT1 has 5 steps and the exception edge).
- Tour B step 3 / step 5: fine; step 5's text `ArrayOps.foreach$extension(ev$1)(…)` prints as `scala.collection.ArrayOps.foreach$extension[Unit, Int](ev$1)(…)` (nit).

## Regression hunt: checked and fine

Chapter paging in the main view and in the Stack (`i` stops: typer, expandSAMs, patternMatcher, erasure, constructors, lambdaLift, flatten); ★ in the phases table matches the stops; history push and back/forward across phases and recordings; load-state reset between recordings (panel Load and variant chips); the load-error banner for 404, non-JSONL and merged-with-a-bad-file; Tentative rendering; Rejected chip colour; dependency graph labels; method panel ◀/▶ with `method=` links (cold reload keeps classfile); `cfg=` links on a change phase (cold reload opens the lightbox, exception edge on Mixins-opt summary$lzyINIT1); `stack=1&node=` cold link (trace restored); CFG stepping demo 1 → 13 with `[`/`]` (blocks 1 → 4 → 2 → 1, "dead" tags, no console errors; the graph fades in over ~0.5 s); removed-list dedup; `?` contents. Console: only the expected 404s and parse errors from my deliberate bad links, plus the blocked beacon.
