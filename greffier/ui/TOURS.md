# Guided tours (updated after QA round 1)

Base: `https://greffier.zaugg.id.au/` (the deployed site; unhashed `data/<name>.jsonl` names redirect to the current file) or locally `http://localhost:8766/ui/index.html` (serve `local/replay/` with `python3 -m http.server 8766`). A `pname=` link now lands on that phase's **output** (cursor at its `PhaseEnd`), the address bar follows what you see (copy it any time), and pasting a link into an open page applies it without a reload. `seq=` alone shows the version visible at that seq (the previous phase's output while a phase is in progress; the banner says so); combine `pname=` and `seq=` to see a phase's tree next to an event in the middle of it. Press `?` for keys; `1`/`2`/`3`/`0` give the tree, events, graph or all views full height. Each link below was opened headless by `tours-check.mjs` and showed the named phase (all steps loaded).

Prefix for every link: `#src=data/<file>.jsonl&skip=1&collapse=1&` (a link without `src=` names no recording and opens the recordings panel). Pages: `→` / `←` turn pages (chapters; each lands on the chapter's dominant phase), `⇧→` small steps.

## Tour A: implicit resolution (`Givens.jsonl`)

1. `pname=parser`: the parsed source text (`List(1, 2, 3).shown`, `(i: String)`, the local `given` in `pickLocal`).
2. `pname=typer`: the typed tree: `shown[List[Int]](…)(using Show.given_Show_List[Int](using Show.given_Show_Int))` and `given_Conversion_Int_String.apply(i)`.
3. `pname=typer&seq=453`: the typed tree with the cursor inside the nested search. Event stack: `Candidate given_Show_List › Adapt › 399 ImplicitSearch Show[Int] › 453 Scope implicitScope eligible Show.given_Show_Int.type › 454 Candidate given_Show_Int`: the nested search for `using sa: Show[A]` (at `seq=333` there is a `✗ rejected given_Conversion_Int_String … not eligible` event: eligible-by-name candidates that do not fit the expected type are recorded as `Rejected`, 208 of them, shown with their name and type). Select the `given_Show_List` identifier in the tree: tab **used here** shows these search events, tab **defined** its completion. (Drop `pname=` from the link to see the explicit "typer in progress, showing parser output" banner.)
4. From step 3 press `⇧→` (or `n`) a few times to watch the events that follow the search (plain `→` turns a chapter page and leaves the typer).
5. `pname=typer&seq=582`: the `Conversion` search and the feature warning `Report` (582); `i` is outlined. The **Reports (1)** chip in the inspector header jumps here from anywhere, and the red tick on the event axis marks it.
6. `pname=typer&seq=632`: `pickLocal`: its search is at 632–634, the winner is the local given (`given_Show_Int.type`, no `Show.` prefix); `632 ImplicitSearch › 633 Scope contextual (level 4, eligible given_Show_Int.type) › 634 Candidate`: the local given is found in the *contextual* scope, so the implicit scope, where `Show.given_Show_Int` lives, is never searched; there is no `Rejected` event for it.
7. `pname=erasure`: `given_Conversion_Int_String().apply(Int.box(i)).asInstanceOf[String]`; the status line shows structural vs retyped counts, and the lineage on the node links back to the typer tree.

The answer to “why does the local given beat `Show.given_Show_Int`” is step 6: it wins in the contextual scope (level 4) and the search stops there. Routine `Rejected … not eligible` events are not counted by “problems only”; rejections for other reasons are.

## Tour B: for-comprehension desugaring and lowering (`ForDesugar.jsonl`)

1. `pname=parser`: the `for` and `s"…"` as written (source text, `$name` intact).
2. `pname=typer`: `withFilter/flatMap/map`, `intArrayOps(xs).foreach`, `StringContext.apply(...).s(...)`.
3. `pname=elimRepeated`: varargs `*` gone.
4. `pname=interpolators`: `"hello " + name + …`; click the `+` and read the lineage (heuristic).
5. `pname=vcInlineMethods`: `ArrayOps.foreach$extension(ev$1)(…)`.
6. `pname=erasure`: `def $anonfun(x: Int)` plus the adapting closure, `Int.box(n + 1)`; blue dotted underlines are retype-only changes, green are structural (toggle "structural only").
7. `pname=mixin`: the version is labelled **mixin + lazyVals + memoize + nonLocalReturns + capturedVars**; `var acc` becomes `IntRef` (capturedVars).
8. `pname=lambdaLift`: lifted `pairs$$anonfun$1` etc., `closure(acc | <empty>.this.sum$$anonfun$1: …)` (the printer adds the prefix and the ascription).

## Tour C: class/trait lowering (`Mixins.jsonl`)

1. `pname=typer`: traits with `super.log`, `lazy val summary`, `class Service() extends Object(), Timestamped, Counting` (the printed form of `extends Timestamped with Counting`).
2. `pname=getters`: `val prefix` becomes `def prefix`, `lazy val` becomes `lazy def`.
3. `pname=resolveSuper`: `Timestamped$$super$log` / `Counting$$super$log` and their implementations in `Service`.
4. `pname=erasure`: `prefix()`, `Int.box`, erased signatures.
5. `pname=mixin`: the fused group (see label): `Service` gets the `log` forwarder, `Counting$$count`, `$init` calls and the lazy-val holder.
6. `pname=constructors`: `Service.<init>`.

7. `src=data/Mixins-opt.jsonl&pname=genBCode&ir=1&cls=Service` (use the `-opt` recording: the base one has no optimiser): the method panel (`summary$lzyINIT1`, 5 steps) steps the optimiser, ▣ graph shows its control-flow graph with the CAS loop and the exception edge.

The fused miniphases of a megaphase still share one version.
