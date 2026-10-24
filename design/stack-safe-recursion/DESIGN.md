# Stack-safe recursion in dotc

Status: draft for discussion. Scope: dotc **itself** should keep going on deeply nested input instead of failing. Making a similar mechanism available to user code is future work.

## Problem

dotc's core algorithms recurse in step with the input: `Typer.typedUnadapted`, `Applications`, `TreeMap`/`TreeAccumulator`, MegaPhase's `transformTree`, `TypeMap`, `TypeComparer`, `TreePickler`/`TreeUnpickler`, and the backend's `genLoad`. Deeply nested input runs out of native stack. Typical sources are long `a + b + ...` chains, `x :: y :: ... :: Nil` literals, generated `if`/`else if` ladders, deeply nested calls, and long chains of `val`s with inferred types.

The parser already copes with long infix chains: it uses an explicit operator stack (`opStack`, `Parsers.scala:1167`). Every later phase then recurses over the left-deep tree that the parser builds.

Since scala/scala3#25937, `ctx.handleRecursive` counts nesting. When the count reaches `-Xmax-fuel` (default 550), it throws `RecursionOverflow` and reports "Recursion limit exceeded". That error is better than a `StackOverflowError`, but it is still an error. The workaround is unchanged: raise `-Xss` and `-Xmax-fuel`, which is a build-tool setting. Under zinc, sbt, Bloop or Metals, the user often doesn't control the stack size of the compiler thread.

Goals:
- programs like these compile;
- the fast path costs nothing measurable;
- genuine cycles (illegal cyclic references, divergent expansion) are still reported with today's `RecursionOverflow` message.

## Common framework

Every option below keeps ordinary recursion as the default and switches to a different mode **only past a depth threshold**. `handleRecursive` already sits on the recursive paths and already counts depth, so it is the natural switch point.

Two parts are shared by all options:

- **Two limits.** `-Xmax-fuel` becomes the threshold for switching modes, not for giving up. A second limit on total depth (native plus heap, around 10⁶) still detects genuine cycles and reports today's `RecursionOverflow` message. Without it, a genuine cycle would end in a slow `OutOfMemoryError`.
- **A threshold-0 differential test.** The deep path rarely runs on real code, so bugs there would hide. With the threshold set to 0, every tree goes through the deep path. Run `testCompilation` in that mode and diff the TASTy, bytecode and diagnostics against a normal run. The toy below does the same on 20k random trees.

## Options

### A. Spine loop (past a depth)

Many deep trees are *spines*: one child position nests deeply while the others stay shallow.

- `desugar.binop` turns `a + b + c` into `Apply(Select(Apply(Select(a, +), b), +), c)`. Right-associative operators are swapped (`makeOp(right, left, …)`, `Desugar.scala:1801`), so `x :: y :: Nil` is also a qualifier spine.
- In `if`/`else if` ladders the spine is `If.elsep`; in nested blocks it is `Block.expr`.

Each spine node kind is split into two halves:

- **pre**: type the shallow siblings that come first, then compute the spine child's prototype and context;
- **post**: finish the node, given the typed spine child, including `adapt` to the node's own `pt`.

The recursive driver runs `pre; typed(child); post`, which is the same order as today. Past the threshold, the loop driver descends the spine running each pre half, types the innermost node normally, then ascends running each post half. Children that aren't on the spine are typed by ordinary recursion from inside pre or post. If one of them is deep itself, it reaches the threshold again and starts its own loop.

### B. Worklist (past a depth)

Write each node's typing **once** in step style. Every child typing becomes a value, `Need(child, pt, ctx, k)`, where the continuation `k` is the rest of the node's code. There are two drivers:

- **Below the threshold** a recursive driver runs a node's steps and recurses into `typed` for each `Need`.
- **Past the threshold** a loop keeps the continuations on a heap stack, so native depth stays constant. It also covers every child position, not just one designated spine child.

This is a hand-written CPS/defunctionalisation of the typer.

### C. Exception-based continuation capture (generic)

At the threshold, `handleRecursive` throws a preallocated `StackSwitch`. The bytecode of instrumented methods has extra handlers at each instrumented call site. Each handler saves its frame (resume label, live locals, spilled operand stack) into the exception and rethrows. A trampoline at the bottom of the segment runs the pending work on the now-shallow stack, then resumes the saved frames from innermost to outermost through generated `m$resume` methods. Each resume method jumps to the instruction after the original call site. Frames are resumed once and return to the trampoline; the native stack is never rebuilt.

This is "generalized stack inspection" (Pettyjohn et al. 2005), the technique that gives Stopify its deep stacks. It is implemented as a backend post-processing step over `MethodNode`s, using the existing `TypeFlowAnalyzer`.

It covers any recursion through instrumented code without touching Typer's source. The costs:
- the bytecode of every instrumented method doubles (`m$resume` is a copy);
- the backend transform is complex: handler ordering, operand-stack spilling, and resetting the depth that `handleRecursive` keeps in a local;
- a capture only works through an unbroken run of instrumented frames. Uninstrumented higher-order calls (`List.map`, `foldLeft`) end the run, and dotc recurses through those everywhere.

Details from the first draft are summarised here; they are only worth expanding if A and D turn out to be insufficient.

### D. Stack hop (fallback)

Past the threshold, run the pending block on a fresh thread with a large stack and join it. This is the JVM's version of what rustc does with `ensure_sufficient_stack`: `stacker::maybe_grow` switches to a newly allocated stack segment when the remaining stack drops below a red zone. The JVM can't switch stacks within a thread, so a thread hop is the closest equivalent.

It needs no refactoring, but it has costs:
- creating a thread costs roughly tens of µs per hop;
- `ThreadLocal`s don't carry over (dotc has few, all in `profile/`);
- IDE cancellation by interrupt has to be forwarded to the new thread;
- stack traces are split across threads.

As a fallback that only kicks in rarely, after A has handled the common shapes, those costs are acceptable.

## Toy implementations

[`toy/`](toy/) contains three typers for a small bidirectional language: literals, variables, `+`/`<`, `if`, `let`, and calls to built-ins. The language has expected types, errors converted to an error tree per node, and an environment. The typing rules are shared (`Rules` in [`Lang.scala`](toy/Lang.scala)); only the traversal differs.

- [`Baseline.scala`](toy/Baseline.scala): direct-style recursion, the shape of Typer today.
- [`SpineTyper.scala`](toy/SpineTyper.scala): option A. Spine children are `BinOp.l`, `If.e` and `Let.body`.
- [`WorklistTyper.scala`](toy/WorklistTyper.scala): option B.

Run with `scala-cli run design/stack-safe-recursion/toy`.

The spine split and its loop driver:

```scala
private def spineStep(e: Expr, pt: Option[Ty])(using env: Env): SpineStep | Null = e match
  case BinOp(l, op, r) =>
    new SpineStep:
      def child = l
      def childPt = None
      def childEnv = env
      def finish(l1: T) = adapt(binop(op, l1, typed(r, rhsPt(l1))), pt)
  case If(c, t, el) =>
    val c1 = typed(c, Some(BoolT))           // pre: shallow siblings first
    val t1 = typed(t, pt)
    new SpineStep:
      def child = el
      def childPt = branchPt(pt, t1)         // spine child's pt depends on a sibling
      def childEnv = env
      def finish(e1: T) = adapt(mkIf(c1, t1, e1), pt)
  ...

private def typedSpine(e0: Expr, pt0: Option[Ty])(using env0: Env): T =
  val stack = ArrayBuffer[SpineStep]()
  var e = e0; var pt = pt0; var env = env0
  var innermost: T | Null = null
  while innermost == null do
    try
      spineStep(e, pt)(using env) match
        case null => innermost = guard(adapt(typedOther(e)(using env), pt))
        case s    => stack += s; e = s.child; pt = s.childPt; env = s.childEnv
    catch case ex: TypeError => innermost = T.err(ex.getMessage)
  var res = innermost.nn
  while stack.nonEmpty do
    res = guard(stack.remove(stack.length - 1).finish(res))
  res
```

The worklist step function and its loop driver:

```scala
private def step(e: Expr, pt: Option[Ty])(using env: Env): Step = e match
  case BinOp(l, op, r) =>
    Need(l, None, env, l1 =>
    Need(r, rhsPt(l1), env, r1 =>
    Done(adapt(binop(op, l1, r1), pt))))
  case If(c, t, el) =>
    Need(c, Some(BoolT), env, c1 =>
    Need(t, pt, env, t1 =>
    Need(el, branchPt(pt, t1), env, e1 =>
    Done(adapt(mkIf(c1, t1, e1), pt)))))
  case Call(f, args) =>
    def loop(as: List[Expr], ps: List[Ty], acc: List[T]): Step = as match
      case a :: as1 => Need(a, Some(ps.head), env, a1 => loop(as1, ps.tail, a1 :: acc))
      case Nil      => Done(adapt(call(f, acc.reverse), pt))
    loop(args, params(f, args.length), Nil)
  ...

private def typedWorklist(e: Expr, pt: Option[Ty])(using env: Env): T =
  val stack = ArrayBuffer[T => Step]()
  var s = guarded(step(e, pt))
  while true do
    s match
      case Need(c, cpt, cenv, k) =>
        if stack.length >= maxHeapDepth then throw RecursionOverflow()
        stack += k
        s = guarded(step(c, cpt)(using cenv))
      case Done(t) =>
        if stack.isEmpty then return t
        s = guarded(stack.remove(stack.length - 1)(t))
  throw AssertionError()
```

Output ([`toy/out.txt`](toy/out.txt); n = 100 000, 512 KiB thread stack; timings are toy numbers, not benchmarks):

```
differential threshold=0    spine=same worklist=same  (20000 trees, 15775 with errors)
differential threshold=1    spine=same worklist=same  (20000 trees, 15775 with errors)
differential threshold=2    spine=same worklist=same  (20000 trees, 15775 with errors)
differential threshold=5    spine=same worklist=same  (20000 trees, 15775 with errors)
differential threshold=1000 spine=same worklist=same  (20000 trees, 15775 with errors)

input                      baseline              spine                        worklist
left chain  a+b+c+...      StackOverflowError    ok IntT 200001 nodes 8ms     ok IntT 200001 nodes 6ms
right chain a+(b+(c+..))   StackOverflowError    StackOverflowError           ok IntT 200001 nodes 8ms
else-if ladder             StackOverflowError    ok IntT 500003 nodes 17ms    ok IntT 500003 nodes 18ms
let chain                  StackOverflowError    ok IntT 399999 nodes 43ms    ok IntT 399999 nodes 95ms
nested call args           StackOverflowError    StackOverflowError           ok IntT 200001 nodes 8ms
left chain, error mid      StackOverflowError    ok IntT 200001 nodes 1err 4ms ok IntT 200001 nodes 1err 4ms
```

Both loop drivers produce the same result as the baseline at every threshold. The spine loop fails, as expected, when the depth is in a child position it doesn't designate: right operands and call arguments.

## Spine loop vs worklist

| | A. Spine loop | B. Worklist |
|---|---|---|
| Deep positions covered | only the designated spine child of each converted kind | every child of every converted kind |
| Child `pt` must be known | in pre, before the spine child is typed; this limits which child can be the spine | when the `Need` is emitted; always true by construction |
| Refactor in dotc | split `typedSelect`/`typedApply` (fun part)/`typedIf`/`typedBlock`/`typedTyped` into pre and post; the rest stays direct style | every `typed` call site in the converted code becomes a continuation, including deep inside `Applications` (argument typing, overloading) |
| Fast-path allocation | one step object per spine node | one `Need` and one closure per child |
| Speculation (`tryEither` etc.) | the loop does not descend through it; `spineStep` returns `null` there (see below) | must be modelled as a frame kind, and needs CPS versions of the `tryEither` family |
| Readability and debugging | Typer stays direct style; only the split point moves | Typer becomes CPS code; stack traces and stepping in the debugger lose the call structure even below the threshold |
| Not covered by either | recursion through completers, `TypeComparer`/`TypeMap` on deep types, inlining and macro expansion: these need option D |

The toy makes B look cheap, because a toy `Call` types its arguments in one obvious place. In dotc, arguments are typed inside `Applications`, by `FunProto.typedArgs`/`typedArg` (`ProtoTypes.scala:496`), possibly once per overload alternative, with results cached in the prototype's state. Making nested-call arguments worklist-safe means CPS-converting `Applications`, and that is most of the cost of B.

The middle ground is B restricted to a few uniform node kinds. TypeScript's binary-expression trampoline (see Prior art) does this: a worklist over nested binary expressions, covering both operands. In dotc the obvious candidates are the generic traversals: `TreeMap`, `TreeAccumulator`, and `TypeMap` over `AppliedType` arguments. Their code is already uniform per node kind, so an explicit-stack driver can be written once.

## Scala-specific challenges

### Threading `pt`

dotc's typer is bidirectional: the expected type flows down, the typed tree flows up, and `adapt` reconciles the two. Several properties of prototypes matter here:

- **Prototypes defer work, and that is what makes spines possible.** A `SelectionProto` (`?{ + : ?}`) needs only the member name and the expected type of the member. A `FunProto` holds the arguments *untyped*, together with a result prototype. Both can therefore be built before the qualifier or function is typed. That is why `Select.qualifier` and `Apply.fun` can be spine children and argument positions cannot: arguments are typed later, against formal parameter types that only exist once the function's type is known.
- **`pt` often depends on siblings.** In `if`, the else branch's expected type depends on the then branch (`branchPt(pt, t1)` in the toy). A lambda's parameter types come from the function's type, and an argument's expected type comes from the method type. A spine child must have its `pt` available in pre, after only *shallow* siblings have been typed. B has no such restriction: the `Need` is emitted after the siblings are done.
- **Prototypes are stateful.** `FunProto.state` caches typed arguments, and `pt` may mention uninstantiated `TypeVar`s, whose constraints are kept in the context's `TyperState`. Both drivers pass the *same* prototype objects and run pre and post halves in the same order as native recursion: pre₁…preₙ, the innermost node, postₙ…post₁. The state is therefore mutated in the same order. The `locked` set of type vars passed to `typed(tree, pt, locked)` has to be carried in the step as well.
- **`adapt` can retype a node.** Inserting `apply`, eta-expanding, or inserting an implicit conversion on a qualifier sometimes retypes the node. When the retry reuses the already-typed qualifier through `untpd.TypedSplice`, it stays shallow, which is mostly the case today. A retry that retypes the *untyped* subtree goes back through `typed`: it is stack-safe, because it reaches the threshold again, but it costs O(depth) per level, quadratic overall. That cost exists today too, but it should be found and fixed for the spine kinds.

### Speculative typechecking

dotc types subtrees speculatively and then commits or discards the result:
- `tryEither` and `tryAlternatively` (`Typer.scala:142`);
- applicability tests during overload resolution;
- implicit search candidates;
- the `typedSelect` fallbacks for extension methods and conversions;
- `tryInsertApplyOrImplicit`;
- `TypeComparer`'s exploratory subtyping checks.

The *extent* of a speculation is mostly a value already: `ctx.fresh.setNewTyperState()` puts a fresh constraint and a buffering reporter into the `Context`. If the spine child's context is that nested context, the speculation covers the child. What is not a value is the **decision afterwards**: `if nestedCtx.reporter.hasErrors then fallBack(result, nestedCtx.typerState) else { commit(); result }`. Both drivers handle it the same way: it is control flow that runs after the child, so it is a post step.

- **Spine loop:** by default, descent stops at a speculation boundary (`spineStep` returns `null`). For the few boundaries that are on hot spines (for example the extension-method and conversion fallback in `typedSelectWithAdapt`), post does the commit or fallback. The fallback must reuse the typed child through `TypedSplice` instead of retyping the untyped subtree, or each level on a failing spine retypes everything below it.
- **Worklist:** speculation becomes a frame kind that holds the nested context and the fallback. The `tryEither` family needs CPS variants, and every `ctx.test`, `explore` and `exploreInFreshCtx` reached from converted code needs one too.
- **Both:** `finally`-style restoration around a child call has to move into post and also run on the exception path. Examples are `TyperState` snapshot and reset, `withMode`, and the `Stats`/`trace` bookkeeping. In B this needs an exception channel through the continuations (`Done(Left(ex))`); the toy only models per-node conversion from `TypeError` to an error tree. Errors that are *reported* rather than thrown go to `ctx.reporter`, which is part of the context, so they need nothing extra.
- **Heap growth:** a deep spine under nested speculation builds a deep chain of nested `TyperState`s and reporters. That chain lives on the heap, but committing walks it, so speculation on deep spines should be measured.

### Other interactions

- **Completers.** Typing `val a1 = a2` forces `a2`'s completer. That runs `typedAheadExpr` under the completer's own context, from inside `SymDenotation.completeFrom` and `Namer` frames. A long chain of completers recurses through frames that are not children of any tree node, so neither A nor B helps. The structural fix would be demand-driven, suspendable completion, as in Kotlin's FIR with its lazy phases or rustc's query system. That is out of scope; D handles it.
- **Typer subclasses.** `ReTyper`, `Erasure.Typer` and `InlineTyper` override `typedSelect` and `typedApply`. A step is valid only if the class's override of that node kind is aware of the pre/post split; otherwise `spineStep` returns `null`. Erasure retypes every tree, so it hits the same spines and is the next one to convert after the frontend.
- **Other phases.** MegaPhase is already split into pre and post halves (`prepareForSelect` / `goSelect`, `MegaPhase.scala:52`, `:632`), so a spine loop there is mechanical. `TreeUnpickler.readTerm` for `SELECT`/`APPLY` reads the qualifier or function next, so it can loop over a stack of pending node builders. `TreePickler` needs a stack of pending length fix-ups. The backend needs `genLoad` on receivers and the collection of string-concatenation operands.

## Prior art

Spine loops in production compilers:
- **Roslyn (C#).** The binder handles left-nested binary operators iteratively. A comment in `Binder_Operators.cs` reads "Do not blow the stack due to a deep recursion on the left". Elsewhere a `StackGuard` calls `RuntimeHelpers.EnsureSufficientExecutionStack()` once recursion is deeper than `MaxUncheckedRecursionDepth = 20`, and reports an error instead of crashing.
- **TypeScript.** `createBinaryExpressionTrampoline` (`factory/utilities.ts`), used by the binder and the emitter, is a state machine (`onEnter`/`onLeft`/`onOperator`/`onRight`/`onExit`) over nested binary expressions with an explicit stack. It covers both operands: B restricted to one node kind.
- **Clang (libclang).** The cursor visitor was made "data recursive" for deeply nested binary operators.
- **Tint (WGSL).** The resolver collects expression nodes by depth-first search with an explicit worklist, to avoid stack overflows.
- **CPython.** The maintainers discussed the same problem: a recursion-depth guard is too low for long operator chains, and a real fix needs an explicit stack or a different representation for chained operators.

Growing or switching the stack:
- **rustc.** `ensure_sufficient_stack` uses `stacker::maybe_grow` to continue on a newly allocated stack segment below a red zone. That is D, without needing a thread.
- **Go and GHC** grow stacks at runtime.

Continuations for deep stacks:
- **Stopify** (Baxter, Nigam, Politz, Krishnamurthi, Guha, PLDI 2018) simulates deeper stacks than browsers provide by capturing first-class continuations.
- **Pettyjohn et al.,** "Continuations from generalized stack inspection" (ICFP 2005), and Loitsch, "Exceptional continuations in JavaScript" (2007), capture by throwing an exception and saving each frame on the way out. This is C.
- **Quasar, Kilim and JavaFlow** also capture with exceptions on the JVM, but rebuild the native stack on resume.
- **Always-on-heap CPS:** Kotlin's `DeepRecursiveFunction`, Scala's `TailCalls`, cats `Eval`. `dotty-cps-async` would let step code be written in direct style, but the compiler can't easily depend on a macro library while bootstrapping.

Worklists in type inference: GHC's constraint solver (OutsideIn(X)) and rustc's obligation fulfilment (`ObligationForest`) use worklists for *constraints*, not for traversing trees. They are relevant to completer chains, not to how deeply trees nest.

## Spine spike (Typer only)

Branch commit "Spike: spine loop in Typer". It covers `Select` qualifiers, `Apply` function parts, and term `InfixOp`s (which desugar to `Apply(Select(l, op), r)`).

Refactoring, with behaviour unchanged at the recursive call sites:
- `realApply` is split into `realApplyProto` (pre) and `realApplyRest`/`typedApplyFinish` (post).
- `typedSelect`'s term case is split at `typedSelectOnTermRest`, and `typedInfixOp` at `typedInfixOpRest`.
- `handleTypeError` and the simplification tail of `typedUnadapted` are extracted.

The loop:
- `typed` switches to `typedSpine` once `ctx.base.recursiveDepth >= -Yspine-threshold` (default 128; -1 disables). This only happens for `Select`/`Apply`/`InfixOp` nodes and only in the frontend `Typer` class itself, not in subclasses.
- `spineFrame` follows the path `typed` → `typedUnadapted` → `typedSelect`/`typedApply`/`typedInfixOp` and builds a frame. It returns `null` wherever that path would do something the frame doesn't model: a source switch, cancellation, `TypedAhead`, a context-function `pt`, pattern or type mode, op-assignment, a function part that isn't a `Select`, or Java.
- On the way up, the loop mirrors `typed`'s exception structure. A `TypeError` inside a link turns that link into an error tree. A `TypeError` from adapting a link is handled by its parent.

Results, `-Xss2m`, nonbootstrapped compiler ([`spine-spike/run.sh`](spine-spike/run.sh)), with `-Ystop-after:typer`:

| input (n terms) | 200 | 400 | 1000 | 3000 | 10000 |
|---|---|---|---|---|---|
| `x + 0 + 1 + ...` before | ok | FUEL | FUEL | FUEL | SOE (parser `checkPos`) |
| `x + 0 + 1 + ...` spine | ok | ok | ok | ok | SOE (parser `checkPos`) |
| `0 :: 1 :: ... :: Nil` before | ok | FUEL | FUEL | SOE (parser `checkPos`) | SOE |
| `0 :: 1 :: ... :: Nil` spine | ok | ok | ok | SOE (parser `checkPos`) | SOE |
| `sb.append(0).append(1)...` before | ok | FUEL | FUEL | parser gives up | parser gives up |
| `sb.append(0).append(1)...` spine | ok | ok | ok | parser gives up | parser gives up |

FUEL is `RecursionOverflow` at the default `-Xmax-fuel` of 550. Each link of a chain costs 2 fuel, so today chains of about 275 terms fail. In the spine runs, every input that gets through the parser also gets through the typer.

What overflows next:
- **Parser:** `Positioned.checkPos` runs on every unit, because `Config.checkPositions` is an `inline val true`. Long `else if` ladders and `.append` chains hit a parser limit at about 1200 links and report spurious syntax errors.
- **Error reporting:** `SyntaxHighlighting`'s tree traversal overflows while rendering an error that points into a long chain.
- **After the typer:** with the full pipeline, the first failure is now PostTyper's `TreeMap` recursion (`PostTyperTransformer.transform`/`transformSelect`), at about 400 terms with a 2 MiB stack. It used to be masked by the typer's fuel limit. Pickling, the MegaPhases, Erasure and the backend come after it.

Equivalence check: `testCompilation` with the default threshold forced to 0, so that every `Select`/`Apply`/`InfixOp` in the suite goes through the loop. 88 of 89 suites pass. The two failures are neg tests that check *where* `RecursionOverflow` fires:
- `tests/neg/26888.scala` sets `-Xmax-fuel:50`, and its error moves from `f10` to `f12`;
- the reported position and the list of operations in `tests/neg/i10605b.check` shift by one frame.

Both are expected: loop links consume no fuel and record no "typing" operation. Both pass at the default threshold.

Not done in the spike:
- `If.elsep` and `Block.expr` spines;
- `Erasure.Typer`, `ReTyper` and `InlineTyper`;
- the phases after the typer;
- a perf A/B. The fast path only adds a field load and a compare in `typed`, but that hasn't been measured.
- Loop links also bypass `trace` and `Stats.record("typed …")`.

## Recommendation

Implement **A for the frontend Typer, MegaPhase, TASTy and the backend**, for the few spine kinds the frame histogram identifies. Use **D as the catch-all past the threshold** for everything A doesn't cover. Use the explicit-stack form of B only for the generic traversals (`TreeMap`, `TreeAccumulator`, `TypeMap`), where the code is already uniform. Don't CPS-convert Typer: the toy shows B covers more, but in dotc that coverage would be paid for by rewriting `Applications` and the speculation machinery into continuations. Park C, and revisit it only if D proves unacceptable (cancellation, the cost of each hop, tooling).

## Plan

1. TODO: Frame histogram. Take stress inputs (operator chains, `::` lists, `else if` ladders, nested calls, `val` chains, deep `given` derivations), compile them with a small `-Xss`, and histogram the frames on the overflowing stacks. The result is the set of recurring cycles, plus how much of the depth A could absorb.
2. TODO: The two-limit split in `handleRecursive`, D behind a flag, and a threshold-0 mode for the differential runs.
3. DONE (spike): A for `Select`/`Apply`-fun/`InfixOp` in the frontend Typer, with `testCompilation` at threshold 0 (see Spine spike). TODO: `If.elsep`, `Block.expr`, and a diff of TASTy/bytecode at threshold 0 against a normal run, beyond pass/fail.
4. TODO: PostTyper (`TreeMap`), then the same for MegaPhase, `TreeUnpickler` and `TreePickler`, `Erasure.Typer`, and `genLoad`. Also the parser's `checkPos` and its limit on chain depth, and `SyntaxHighlighting`.
5. TODO: Perf A/B on the compiler benchmarks: the feature on, off, and forced to threshold 0.

Future work: a user-facing `@stackSafe` annotation (option C) for user code.
