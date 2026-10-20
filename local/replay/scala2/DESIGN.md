# Greffier for Scala 2.13 (nsc): design

Written with LLM assistance (Claude), see `LLM_POLICY.md`.

Goal: record a scalac 2.13.x compile in the v1 schema (`../SCHEMA-v1.md`) so the existing viewer shows it unchanged, with the same split as Scala 3: a compiler plugin that owns the recorder, and a Byte Buddy `-javaagent` that delivers hooks into an unmodified compiler by pinned join points.

## How nsc differs for the recorder

**Phases.** nsc has no MegaPhase fusion: about 24 phases (`parser`, `namer`, `packageobjects`, `typer`, … `delambdafy`, `jvm`), each a whole-tree pass over every unit. So every phase is its own group (`group == id`, `megaOf: null`), one tree version per phase and unit, and there is no per-miniphase granularity to recover. The viewer's chapters-from-groups degrade to one chapter per phase (pruned by its median rule), which is what an nsc user expects from `-Xprint`.

**Phase boundaries.** Two options: a plugin component after every phase, or a hook on the Run's phase loop. Extra plugin phases change the phase plan (ids, `-Xprint`/`-Ystop` names, `prev.name` tests in `Phase`), and cannot go between `tailcalls`/`specialize`, `explicitouter`/`erasure` and `erasure`/`posterasure` (`runsRightAfter`). So the agent hooks the loop in `Global.Run.compileUnitsInternal` instead, at the two calls it already makes around `globalPhase.run()`: `Profiler.beforePhase(phase)` and `Profiler.afterPhase(phase, snap)` (advice on `NoOpProfiler$` and `RealProfiler`). `afterPhase` runs before `-Xprint` and before `globalPhase` advances, so the recorder prints what `-Xprint:<phase>` would. The plan is untouched, which is what makes byte-identical output plausible. The plugin is then only the recorder's host: `-Xplugin` loads it, `init` installs the observer, and it has no components. Without the agent nothing is recorded (the plugin says so).

**Trees are mutated in place.** nsc's typer sets `tpe` and `symbol` on the parser's nodes (`tree setType`, `setSymbol`), later phases retype in place (`erasure` clears types and retypes the same nodes), and `setPos` moves positions. Children are `val`s, so a node's structure never changes, only its own symbol, position and type. The arena interns by identity, as for Scala 3, but a node record is a snapshot (kind, span, children, symbol, name): when a known tree's symbol or span differs from its record, or one of its children was re-identified, it gets a fresh id, and the version's `derive` says `retype` (exact) from the old id. Types are not in node records, so a type change alone keeps the id and shows up in `version.types`. In practice the typer gives every node a fresh id (as in Scala 3, where typer allocates new trees), and later phases re-identify only spines above changed nodes.

**New trees.** `Transformer`s and `TypingTransformer`s build new nodes with `treeCopy` (`StrictTreeCopier`, or `LazyTreeCopier` returning the old node when nothing changed) and `atPos`. Every strict copy ends in `Tree.copyAttrs(from)`, which is the copier hook (one join point instead of Scala 3's `TreeCopier.finalize`); `duplicate` goes through it too. Lineage: copier → `rewrite` exact; same identity, new snapshot → `retype` exact; otherwise by span (heuristic), `synth`, `elide`, as on Scala 3.

**Symbols and infos.** `syminfo` prints `sym.info` at `exitingPhase(phase)` for symbols defined in this run when the cheap fingerprint (`rawInfo` identity, `rawflags`, owner, name) changed. A symbol that is not yet initialized (`!isInitialized`, e.g. still a namer completer) is not forced: it is printed as `<lazy>` until a later phase sees it completed. Info transformers (erasure, uncurry, specialize, mixin, flatten) give new infos per phase through the type history, so `exitingPhase` shows each phase's view.

**Printing.** `-Xprint`'s printer is `global.newTreePrinter` (`scala.tools.nsc.ast.Printers#TreePrinter`). A subclass overrides `printTree(tree)` to bracket each subtree's text with invisible markers (`ESC[1<id>m` … `ESC[2<id>m`, the same encoding `MarkingPrinter.flatten` strips on Scala 3). nsc's printer streams to a `PrintWriter` with no layout pass, so markers cannot change line breaking. Offsets are UTF-16 code units of `unit.source.content` (the meta unit text is that array, which may have a newline appended by `BatchSourceFile`).

Printing is not free of side effects: nsc's `print(tree)` rebuilds an initialized definition from its symbol (`ClassDef(sym, impl)`), and the factories' `atPos` gives position-less subtrees a position (delambdafy's new `Template`s). The recorder notes the reachable trees without a position before printing and resets them after (`positionsRestored` in `stats`).

**Positions.** nsc has range positions by default in 2.13. Span: `[start, end]` for a range position, `[point, point]` for an offset position (synthetic trees made `atPos(pos.focus)`), `null` for `NoPosition`.

## Typer events: which nsc sites

| v1 event | nsc site | notes |
|---|---|---|
| `Typed` / `Retyped` | `Typers$Typer.typed(Tree, Mode(int), Type)` enter/exit (incl. throw) | `Retyped` when the running phase is past typer (erasure's retyping, `localTyper` in transforms). An already-typed tree returns at once: written only if something nested. Input id is taken at enter (the untyped node), result id at exit. |
| `Adapt` | `Typers$Typer.adapt(Tree, Mode, Type, Tree)` | No arm/consume pair as for dotc's `adapt1`: nsc's `adapt` calls itself after a conversion step (`adapt(tree setType …)`), and those calls are nested `Adapt` events. Written only if the tree changed or something nested. |
| `Complete` | `Namers$LockingTypeCompleter.complete$` (static trait body; all namer completers) | Library symbols (SymbolLoaders, unpickler) are not recorded, as on Scala 3 (`Namer.Completer` only). `forcedBy` is `context.owner` of the innermost running `typed`. |
| `ImplicitSearch` | `Implicits$ImplicitSearch.bestImplicit()` | the search object carries `tree`, `pt`, `pos`; result `SearchResult` (`isSuccess`, `tree`). One per `inferImplicit*` (views and implicit args). |
| `Candidate` | `ImplicitSearch.typedImplicit(ImplicitInfo, Boolean, Boolean)` (name-mangled private) | candidate symbol `info.sym`, `ref` = `info.tpe`. Rejections by `rankImplicits` / divergence / shadowing (`Rejected`, `Scope`) are not hooked yet. |
| `Report` | `scala.reflect.internal.Reporter.filteredInfo(Position, String, Severity, List)` (every report, whatever the reporter class) and `Contexts$BufferingReporter.issue(AbsTypeError, Context)` | buffered (`silent`) errors are `buffered:true`, like StoreReporter on Scala 3. nsc buffers many speculative errors (e.g. `value += is not a member of Int` before the `n = n + 1` rewrite). |
| copier | `Trees$Tree.copyAttrs(Tree)` | |
| phase begin/end | `NoOpProfiler$.beforePhase/afterPhase`, `RealProfiler.beforePhase/afterPhase` | `InitPhase` is skipped. |

Edges as on Scala 3: `resolves` from `Typed` results that are `RefTree`s, `forces` from `Complete`, `implicit` from successful `ImplicitSearch`, between top-level classes.

There is no tentative typing to hook in the dotc sense: nsc's `silent(...)` and `tryTypedApply` buffer errors in a fresh `ContextReporter`. A `Tentative` event could come from `Typer.silent`; not in the first cut.

## Backend

nsc's GenBCode is what Scala 3's backend was ported from, so the JVM recorder ports nearly line by line:

| v1 hook | nsc 2.13 site |
|---|---|
| class nodes | `CodeGen.genClass(ClassDef, CompilationUnit)` returns the ClassNode; `genMirrorClass(Symbol, CompilationUnit)` the mirror |
| method | `BCodeSkelBuilder$PlainSkelBuilder.genDefDef(DefDef)` (stashes `dd`), `initJMethod(int, List)` (fires, reads `mnode`) |
| ranges | `BCodeBodyBuilder$PlainBodyBuilder.genStat(Tree)` and `genLoadTo(Tree, BType, LoadDestination)` (which `genLoad` and the method body call) enter/exit: instruction index range in `mnode` |
| optimiser passes | `opt.LocalOpt.methodOptimizations(MethodNode, String)` (owner); `removalRound` (one round); the exit of each pass method (`nullnessOptimizations`, `removeUnreachableCodeImpl`, `BoxUnbox.boxUnboxElimination`, `CopyProp.copyPropagation`, … `LocalOptImpls.simplifyJumps`), then `compactLocalVariables`/`removeUnusedLocalVariableNodes` and `removeEmptyLineNumbers`. dotc's port has `traceIfChanged(pass)` after each pass, and so does nsc's source, but the 2.13 compiler jar is built with the optimiser, which inlined every `traceIfChanged$1` call. nsc's default `-opt:l:default` already runs DCE and the line-number cleanup, so un-optimised compiles get a few `jvm:*` versions too. |
| inliner | `opt.Inliner.inlineCallsite(Callsite, Option, Boolean)` returns the instruction map; the callsite names the method and the callee |
| class written | `PostProcessor.serializeClass(ClassNode)` (after the ClassWriter) |

`-Ybackend-parallelism` defaults to 1 in nsc, and then the post-processor (local optimiser, class writer) runs on the main thread; with more threads the plugin skips JVM recording, as on Scala 3. Without the global optimiser nsc post-processes each unit right after generating it, so the local passes of unit 1 run before unit 2 is generated; the recorder only orders JVM records by step, so this does not show.

## What is cheap, what needs the agent, what cannot be matched

- Cheap (plugin only): the recorder, tree versions, offsets, symbols, lineage by span. But the plugin has no phase-end callback of its own without changing the plan, so in practice everything needs the agent's two profiler hooks.
- Needs the agent: phase boundaries, every typer event, the copier hook (exact lineage), every backend hook.
- An `AnalyzerPlugin` (`pluginsPt`/`pluginsTyped`, `pluginsNotifyImplicitSearch`/`…Result`) would give typer and implicit events without an agent, but `pluginsTyped` is not called when typing throws or when the tree was already typed, and there is no completer or candidate hook. Not used.
- Cannot be matched: per-miniphase versions (there are none); dotc's `Retyped` from the Inlining phase (no inliner phase on trees); TyperState-based tentative typing; dotc's Name content hashes (not needed: nsc's `Name.hashCode` is the name table index, so implicit candidate order does not depend on identity hashes, and the corpus streams are byte-identical run to run).
