# Replay interchange schema v1 (JSONL)

Written with LLM assistance (Claude), see `LLM_POLICY.md`. Produced by `plugin/` (`meta.schema = "v1"`); validated by `plugin/analyze.py`. Successor of v0 in `PLAN-replayable-compiler.md`; merges the Track A/B/C proposals.

One JSON object per line, discriminated by `t`. The stream is append-only and can be read front to back: every id a record refers to is defined by an earlier line. Ids (node, sym, type) are dense ints starting at 0, per recording.

## Ordering

- One global `seq` counter covers all events (`ev`), including `PhaseBegin`/`PhaseEnd`. `ev` lines appear in strictly increasing `seq` order, with no gaps.
- The recorder buffers a phase's events and writes them at the phase's end (not at event exit), preceded by the `node`/`sym`/`type` records they use, and followed by that phase's `edge` records. Then the `RecordPhase` that follows writes, per unit, any new `node`/`sym` records, the `version`, its `syminfo` and its `derive` records.
- So a phase group's block is: `[defs] ev PhaseBegin … ev PhaseEnd [edges]`, then per unit `[defs] version [syminfo] [derive]`.

## Common conventions

- `span`: `[start, end]` in UTF-16 code units of the unit's source text, end-exclusive; `null` when the tree/symbol has no position.
- `unit`: index into `meta.units`, or `null` (source not compiled in this run, e.g. library TASTy).
- `phase`: a **phase group** id. A group is what the Run executes as one step: a plain phase, or a MegaPhase (in the default plan every miniphase is its own group except `mixin..capturedVars`). A group's id is the id of its first member. `phase` records map member phases to their group.

## Records

```
{"t":"meta","schema":"v1","compiler":"…","args":["name=value",…],
 "units":[{"id":0,"path":"…","text":"<source>"}]}
{"t":"phase","id":p,"name":"erasure","mini":true,"group":g,"megaOf":"MegaPhase{…}"|null,"denotTransformer":bool}
{"t":"node","id":n,"kind":"Apply","span":[s,e]|null,"unit":u|null,"children":[ids],"sym":symId|null,"name":"…"|null}
{"t":"sym","id":s,"name":"…","owner":s2|null,"fullName":"…","kind":"class|type|term","local":bool,"unit":u|null,
 "firstPhase":g,"firstSeq":q|null}
{"t":"syminfo","sym":s,"fromPhase":g,"name":"…","info":"<printed>","flags":"…","owner":s2|null}
{"t":"type","id":k,"text":"<printed at the end of the phase that interned it>"}
{"t":"version","phase":g,"unit":u,"seq":q,"root":n,
 "text":"…" | "textSameAs":g0, "offsets":[[n,s,e],…] | "offsetsSameAs":g0,
 "types":[[n,"text"],…]?, "nodes":count, "new":count}
{"t":"derive","phase":g,"unit":u,"new":[ids],"old":[ids],"how":"copy|rewrite|split|synth|elide|retype","confidence":"exact|heuristic"}
{"t":"ev","seq":q,"parent":q0|null,"kind":"…","phase":g,"unit":u|null,"span":[s,e]|null,"nodes":[…],"syms":[…],"payload":{…}}
{"t":"edge","kind":"resolves|forces|implicit","from":s,"to":s2,"weight":w,"ev":q,"phase":g}
{"t":"stats",…}   last line; counters only
```

### node

Interned by tree identity the first time either a typer event or a phase walk sees it; its subtree is interned first, so `children` ids are always smaller than the parent's. `children` are the tree's direct sub-trees in field order. `name` is the name of a `NameTree` or the shown constant of a `Literal`. Node records carry no type; see `version.types`.

### version

The output of phase group `phase` for `unit`. `seq` is the `seq` of that group's `PhaseEnd`: a cursor at `seq' >= seq` sees this version. Groups that did not run produce no version.

- `text`/`offsets`: printed tree (like `-Xprint`) and `[nodeId, start, end]` triples, end-exclusive UTF-16, in pre-order (start ascending, outer before inner).
- `textSameAs: g0` / `offsetsSameAs: g0`: identical to the text/offsets of this unit's version at group `g0`, an earlier version (not necessarily the immediately previous one).
- `types`: `[nodeId, text]` for reachable nodes whose printed type differs from the last text listed for that node (including first listing). The type of node n at version v is the last entry for n at or before v.
- `nodes`: reachable nodes; `new`: reachable nodes not reachable in this unit's previous version.
- When a version adds no nodes, changes no node's type object, and no reachable local symbol's denotation changed, nothing is printed: it is written with `textSameAs`/`offsetsSameAs` and no `types`.

### derive

Lineage from this unit's previous version to this one, for its `new` nodes, plus the old nodes that left.

| how | new | old | meaning |
|---|---|---|---|
| `copy` | [n] | [o] | pure copy: same kind, name, symbol, span, printed type; each child is the same node or a pure copy of the corresponding old child. Viewers may collapse these. |
| `rewrite` | [n] | [o] | n replaces o (copier, typer's untyped→typed, or same span) |
| `retype` | [n] | [o] | n is o re-typed by a later-phase typer (Erasure, Inlining) |
| `split` | [n1,n2,…] | [o] | several new nodes trace to one old node |
| `synth` | [ns] | [] | new nodes with no ancestor; one record per (phase, unit) |
| `elide` | [] | [os] | old nodes that left the tree with no successor; one record per (phase, unit) |

`confidence: "exact"` when the source was found by following the copier hook (`TreeCopier.finalize`) or a `Typed`/`Retyped` event (result → input) back to a node of the previous version; `"heuristic"` when matched by equal span (preferring an old node that was dropped, then same kind, then the outermost). `synth` and `elide` are always heuristic. `merge` is in the vocabulary but not produced (no reliable signal yet).

### ev

`phase` is the running phase group, not `ctx.phase` (completers run at the symbol's creation phase). `parent` is the innermost enclosing event, or null. `nodes` and `syms` are positional; `null` marks an absent role. `syms` drops absent symbols.

| kind | nodes | syms | payload |
|---|---|---|---|
| `PhaseBegin` | [] | [] | `name`, `members` (miniphase names) |
| `PhaseEnd` | [] | [] | {} |
| `Typed` | [untyped input, typed result] | [result's symbol, for RefTree/MemberDef] | `pt`, `tpe` (type ids), `result` (tree kind); `aborted:true` if it threw |
| `Retyped` | [input, result] | as Typed | as Typed. `Typed` when `ctx.phase` is a typer phase, else `Retyped` (Erasure, Inlining re-typing) |
| `Adapt` | [input, output] | [] | `pt`, `changed`. Only written if the tree changed or something nested in it was recorded |
| `Complete` | [] | [completed sym] | `ok`, `forcedBy` (sym id of `ctx.owner`) |
| `ImplicitSearch` | [argument, chosen tree] | [chosen sym] | `pt`, `result` (`"success"` or failure class) |
| `Candidate` | [tree if accepted] | [candidate sym] | `ref` (type id), `accepted`, `reason` |
| `Report` | [] | [] | `level`, `msg` (rendered at phase end), `reporter` (class; `StoreReporter` = speculative) |

### edge

Between the top-level classes enclosing `from` and `to`, aggregated per (kind, from, to, phase group): `weight` is the number of occurrences in that phase, `ev` the first one's seq. A later phase may add another record for the same pair; sum weights for totals. `resolves` from `Typed` results that are RefTrees, `forces` from `Complete`, `implicit` from successful `ImplicitSearch`.

## Extension: JVM bytecode (`ir: "jvm"`)

Produced when the run reaches `genBCode` (switch off with `-P:replay-record:nojvm`). `meta.requires` lists `-Ybackend-parallelism:1`: the optimiser and class-writer hooks have no Context, and with more backend threads they would run concurrently, so the plugin skips JVM recording (with a warning) unless parallelism is 1. Records without an `ir` field are tree records, as above.

All JVM records come after the last tree version and before `stats`. They are grouped by JVM phase, then by class: `[phase] { [node…] version [derive…] }*`.

### phase

The genBCode group keeps its id. If the optimiser changed anything, one synthetic phase per optimiser step follows, with fresh ids above every compiler phase id, in execution order:

```
{"t":"phase","id":p,"name":"jvm:r1:dce","ir":"jvm","mini":false,"group":p,"megaOf":null,"denotTransformer":false}
```

| name | step |
|---|---|
| `jvm:inliner` | after the global optimiser (inliner and closure optimiser, `-opt-inline`, `-opt:closure-invocations`), for all classes |
| `jvm:r<k>:<pass>` | after local optimiser pass `<pass>` in removal round `k` (`nullness`, `dce`, `boxUnbox`, `copyProp`, `staleStores`, `redundantCasts`, `pushPop`, `storeLoadPairs`, `staleHandlers`, `simplifyJumps`) |
| `jvm:localVariables`, `jvm:lineNumbers` | the local optimiser's final cleanups |
| `jvm:classfile` | the class as handed to the `ClassWriter` (InnerClasses attribute rebuilt) |

Rounds are counted per method (`beforeMethodOpt` starts a round), so `jvm:r2:dce` holds whatever methods reached a second round. Only steps that changed some method's text get a phase.

### node

```
{"t":"node","id":n,"ir":"jvm","kind":"ClassNode","name":"Givens$","children":[fields…, methods…],"sym":s|null,"mirror":bool, "span":null,"unit":u}
{"t":"node","id":n,"ir":"jvm","kind":"FieldNode","name":"MODULE$","desc":"LGivens$;","sym":s|null, "children":[],"span":null,"unit":u}
{"t":"node","id":n,"ir":"jvm","kind":"MethodNode","name":"render","desc":"()Ljava/lang/String;","insns":38,"sym":s|null, "children":[],"span":null,"unit":u}
{"t":"node","id":n,"ir":"jvm","kind":"InsnRange","method":m,"range":[start,end], "children":[],"sym":null,"name":null,"span":null,"unit":u}
```

- Ids share the tree id space (dense, increasing).
- `ClassNode.name` is the JVM internal name. `mirror` is true for a module's static-forwarder class.
- `MethodNode.insns` counts every `AbstractInsnNode`, including labels, line numbers and frames. `sym` is the DefDef's symbol (for a forwarder, the module method's symbol).
- `InsnRange.range` is `[start, end)` in instruction indices of method `m` at the version that lists it. Instructions are not nodes themselves. A range belongs to exactly one MethodNode id: when a method changes, its ranges get new ids too.
- Ranges nest at genBCode (they come from nested `genLoad` calls). One range node may stand for several tree nodes with the same extent (e.g. `genStat` and the `genLoad` it calls, or a `Block` and its only expression); each tree node gets its own `derive`.

### version

```
{"t":"version","ir":"jvm","phase":p,"unit":u,"seq":q,"class":"Givens$","root":classNodeId,
 "text":"…","offsets":[[n,s,e],…],"nodes":count,"new":count}
```

- One version per class, not per unit. `seq` is genBCode's `PhaseEnd` for every JVM phase: the optimiser runs inside genBCode.
- At genBCode every class has a version. At a later JVM phase a class has a version only if one of its methods changed (or, for `jvm:classfile`, the class text changed). The class at JVM phase `p` is its latest version with phase ≤ `p`, in phase-record order. There is no `textSameAs`.
- `text` is ASM `Textifier` output for the class (as `javap -c -v`-like as ASM gets: access flags, descriptors, labels `L0…`, `LINENUMBER`, `LOCALVARIABLE`, `MAXSTACK`).
- `offsets` cover the ClassNode (whole text), each FieldNode, each MethodNode (header and body), and each InsnRange (from the range's first instruction line to the start of the next instruction line after its end). They are in pre-order like tree offsets. Overlapping ranges are possible after optimisation, because a range is re-placed at the min..max of its surviving instructions.
- `nodes` is the number of nodes in `offsets`; `new` is the number of node records written for this version.

### derive

JVM derives carry `"ir":"jvm"`. Their `old` ids are tree nodes (for `codegen`) or earlier JVM nodes.

| how | new | old | meaning |
|---|---|---|---|
| `codegen` | [jvm nodes] | [tree node] | at genBCode. TypeDef → ClassNode (main and mirror class); ValDef → FieldNode (`MODULE$` ← TypeDef); DefDef → MethodNode (forwarders ← the module method's DefDef); tree node → InsnRange (one record per tree node, listing all its ranges) |
| `rewrite` | [n] | [o] | a changed MethodNode, a ClassNode's next version, or a range whose instruction text changed |
| `copy` | [n] | [o] | a range whose instruction text is unchanged in a changed method |
| `elide` | [] | [ranges] | ranges none of whose instructions survived the step |
| `inlined-from` | [range] | [callee MethodNode or InsnRange], or [] | instructions the inliner copied into this method. `callee` (`"Owner.name(desc)"`) names the callee. `old` is the callee's genBCode MethodNode (whole body) or one of its genBCode InsnRanges (the copies of that range's instructions). It is `[]` when the callee was not compiled in this run (classpath bytecode) |

Confidence for `codegen`:

- `exact` when the tree is the arena node itself, follows the copier hook back to one, or (for DefDef/ValDef) is matched by symbol, or a forwarder is matched by (owner, name, descriptor).
- `heuristic` when it is matched by equal span and kind, by method name (static forwarders `m$` and the trait `$init$` get fresh symbols), or `<clinit>` falls back to the TypeDef.
- JVM steps after genBCode are always `exact`. They follow instruction-node identity, which the optimiser preserves for the instructions it keeps.

No `ev` records are written for the backend (no `GenLoad` events). The ranges carry the same information without adding to the `seq` order.
