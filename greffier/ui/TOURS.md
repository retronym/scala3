# Guided tours (updated after the post-evaluation changes)

Base: `http://localhost:8766/ui/index.html` (serve `local/replay/` with `python3 -m http.server 8766`). A `pname=` link now lands on that phase's **output** (cursor at its `PhaseEnd`), the address bar follows what you see (copy it any time), and pasting a link into an open page applies it without a reload. `seq=` alone shows the version visible at that seq (the previous phase's output while a phase is in progress; the banner says so); combine `pname=` and `seq=` to see a phase's tree next to an event in the middle of it. Press `?` for keys; `1`/`2`/`3`/`0` give the tree, events, graph or all views full height. Each link below was opened headless by `tours-check.mjs` and showed the named phase (all steps loaded).

Prefix for every link: `#src=data/<file>.jsonl&skip=1&collapse=1&`.

## Tour A: implicit resolution (`Givens.jsonl`)

1. `pname=parser`: the parsed source text (`List(1, 2, 3).shown`, `(i: String)`, the local `given` in `pickLocal`).
2. `pname=typer`: the typed tree: `shown[List[Int]](…)(using Show.given_Show_List[Int](using Show.given_Show_Int))` and `given_Conversion_Int_String.apply(i)`.
3. `pname=typer&seq=334`: the typed tree with the cursor inside the nested search. Event stack: `Adapt › ImplicitSearch Show[List[Int]] › Candidate given_Show_List › Adapt › Adapt › ImplicitSearch Show[Int] › Candidate given_Show_Int`: the nested search for `using sa: Show[A]`. Select the `given_Show_List` identifier in the tree: tab **used here** shows these search events, tab **defined** its completion. (Drop `pname=` from the link to see the explicit "typer in progress, showing parser output" banner.)
4. From step 3 press `→` a few times to watch the events that follow the search.
5. `pname=typer&seq=363`: the `Conversion` search (358–359) and the feature warning `Report` (363); `i` is outlined. The **Reports (1)** chip in the inspector header jumps here from anywhere, and the red tick on the event axis marks it.
6. `pname=typer&seq=414`: `pickLocal`: the stack shows `DefDef pickLocal`, the winner is the local given (`given_Show_Int.type`, no `Show.` prefix).
7. `pname=erasure`: `given_Conversion_Int_String().apply(Int.box(i)).asInstanceOf[String]`; the status line shows structural vs retyped counts, and the lineage on the node links back to the typer tree.

Still breaks: why the local given beats `Show.given_Show_Int` (rejected candidates are not recorded).

## Tour B: for-comprehension desugaring and lowering (`ForDesugar.jsonl`)

1. `pname=parser`: the `for` and `s"…"` as written (source text, `$name` intact).
2. `pname=typer`: `withFilter/flatMap/map`, `intArrayOps(xs).foreach`, `StringContext.apply(...).s(...)`.
3. `pname=elimRepeated`: varargs `*` gone.
4. `pname=interpolators`: `"hello " + name + …`; click the `+` and read the lineage (heuristic).
5. `pname=vcInlineMethods`: `ArrayOps.foreach$extension(ev$1)(…)`.
6. `pname=erasure`: `def $anonfun(x: Int)` plus the adapting closure, `Int.box(n + 1)`; blue dotted underlines are retype-only changes, green are structural (toggle "structural only").
7. `pname=mixin`: the version is labelled **mixin + lazyVals + memoize + nonLocalReturns + capturedVars**; `var acc` becomes `IntRef` (capturedVars).
8. `pname=lambdaLift`: lifted `pairs$$anonfun$1` etc., `closure(acc | sum$$anonfun$1)`.

## Tour C: class/trait lowering (`Mixins.jsonl`)

1. `pname=typer`: traits with `super.log`, `lazy val summary`, `class Service extends Timestamped with Counting`.
2. `pname=getters`: `val prefix` becomes `def prefix`, `lazy val` becomes `lazy def`.
3. `pname=resolveSuper`: `Timestamped$$super$log` / `Counting$$super$log` and their implementations in `Service`.
4. `pname=erasure`: `prefix()`, `Int.box`, erased signatures.
5. `pname=mixin`: the fused group (see label): `Service` gets the `log` forwarder, `Counting$$count`, `$init` calls and the lazy-val holder.
6. `pname=constructors`: `Service.<init>`.

Same limitation as before: no bytecode, and the five fused miniphases still share one version.
