# Replay viewer: user evaluation

Evaluator: an LLM session (Claude) acting as a curious Scala developer, using the built-in browser pane of the Claude desktop app against `http://localhost:8766/ui-eval/index.html`. Written with LLM assistance; nothing in the viewer was changed.

Setup and caveats:
- The browser pane was 1024×768 CSS px (screenshots are scaled to 800×600). Several layout complaints below are partly caused by that size, but 1024×768 is a realistic laptop split-screen.
- Where a panel was too small to read I extracted its visible text (`document.body.innerText`) instead of scrolling; that reads only what the UI renders. For the phase-by-phase answers I drove the phase dropdown and pressed `e` in a loop rather than clicking 18 times per file; that is the same sequence a user would perform.
- Where I checked an answer against the raw JSONL, it is marked **(data, not UI)**. Those checks were done to tell UI gaps from recorder gaps.
- The static server on 8766 died once during the session ("cannot fetch data/util.jsonl"); I restarted it.

Screenshots: `eval-shots/` (paths below are relative to `ui-eval/`).

## Part 1: questions

### Q1. Givens: which given is chosen for `List(1,2,3).shown`, what was considered, does `pickLocal` pick the local given?

**Answer.** `Show.given_Show_List[Int](using Show.given_Show_Int)`. The inspector shows `323 Adapt Apply#391` → `324 ImplicitSearch pt Show[List[Int]] success → given_Show_List` → `330 Candidate ✓ accepted given_Show_List`, and inside it (via two nested `Adapt`s, seq 331–332) `333 ImplicitSearch pt Show[Int]` → `334 Candidate ✓ given_Show_Int`. During the outer search the compiler also completed `given_Conversion_Int_String` (`325 Complete … forced by <empty>`): the only hint of what else was in scope. `pickLocal`: `413 ImplicitSearch pt Show[Int] success → given_Show_Int`, `414 Candidate ✓ accepted given_Show_Int given_Show_Int.type`. It is the local one, but that is visible only indirectly: the event stack above it is `DefDef pickLocal › Block › Apply › Adapt`, and the candidate's type is printed `given_Show_Int.type` without the `Show.` prefix that the global one carries.

**What was considered:** cannot be answered. Every search in the file has exactly one Candidate and it is the accepted one (data, not UI: 5 searches, 5 candidates, all `accepted:true`). In util.jsonl there are 3 rejected candidates out of 445. So the recorder emits (nearly) only winners.

**Clicks.** Load URL → `]` → `e` (to see the typer tree) → scroll the scrubber → click inside `shown[List[Int]](…)`. Clicking selected the inner `Ident given_Show_List` (#386), and the inspector listed that symbol's *definition* events (`231 Complete given_Show_List forced by Show$`, the TypeDef/ValDef typing), not the use-site search (`eval-shots/q1-select-given-ident-shows-definition-events.jpg`). Hover showed the enclosing `Apply #394`, but clicking at the same spot kept #386 selected. I fell back to a deep link `#…&seq=463&node=394`; that showed the right events but the scrubber displayed the *parser* tree with "#394 is not in this version" (`eval-shots/q1-deeplink-node-shows-parser.jpg`). The `Adapt` row was collapsed; it took two clicks on the triangle (the first toggled it open, a stray second click closed it) and the inspector pane is about 150 px tall, so I scrolled inside it repeatedly (`eval-shots/q1-render-implicitsearch.png`). For `pickLocal` the fastest route was `#…&seq=414` (`eval-shots/q1-picklocal-candidate.jpg`).

**Stuck / expected.**
- Expected to click a use site (the `using` argument) and see "chosen by ImplicitSearch #324" directly. A selected Ident of a given should offer both "where defined" and "where chosen".
- Expected a "parent node" affordance (e.g. Alt-click or a breadcrumb) to widen the selection from `Ident` to the enclosing `Apply`.
- Expected rejected/shadowed candidates with reasons. None recorded.
- Expected the candidate label to show the owner (`Givens.pickLocal.given_Show_Int` vs `Show.given_Show_Int`). The sym's `fullName` is `Givens.given_Show_Int` (data), which drops `pickLocal` and is misleading anyway.

### Q2. Givens: where does `Conversion[Int,String]` get inserted in `viaConversion`, and when does the call become explicit?

**Answer.** It is inserted by typer during adaptation: `357 Adapt Apply#415 pt String` → `358 ImplicitSearch` → `359 Candidate ✓ given_Conversion_Int_String`, followed by `363 Report warning "Implicit conversion to type String is not to an into[...] type …"` (`eval-shots/q2-conversion-report-at-typer.jpg`; the cursor-event node `i` is outlined in the source). The call is explicit in the first typed tree: `given_Conversion_Int_String.apply(i):String + "!"`. It is unchanged until erasure, which gives `given_Conversion_Int_String().apply(Int.box(i)).asInstanceOf[String]:String + "!"` (accessor call, boxing, cast). The `+` string concatenation is still a `+` at the last recorded phase (selectStatic); its lowering happens in the backend, which is not recorded.

**Clicks.** About 4 with a seq deep link; by dropdown, 1 dropdown + `e` per phase × 18 phases to confirm "unchanged until erasure".

**Stuck.** Picking "erasure" in the phase dropdown shows the **getters** tree with a banner "erasure in progress … showing getters output … jump to end of phase (e)". The selector moves the cursor to `PhaseBegin`, so every phase pick shows the previous phase's output until you press `e`. I only understood this after reading the banner twice. Expected: picking a phase shows that phase's result. The Report row is truncated at the panel edge; the full message is only in the DOM.

### Q3. ForDesugar: what does the for-comprehension become after typer, and when does `s"…"` become StringContext / concatenation?

**Answer.** After typer: `xs.withFilter((x: Int) => x % 2 == 0).flatMap[(Int, String)]((x: Int) => ys.map[(Int, String)]((y: String) => Tuple2.apply[Int, String](x, y)))`; `for x <- xs do acc += x` becomes `intArrayOps(xs).foreach[Unit]((x: Int) => acc = acc + x)`. The interpolator is already `StringContext.apply([…]*).s([name, n + 1 : Any]*)` after typer; elimRepeated drops the `*`; the **interpolators** version turns it into `"hello " + name + ", you are " + (n + 1) + " years old"`; erasure adds `Int.box(n + 1)`. Further lowering seen while scrubbing: vcInlineMethods rewrites `intArrayOps(xs).foreach` to `ArrayOps.foreach$extension(ev$1)(…)`; erasure splits each lambda into a local `def $anonfun` plus an adapting closure; the version labelled **mixin** turns `var acc` into `IntRef` (this is `capturedVars`, see Part 2); lambdaLift lifts the bodies to `private static def pairs$$anonfun$1` etc. and leaves `closure(acc | sum$$anonfun$1)`.

**Clicks.** Phase dropdown + `e` per phase; reading needed scrolling in a scrubber that wraps long lines at ~45 columns.

**Stuck / expected.**
- The parser view prints the interpolation as `s"hello {name}` with the `$` missing and the string broken over 8 lines (`eval-shots/01-givens-initial-parser.jpg` shows the same printer artefact for `s"\"$a\""` as `s"\\\"{a}\\\""`). A learner would think the source was mangled.
- Lineage on the new `+` node says `split heuristic @interpolators` from the literal `", you are "`, not from the `StringContext.s(...)` call it replaced (`eval-shots/q3-interpolators-concat-lineage-heuristic.jpg`). Expected the "derived from" chain to point at the `s(...)` Apply.
- For desugaring itself there is nothing to see: the for-expression goes from parser text to typed text in one step. Expected an intermediate "desugared untyped tree" version, or at least a derive record that says "desugar".

### Q4. Closures: what happens to `counter`'s `var n`, where does the lambda in `applyTwice` end up, which phase decides?

**Answer.** `var n: Int = start` survives to erasure; the version labelled **mixin** (really `capturedVars`, fused into the same group) makes it `val n: scala.runtime.IntRef = IntRef.create(start)` with `n.elem = n.elem + 1`; **lambdaLift** turns the lambda into `private final <static> def counter$$anonfun$1(n$1: IntRef): Int` and leaves `closure(n | <empty>.this.counter$$anonfun$1)` at the use site (`eval-shots/q4-closure-lineage-lambdalift.jpg`; hover lineage: `rewrite @lambdaLift ← #505 retype @erasure ← #143 split @typer ← "() => { n += 1 n }"`).

`applyTwice` has no lambda; it receives `f`. What the tool does show: `f(f(x))` → typer `f.apply(f.apply(x))` → **specializeFunctions** `f.apply$mcII$sp(f.apply$mcII$sp(x))`. For lambdas generally: they stay `Closure` trees through every recorded phase; whether a `Closure` becomes an `invokedynamic` LambdaMetafactory call is decided in genBCode, which is not recorded. The one anonymous-class case in the file is `partial`: **expandSAMs** turns the `PartialFunction` literal into `final class $anon extends AbstractPartialFunction[Int, String]` with `isDefinedAt`/`applyOrElse`.

**Stuck / expected.** Expected a backend step (or at least a per-Closure "emitted as indy to LambdaMetafactory / as class X" record). In lineage tooltips intermediate nodes show only "(not in the previous version)" with no text and no link to jump to the phase where they existed.

### Q5. Patmat: pattern matcher output for `area`; when does the `Dot` case compare by reference; what does the `(a, b)` case become?

**Answer.** patternMatcher output (`eval-shots/q5-patmat-area.jpg`):
```
matchResult9[Double]: {
  case val x11: (s : Shape) = s
  if x11.$isInstanceOf[Circle] then { … Circle.unapply(x19) … x20._1 …
      if r > 0 then return[matchResult9] {π * r * r} else ()
      return[matchResult9] 0.0d }          // Circle(_) folded into the Circle branch
  if x11.$isInstanceOf[Rect] then { … return[matchResult9] {w * h} }
  return[matchResult9] 0.0d                // Dot
}
```
The `Dot` case never compares by reference in any recorded phase: because `Shape` is sealed and the other cases are covered, the last case is emitted as an unconditional `return`, with no `eq Dot` test and no `MatchError` fallback. Erasure only changes `$isInstanceOf` to `isInstanceOf` and `_1` to `_1()`. The `(a, b)` case is in `describe`, not `area`: typer gives `case Tuple2.unapply[Any, Any](a @ _, b @ _)`; patternMatcher gives `if x22.$isInstanceOf[(Any, Any)] then { val x26 = x22.$asInstanceOf[(Any, Any)]; val a = x26._1; val b = x26._2; … }` (no `unapply` call); interpolators turns its body into `"pair " + a + " " + b`; erasure gives `isInstanceOf[Tuple2]` and `_1()`/`_2()`.

**Clicks.** 2 (dropdown, `e`) plus scrolling. This was the question the tool answered best.

**Stuck / expected.** I could not confirm through the UI that the final `return 0.0d` *is* the `Dot` case: clicking it gave no tooltip, and nothing ties a generated branch to the source `case` it came from. Expected source-case highlighting when hovering a branch.

### Q6. Mixins: when does `Service` get its `log` implementations, what does `super.log` resolve to, when does `lazy val summary` become a holder/field?

**Answer.**
- **resolveSuper** (its own version in this file) adds to the traits abstract super accessors `Timestamped$$super$log` / `Counting$$super$log` and to `Service` their implementations: `Timestamped$$super$log = super[Logger].log(msg)` and `Counting$$super$log = super[Timestamped].log(msg)`. So `super.log` in Timestamped resolves to Logger's, and in Counting to Timestamped's, following `Service`'s linearization.
- The version labelled **mixin** adds `override def log(msg) = super[Counting].log(msg)` to `Service`, the field `private var Counting$$count` with accessors, `Logger$_setter_$prefix_$eq`, the `$init()` calls, and the lazy-val machinery: `@volatile private lazy var summary$lzy1: Object`, a `@static VarHandle summary$lzy1$lzyHandle`, `summary()` and `summary$lzyINIT1()` (CAS loop with `LazyVals.Evaluating/Waiting/NullValue`) (`eval-shots/q6-mixin-service-lazyval.jpg`). The trait keeps `lazy def summary(): String = "logged " + …`, which the class forwards to via `super[Counting].summary()`.
- **constructors** moves field initialisation into `Service.<init>`: `super(); super[Logger].$init(); super[Counting].$init(); this.prefix = "[svc]"; releaseFence()`.

**Stuck.** mixin, lazyVals, memoize, nonLocalReturns and capturedVars share one recorded version (data: they have the same `group` 164), labelled "mixin". The UI cannot tell which of them made the lazy-val holder, and a learner would conclude "mixin does lazy vals". There is no indication anywhere that a version covers several miniphases.

### Q7. util.jsonl: Signatures.scala: implicit dependencies, what forced most completions, longest completion chain

**Answer (graph, level=file, cursor at end).** Implicit edges from `Signatures.scala`: `Symbols$` (weight 32), `Predef$` 11, `SourceFile.scala` 8, `Flags$` 7, `Int$` 4, `Ordering$` 3, `Types$` 2, `tpd$` 1. Of these only `SourceFile.scala` is a file in this recording; the rest are library or compiler classes on the classpath, which stay as class nodes at file level. Force edges: `Spans.scala` 4, `SourcePosition.scala` 1, `SourceFile.scala` 1.

I got this list by reading edge `<title>`s, not by hovering: with 240 nodes the labels are hidden, the graph pane is about 390×200 px, and there is no list of a node's neighbours. Raising "min weight" to 20 made the graph legible but removed all implicit edges except `Symbols$`. Clicking the `Signatures.scala` node moved the cursor to its first event (`22258 Typed PackageDef`), which **rewound the graph** to 482/968 edges, and the inspector said "Nothing selected" (`eval-shots/q7-graph-file-node-click.jpg`).

**What forced the most completions:** not answerable in the UI (no aggregation of `Complete.forcedBy`). Data, not UI: 525 Complete events in the unit; top `forcedBy` = the package `dotty.tools.dotc.util` (22), `Signatures.applyCallInfo` (21), `Signatures.MethodParam` (18), `Signatures.Signature` and `toApplySignature` (17 each).

**Longest completion chain:** not searchable in the UI. Data, not UI: depth 6 at seq 28183, `name ← $anonfun ← $anonfun ← argsWithOriginalIndices ← $anonfun ← finalParams` (all inside `toParams`); globally depth 7 in ReadOnlySet.scala. Once you know the seq, the inspector does show the whole chain as the event stack for `#…&seq=28183`, which is good, but the scrubber stayed on **Attachment.scala** (the unit selector did not follow the cursor on a deep link) and the inspector rows were pushed off the right edge by indentation (`eval-shots/q7-deeplink-chain-wrong-unit.jpg`). Arrow keys did nothing after I clicked inside the inspector.

**Expected.** A sym/file search box; "neighbours of X" as a sorted table (kind, weight, first seq, click to jump); a "why did this complete?" query that walks `forcedBy`; a top-N table of completion forcers per unit.

### Q8. Which phase adds most new nodes; can you tell a real rewrite from copy noise?

**Answer.** Per-version headers give "N new (pure copies hidden) since <prev>". Totals (data, `version.new`): erasure is first in Givens, Closures, Patmat and util; typer narrowly beats erasure in ForDesugar (106 vs 105); mixin is first in Mixins (250). In util.jsonl: erasure 26,214, parser 21,477, typer 21,157, then firstTransform/patternMatcher/mixin about 6.4–6.8k each.

**Real rewrite vs noise from the UI alone: only partly.** In the erasure view every retyped `TypeTree` (`String`, `Int`, `IterableOnce`) is highlighted in the same green as the genuinely new `def $anonfun` (`eval-shots/q8-erasure-retype-vs-rewrite-same-colour.jpg`). Hover distinguishes them, but the labels are noisy: the erasure return type `IterableOnce` reports `split heuristic @erasure ← copy heuristic @posttyper ← synth @typer`, not "retype". Givens' firstTransform header says "99 new … 63% shared" while the visible text diff is only removed self-types (`this: X.type =>`) and removed `>: Nothing <: Any` bounds: the parents were rebuilt because children were dropped. "collapse pure copies" helps with exact copies, but most noise is `retype` (15,650 in util) and `rewrite/heuristic` (10,076).

**Expected.** Three highlight styles: structural rewrite/synth, retype-only, rebuilt-parent; a "show only structural changes" filter; and a per-phase summary bar (new/retyped/elided) somewhere other than the version header.

### Q9. Find a `Report` in an error-free file

Closures, ForDesugar, Patmat and Mixins have **no** Report events; util.jsonl has none either (data). Givens.scala compiles without errors but has one warning, found in Q2 (`363 Report warning`, the `implicitConversions` feature warning). I found it only because I was inspecting `viaConversion`. The UI has no way to list events of a kind: no filter, no marker on the event axis, no count. Expected: a "Reports (1)" chip in the header that jumps to it, and Report ticks on the axis.

To see more diagnostics I would: (1) re-record with `-Wall -deprecation -feature -explain` so warnings exist at all; (2) want the recorder to also emit diagnostics that are buffered and discarded during tentative typing. util.jsonl shows such a case: seq 28171 in Signatures.scala types a `Select` to `<error value unapply is not a member of (Name, untpd.Tree) => untpd.NamedArg …>` in a compile that succeeds, with no Report and no marker saying the attempt was tentative and thrown away.

### Q10. My own questions

**(a) Why does a successful compile of Signatures.scala contain an `<error …>` type?** (seq 28171, found while reading the Q7 chain.) The inspector shows the typer trying `NamedArg.unapply` on a function-typed companion and getting an error type, then carrying on. The UI cannot answer whether this was a `tryEither`/exploring-reporter branch, what the alternative was, or which branch won: no "tentative" marker, no discarded-diagnostic record, no sibling relationship between the attempts.

**(b) What does firstTransform remove from Givens?** The node count drops from 303 to 264. Comparing the two versions by eye: self-types and trivial `>: Nothing <: Any` bounds disappear. The UI has no "removed nodes" view: removed nodes are simply absent from the new version, and elide records are reachable only from a surviving node's lineage. I had to copy both texts out and diff them.

## Part 2: UX and discoverability critique (most important first)

1. **Phase pick shows the previous phase.** Choosing "erasure" lands on erasure's `PhaseBegin` and shows the getters tree with an "in progress" banner; you must press `e`. Because of this every comparison needed two actions, and the `pname=` deep link puts the cursor at seq 0 ("parser in progress (seq 0); showing interpolators output"), after which pressing `e` jumps back to the parser. *Change:* phase pick = that phase's output (cursor at `PhaseEnd`); keep "start of phase" on a modifier or a separate button. Keep the in-progress banner for event scrubbing only.
2. **Deep links are one-way and partly broken.** Changing the hash does not reload (you need a full reload); navigating does not update the hash, so you cannot copy a link to what you see; `seq=` alone always shows the parser tree, even at a `PhaseEnd` (`seq=463` in Givens; `eval-shots/q1-deeplink-node-shows-parser.jpg`); `seq=` does not switch the unit (Q7). `seq=…&pname=…` together works. *Change:* listen to `hashchange`; write `src,unit,pname,seq,node,sym,level` back with `history.replaceState`; derive the displayed version from `seq` on load.
3. **First run.** No params loads `fixture.jsonl`, the synthetic v0 fixture, under the heading "Replayable compiler · v0 viewer" (tab title "Track C UI") (`eval-shots/00-first-run-no-params.jpg`). Nothing says it is synthetic or that real recordings exist; `#src=` is not discoverable from the page. "Choose files" accepts several files (merged), but the label says "load v0 JSONL" though v1 is what you want. *Change:* an empty-state panel listing the recordings in `data/` (or a text field for a URL), with "what is this" in two sentences and the keyboard list; rename to the v1 viewer; mark the fixture as synthetic.
4. **Selecting the thing you mean.** Clicks land on the innermost span; hover can show a different node from the one a click selects (Q1). Selecting a given's Ident shows its definition events, not the search that chose it. *Change:* a breadcrumb of ancestors (click to widen), `Alt`+click for parent, and for a symbol reference two tabs "defined" / "used here (search, adapt)".
5. **Layout at laptop size.** The inspector is about 150 px tall and the graph about 390×200 px at 1024×768; the scrubber wraps code at about 45 columns. Inspector rows are pushed off the right edge by indentation in deep trees (util). Report messages are truncated. *Change:* resizable split panes or tabs (Tree | Events | Graph), wrap or tooltip long rows, cap indentation, and a full-height mode per view.
6. **Miniphase grouping is invisible.** mixin/lazyVals/memoize/nonLocalReturns/capturedVars produce one version labelled "mixin"; band tooltips say only "mixin · seq 473–474 · 0 events". A learner will attribute IntRef boxing and lazy-val holders to mixin. *Change:* label the version "mixin + lazyVals + memoize + nonLocalReturns + capturedVars" (the `megaOf` data is in the file), and push the recorder for per-miniphase versions (below).
7. **Axes and labels.** "event axis" vs "phase axis" vs "phases" band is three controls for one timeline; "1/0 events in" (seen in ForDesugar) reads as nonsense; with Retyped hidden, transform phases have 0–1 visible events so the bands for 80 phases share a few pixels. "bands by time" is on by default but there are no timestamps. *Change:* a single timeline with phase bands and a separate list of phase versions; hide event counts that are zero; default "bands by time" off when the file has no timestamps.
8. **Printed text readability.** Parser view printer artefacts (`s"hello {name}` split over lines, `s"\\\"{a}\\\""`), `<?>` prototypes, fully-qualified types in util (`dotty.tools.dotc.ast.Trees.Tree[dotty.tools.dotc.ast.Trees.Untyped]`), `classOf[Object with Timestamped with Counting {...}]`. Tooltips repeat the whole method type. *Change:* show the source text for the parser version (it is in `meta.units[].text`); short names with full name on hover; elide `<?>`.
9. **Lineage.** Useful when it works (Q4 chain). But intermediate nodes show "(not in the previous version)" with no text and no link; heuristic splits misattribute (Q3, Q8); `retype` is not distinguished visually. *Change:* make each lineage step a link that jumps to that phase with that node selected, and show the old node's text from the version it lived in, not only the previous one.
10. **Graph.** What you can learn: overall shape, that one file talks to many library classes, when edges appear during typer. What you can't: anything about a specific node at real scale. Labels vanish above 120 nodes, there's no search, no neighbour list, edges are only inspectable by hover, clicking a node rewinds the cursor (and the graph) to its first event, and the inspector shows nothing for a file node. File level still shows every classpath class as its own node (240 nodes for util). The layout jumped between screenshots. *Change:* search box; a side table of neighbours by kind/weight; "focus on node" (ego graph, 1–2 hops); group classpath symbols by package at file level; click selects without moving the cursor (cursor jump on double-click).
11. **No event queries.** No way to list events by kind (Reports, failed ImplicitSearch, Complete with deepest stack). *Change:* a filter row in the inspector: kind, unit, sym text, result.
12. **Keyboard.** The key strip is visible and correct, but keys stop working after clicking inside some panels (arrows after clicking in the inspector in util). There is no help overlay beyond the strip (I did not find one). *Change:* global key handler unless a text input has focus; a `?` overlay.
13. **Accessibility.** Checkboxes and sliders have no accessible names (the accessibility tree lists them as `checkbox "on"`, `textbox "0" type=range`).
14. **Multi-file merge.** Works (`eval-shots/p2-merged-three-files.jpg`), but files are concatenated, so the timeline shows typer three times; this is not how a real joint compilation interleaves. Fine for a demo; label it.
15. **Performance felt.** Good. util.jsonl (72 MB) loads in about 0.55–0.6 s from localhost (header: "546 ms, index 146 ms"), JS heap 165–200 MB; switching phase + `e` for 27 versions took well under 300 ms each; graph layout 130–290 ms. The small corpus files load in 10–470 ms (header figures: Givens 10–20 ms, Mixins 26 ms, Closures 166 ms, Patmat 466 ms). No jank while scrolling the windowed scrubber.

**What I wanted and could not do:** two phases side by side with a diff; follow one source expression through all phases in one strip (a "filmstrip" of the selected node's descendants per phase); see what the tree looked like halfway through typer (the scrubber shows the parser output until typer's `PhaseEnd`, so stepping through typer events never changes the tree); bytecode for the final version.

### Recorder: top 5 additions that would have answered Part 1 better

1. **All implicit candidates**, eligible and rejected, with reason (not a subtype, ambiguous with X, shadowed, diverging, lower priority), plus the scope each came from (local, lexical, implicit scope of type). Q1 is the canonical teaching question and today only winners are recorded.
2. **Per-miniphase versions inside fused groups** (or per-node `phase` on every `derive`), so lazyVals vs mixin vs capturedVars are separable. Q3, Q4 and Q6 all hit this.
3. **Backend decisions**: per `Closure`, whether it was emitted as `invokedynamic` (LambdaMetafactory, with the SAM) or a class, and ideally a per-method bytecode listing for the final version. Q2 (string concat) and Q4 (indy) need it.
4. **Tentative typing and discarded diagnostics**: mark events inside `tryEither`/exploring contexts, record buffered Reports with a "discarded" flag and which alternative won. Q9 and Q10a.
5. **Better lineage taxonomy**: a `retype` kind distinct from structural rewrite (and from "parent rebuilt because a child changed"), attribution of generated pattern-match branches to the source `case`, and desugaring as its own step (an untyped desugared tree or derive records `how: "desugar"`). Q3, Q5, Q8.

(Also wanted, lower priority: `ts` on PhaseBegin/End so bands-by-time work; a correct owner chain in `sym.fullName` for local syms such as `Givens.pickLocal.given_Show_Int`; names other than `x$4` for anonymous using-parameters in Candidate payloads.)

## Part 3: guided tours

Base URL: `http://localhost:8766/ui-eval/index.html`. Every link must be opened with a full reload (hash changes alone do not reload). Use `seq` and `pname` together; `seq` alone shows the parser tree. Do not press `e` after loading a `pname` link unless the link also has `seq`.

### Tour A: implicit resolution (Givens.scala)

1. `#src=data/Givens.jsonl&skip=1&collapse=1` → read the parser tree: `List(1, 2, 3).shown`, `(i:String)`, the local `given` in `pickLocal`.
2. `#src=data/Givens.jsonl&skip=1&collapse=1&seq=463&pname=typer` → the typed tree: point at `shown[List[Int]](…)(using Show.given_Show_List[Int](using Show.given_Show_Int))` and `given_Conversion_Int_String.apply(i)`.
3. `#src=data/Givens.jsonl&skip=1&collapse=1&seq=334` → inspector: the stack `Adapt › ImplicitSearch Show[List[Int]] › Candidate given_Show_List › Adapt › Adapt › ImplicitSearch Show[Int] › Candidate given_Show_Int`. Explain the nested search for the `using sa: Show[A]` parameter. The scrubber shows parser output here ("typer in progress"): say so.
4. Press `→` a few times from step 3 to show events following the search.
5. `#src=data/Givens.jsonl&skip=1&collapse=1&seq=363` → the `Conversion` search (358–359) and the feature warning `Report` (363); `i` is outlined in the source.
6. `#src=data/Givens.jsonl&skip=1&collapse=1&seq=414` → `pickLocal`: the stack shows `DefDef pickLocal`, the candidate type `given_Show_Int.type` (no `Show.` prefix): the local given wins.
7. `#src=data/Givens.jsonl&skip=1&collapse=1&seq=948&pname=erasure` → `given_Conversion_Int_String().apply(Int.box(i)).asInstanceOf[String]`.

**Breaks:** cannot show *why* the local given beats `Show.given_Show_Int` (no rejected candidates); at steps 3–6 the tree pane shows the parser output, so the class sees events next to untyped code; the inspector pane is small enough that the teacher has to scroll for step 3.

### Tour B: for-comprehension desugaring and lowering (ForDesugar.scala)

1. `#src=data/ForDesugar.jsonl&skip=1&collapse=1&seq=1&pname=parser` → the `for` and `s"…"` as parsed (warn about the printer showing `{name}` without `$`).
2. `…&seq=203&pname=typer` → `withFilter/flatMap/map`, `intArrayOps(xs).foreach`, `StringContext.apply(...).s(...)`.
3. `…&seq=247&pname=elimRepeated` → varargs `*` gone.
4. `…&seq=281&pname=interpolators` → `"hello " + name + …`. Click the `+` node to show lineage (and note it is heuristic).
5. `…&seq=291&pname=vcInlineMethods` → `ArrayOps.foreach$extension(ev$1)(…)`.
6. `…&seq=456&pname=erasure` → `def $anonfun(x: Int)` plus `(x: Object) => $anonfun(Int.unbox(x))`, `Int.box(n + 1)`. Use this step to show retype noise (every type tree is green).
7. `…&seq=474&pname=mixin` → `var acc` becomes `IntRef`. Say explicitly that this is capturedVars, in the same recorded group as mixin.
8. `…&seq=478&pname=lambdaLift` → lifted `private static def pairs$$anonfun$1` etc., `closure(acc | sum$$anonfun$1)`.

(Replace `…` with `#src=data/ForDesugar.jsonl&skip=1&collapse=1`.)

**Breaks:** no intermediate desugared-but-untyped tree, so step 1→2 jumps; step 7 is mislabelled; the story ends at `closure(...)` because genBCode (indy) is not recorded.

### Tour C: class/trait lowering through mixin and erasure (Mixins.scala)

1. `#src=data/Mixins.jsonl&skip=1&collapse=1&seq=210&pname=typer` → traits with `super.log`, `lazy val summary`, `class Service extends Timestamped with Counting`.
2. `…&seq=304&pname=getters` → `val prefix` becomes `def prefix`, `lazy val` becomes `lazy def`.
3. `…&seq=314&pname=resolveSuper` → `Timestamped$$super$log`/`Counting$$super$log` in the traits and their implementations in `Service` (`super[Logger].log`, `super[Timestamped].log`): ask the class to predict them from the linearization first.
4. `…&seq=512&pname=erasure` → `prefix()`, `Int.box`, erased signatures.
5. `…&seq=530&pname=mixin` → `Service` gets `log` forwarder, `Counting$$count` field and accessors, `Logger$_setter_$prefix_$eq`, `$init` calls, and the lazy-val holder + VarHandle + `summary$lzyINIT1`.
6. `…&seq=532&pname=constructors` → `Service.<init>`: `super(); super[Logger].$init(); super[Counting].$init(); this.prefix = "[svc]"`.

(Replace `…` with `#src=data/Mixins.jsonl&skip=1&collapse=1`.)

**Breaks:** step 5 mixes mixin and lazyVals output under one label; the `Service` lazy-val code is about 60 lines and the scrubber wraps it at ~45 columns, so it needs a wide window; no bytecode, so the class cannot see that trait methods become interface default methods.
