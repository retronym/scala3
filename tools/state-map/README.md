# State map

A model of the mutable state of dotc's classes, extracted from their TASTy, and a class diagram of it. Each field is sorted by the kinds of `docs/_docs/internals/interactive-multi-reader.md` §3 (A per-request scratch, B lazy facts with a black hole, C caches of derived values, D interning and counters, E trees) and by the discipline that protects it from concurrent readers (§4.4, §8), so that the shareable state of the presentation compiler can be read as one picture, and a change to it shows as a diff.

- `StateMap.scala`: the extractor, a scala-cli script. It reads every `.tasty` under `dotty/tools/dotc` of the non-bootstrapped compiler with `scala.tasty.inspector.TastyInspector`, collects the `var`s and the `val`s holding mutable collections of the classes in the root package, `core`, `typer`, `interactive`, `cc`, `transform`, `ast`, `util`, `inlines` and `quoted`, and every read and write of them with what encloses it. It writes `state-map.json` and prints a table of kind × protection, the fields no kind rule places, and the validation.
- `expected.tsv`: what §8's I91 table and I92–I96 say of each cache. The extractor reports each field the rules place differently, and checks the `Stamped` call sites found in the source text against the fields it puts under `Stamped`.
- `index.html`: the diagram, reading `state-map.data.js` (the same JSON as a script, so that the page works from `file://`). It loads elkjs from jsDelivr for the layout and draws plain SVG.
- `run.sh`: compiles the compiler with sbt and runs the extractor.
- `library/src/scala/annotation/internal/{scratch,lazyFact,cache,interned,treeState,guardedBy,confined,publishedBy,benignRace,featureArea}.scala`: the annotations (below).

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.12+1.1-tem tools/state-map/run.sh
```

The extractor is a scala-cli script beside the sources rather than an sbt project or a test: it needs the TASTy inspector of the reference compiler (3.10.0, the version that compiles the non-bootstrapped classes), and nothing in the build should depend on it. `local/` is ignored by git, so it lives here.

## Rules

An annotation on the field (below) takes precedence over every rule; otherwise each field's kind, protection and reach are set by the first rule that matches, listed in this order in the JSON's header with the same text. Rules read the TASTy (owner, base classes, declared type, annotations, the trees around each access), never a guess: a field no kind rule matches is `unclassified`, and one with no access outside initialisation is `unknown`. `evidence` says what a protection rests on: `lexical` (the accesses), `annotation`, `type`, `owner`, or `asserted` (a lock held further up the stack, which the access does not show; taken from §8).

Kind (after the annotations `@scratch`, `@lazyFact`, `@cache`, `@interned`, `@treeState`):

- `A.reader`: a field of `dotc.Reader`.
- `A.traversal`: a field of a `TypeMap`, `TypeAccumulator`, `VariantTraversal`, tree map or accumulator, `TypeComparer` or `ConstraintHandling`: one traversal's or one reader's.
- `A.iterator`: a field of an `Iterator`.
- `B.denotation`: `myFlags`, `myInfo`, `myAnnotations`, `myParamss`, `myPrivateWithin`, `myCompanion`, `myDecls`, `mySourceModule`, `myModuleClass` of a `SymDenotation`.
- `B.validity`: `myValidFor`, `nextInRun` of a denotation.
- `B.completer`: a field of a `SymDenotations.LazyType` (completers, symbol loaders).
- `B.inherited`: a field of an `InheritedCache` implementation.
- `B.lazy`: a field of `LazyRef` or of a lazy annotation.
- `B.unpickler`: a field of a class in `core.tasty`, `core.classfile`, `core.unpickleScala2`.
- `B.scope`: a field of `MutableScope`.
- `C.stamped`: the field a `Stamped` extension (`cached`, `cachedIf`, `peek`) is called on: a value and its stamp as one immutable object.
- `C.stamp`: a stamp by `StampedCachesTest`'s rule (declared `Period`, `RunId`, `Validity`, or named `…Period`, `…RunId`, `valid…`, `checked…`).
- `C.publish`: named by a `VarHandle` in `util/Publish.java`, or accessed in a method that calls `util.Publish`.
- `C.packed`: on a type, denotation or symbol, `@volatile` and declared a tuple or a class of two or more `val`s.
- `D.designator`: `NamedType.myDesignator`, part of the interned key.
- `C.type-memo`: any other field of a `Type` other than a `TypeVar` (types are hash-consed values).
- `C.lazy-init`: a `private var myX: T | Null = null` read and written in one method (the pattern of the I91 audit).
- `C.name`: named `…cache…`, `cached…`, `last[A-Z]…`, `my…Hash`.
- `D.interner`: a field of `WeakHashSet`, `ConcurrentWeakHashSet` or a class in `core/Uniques.scala`, or declared one.
- `D.names`: a field of a class in `core/Names.scala`.
- `D.counter`: named `_next…Id`, `nextId`, `…SymId(s)`, or a field of a `FreshNameCreator`.
- `E.tree`: a field of a tree, `Positioned`, an attachment container or link.
- `E.session`: a field of `InteractiveDriver.Session`.
- `E.unit`: `CompilationUnit.tpdTree`, `untpdTree`.

Protection (after the annotations `@guardedBy`, `@confined`, `@publishedBy`, `@benignRace`, then the `-Ycheck-reentrant` markers `@sharable` → static-safe and `@unshared` → single-threaded, on the field or its owner):

- `P.thread-local` → thread-confined: declared `ThreadLocal`.
- `P.confined-owner` → thread-confined: kind A.
- `P.post-typer` → post-typer: reach `post-typer`.
- `P.publish-handle` → Publish: named by a `VarHandle` in `Publish.java`.
- `P.packed` → packed-volatile, `P.volatile` → volatile: `@volatile`, packed as for `C.packed` or not.
- `P.concurrent-type` → concurrent: a `java.util.concurrent` or `scala.collection.concurrent` type, an atomic, `ConcurrentWeakHashSet`, `SyncEqHashMap`.
- `P.on-base` → concurrent: initialised by `Sharing.newMap`, `newEqHashMap` or `new…Uniques`.
- `P.asserted-completion` → completion-lock: kinds `B.denotation`, `B.completer`, `B.unpickler`, written under `lockAndComplete` or `Sharing.claim` further up the stack.
- `P.stamped`: declared `Stamped[K, T]`: a stamp and a value as one immutable object, so every read and store of the field, a reset to `Stamped.Empty` included, is of a whole pair.
- `P.access`: by the accesses outside initialisation and resets. Each access is, innermost first: the field a `Stamped` extension is called on → Stamped; in `synchronized` (or its receiver, `x` of `x.synchronized`), `Sharing.serialised` or a `Sharing.Guard`'s `locked` → synchronized; in the `op` of `Sharing.claim` or `claimAt`, or the `compute` of `Sharing.once` → completion-lock, or root-claim when the call's `ClaimKind` says `lockWhenSerialised = false`; in the `op` of `CompletionLock.locked`, or in a method that takes a `Sharing.Holds` → completion-lock; in the `op` of `CompletionRoots.locked` → root-claim (an inline method that passes its parameter to one of these protects that parameter too); in a method that calls `util.Publish` → Publish; in the else branch of `if x.concurrent`, `if x.parallel` or `if Config.c && x.concurrent` → batch; in a private or local method, the one protection all its calls have; else none. The field is that one protection, `batch-only`, `partial:P` (some accesses P, some none), `mixed:P,Q`, or `unknown`.

Reach: `post-typer` for owners in `cc`, `backend`, `transform` (but `SetRootTree`, `CookComments`), and phases other than the interactive compiler's; `typer` otherwise.

Not modelled: the internals of the mutable collection classes in `util` (modelled where a collection is held), synthetic fields, and `lazy val`s.

Features, by package and class name: `cc`, `match-types`, `inline-macros`, `explicit-nulls`, `tasty-classfile`, `scala2`, `interactive`, `core`. `role` is `cache` for kind C and `semantic` otherwise.

## Annotations

Naming conventions and types carry the rules a long way, but not all of it: a field of a `ContextPool` is per-reader only because the pools moved to `Reader`, a completer writes `myInfo` under the completion lock taken further up the stack, and `LambdaParam.myVariance` races by design. These are facts about the design, so they belong on the field. Ten annotations in `scala.annotation.internal`, beside `@sharable` and `@unshared`, say them. TASTy keeps them, so the extractor reads them at no cost to the compiler at run time.

| annotation | says |
|---|---|
| `@scratch` | kind A: per-request scratch |
| `@lazyFact` | kind B: a lazy fact with a black hole |
| `@cache` | kind C: derivable, dropping it costs only time |
| `@interned` | kind D: an interning table or counter |
| `@treeState` | kind E: a tree's or a session's tree state |
| `@guardedBy("completionLock" \| "rootClaim" \| "writer" \| "this" \| "<field>")` | every access holds that lock: the completion lock (`Sharing.claim` on a key whose `ClaimKind` takes it when readers are serialised), a root's claim (`Sharing.claim` on one whose kind does not), a write action of the interactive driver (which runs with no reader), or a monitor |
| `@confined` | one thread reaches the owner |
| `@publishedBy("Publish" \| "volatile" \| "packed" \| "init")` | writes are published by `util.Publish`, one volatile value, an immutable value packed into a volatile field, or are made only before the owner is shared |
| `@benignRace("why")` | readers may race on it: any writer stores the same or an equal value |
| `@featureArea("cc")` | a feature area the package and class name do not show; features stay inferred otherwise |

An annotation on a class applies to each of its fields that does not say otherwise. In the JSON, `kindSource` and `protectionSource` are `annotated` or `inferred`; the rules' own answer is kept as `inferredKind` and `inferredProtection`, and `annotationCheck` compares the two: `agrees`, `disagrees` (for an annotated P the rules find `partial:P`, so some access lacks it), or `unverified` (the rules find no protection to compare with, as for a lock held further up the stack). The diagram marks an annotated field with `@` and a disagreement with `≠`, and its `source` chips select annotated, marker-only and inferred fields. The validation against §8 checks the rules' answer, not the annotations.

Seven fields are annotated as exemplars, one per kind and protection where the rules cannot place them: `ContextPool.inUse` (`@scratch @confined`), `Namer.myNestedTyper` (`@lazyFact @publishedBy("volatile")`), `Symbol.lastDenot` (`@cache @publishedBy("Publish")`, which the rules dispute: eleven reads outside the publishing methods), `LambdaParam.myVariance` (`@cache @benignRace`), `NameKinds.uniqueNameKinds` (`@interned`, beside its `@sharable`), `InteractiveDriver.myOpenedTrees` (`@treeState @guardedBy("myOpenedTrees")`), `CompilationUnit.needsCaptureChecking` (`@featureArea("cc")`).

`@sharable` and `@unshared` are a different axis: they tell `-Ycheck-reentrant` (`transform/CheckReentrant.scala`) that a var reachable from a static object is safe across compiler instances, which says nothing about readers sharing one `ContextBase`. Nothing in the build or CI passes `-Ycheck-reentrant` today; running it in CI is a cheap side win, and its traversal (vars reachable from static owners, cut at annotated paths) is a starting point for the checker below.

Prior art: JCIP's `@GuardedBy`, `@ThreadSafe` and `@Immutable` (`net.jcip.annotations`), checked lexically by Error Prone's `GuardedByChecker`, with `@LazyInit` for benign races; IntelliJ's `@RequiresReadLock`, `@RequiresWriteLock` and `@RequiresEdt` (`com.intellij.util.concurrency.annotations`), checked by instrumentation in tests.

A checker, later: the extractor's access classification is most of it. Each `@guardedBy(l)` field fails when an access is neither lexically under `l` nor in a method every call of which is (as `P.access` does now); for a lock held further up the stack (the completion lock around a completer) it needs a method annotation in the manner of `@RequiresReadLock`, `@requiresLock("completionLock")`, checked at the method's call sites the same way, or a runtime assertion under `-Yinteractive-parallel` (`completionLock.isHeldByCurrentThread`) as IntelliJ instruments its tests. `@confined` fails when the owner is reachable from a shared kind, `CheckReentrant`'s reachability over types instead of static roots. Run over TASTy in a test, it turns `annotationCheck: disagrees` into a failure.

`@frozenAfter("completion")` is for state filled while its owner is hidden and frozen once the owner's completion publishes it (a class's scope): the extractor reports it as `frozen-after-completion` and leaves the check to `-Ycheck-frozen-scopes` when the program runs, since no static rule tells a completer's writes from later ones.

The page's **guide** view (`index.html#guide`) documents the extractor, the hazards the map is about and the strategies against them, with each strategy's field count and a filter for it.

## Protection as a capability

A lock held further up the stack is what neither the rules nor an annotation can check at the access. Clang's thread-safety analysis makes it a capability (`REQUIRES(mu)`), and `dotty.tools.sharing` does the same with a context parameter: `Sharing.claim(key)` and `Sharing.once(key)` hand their code a `Sharing.Holds[key.type]`, invariant in the key and made nowhere else (but `Sharing.unguarded(why)`, the escape hatch), and state that only the claim's holder may touch is reachable only through methods that take one. A completion is a source too: `LazyType.complete(denot)` takes a `Holds[denot.type]`, which only `SymDenotation.lockAndComplete0` makes, so a completer run outside a completion does not compile. A `Sharing.Guard`'s state is `Guarded` fields, reached only inside its `locked` with a `Sharing.Monitor[K]`; `Sharing.serialised` and `Sharing.locking` give the same evidence for another object's monitor. The extractor counts a method that takes a `Holds` as completion-lock, and a `Guard`'s fields as synchronized.

Evidence is used where the claim or monitor is the guard: inherited caches, lazy references and annotations, the bring-forward, a scope's one writer, lazy TASTy reads, the run's suppressions, suspension and late files, the docstrings and comment expander, and the base's sources and files. The denotation ring (`nextInRun`) is the counter-example: only phases after typer write it, from 53 sites, so evidence would spread to every phase; it is linked in by one store after a release fence instead.

## Coverage

Of the 1,051 fields in the packages the extractor reads, 391 are annotated and the rules place the rest; every one has a protection. Two are reported `partial`: `Run.suppressions.suspended` and `ContextDocstrings._docstrings`, whose unguarded reads take the monitor's evidence from `Sharing.unlocked(why)` on purpose (the REPL handing over to a new run, and the docstrings read after the typer or by a write action), which the extractor does not yet recognise. The races the audit found are in §8 of interactive-multi-reader.md. The batch compiler pays a volatile read on lazy annotations, `tpdTree` and the line-index cache, and an uncontended monitor per fresh name and per docstring.

## The diagram

The default view has a node for each of Symbol, SymDenotation, ClassDenotation, the denotations, NamedType, AppliedType, the other types (one node, "Type families", until expanded), Scope, ContextBase, Run, Reader, TreeUnpickler, the interactive Session, the inherited caches, the interners and LazyType. A row per field: the left bar is its kind, the right column its protection, and a red row has none at some access. An edge from a class to another is the fields whose declared types name it. Click a header to add the classes its fields point to (alt-click: and those that point to it), shift-click to drop it; click a row for its source on GitHub, hover it for its rules and the accesses that have no protection.
