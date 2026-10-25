# State map

A model of the mutable state of dotc's classes, extracted from their TASTy, and a class diagram of it. Each field is sorted by the kinds of `docs/_docs/internals/interactive-multi-reader.md` §3 (A per-request scratch, B lazy facts with a black hole, C caches of derived values, D interning and counters, E trees) and by the discipline that protects it from concurrent readers (§4.4, §8), so that the shareable state of the presentation compiler can be read as one picture, and a change to it shows as a diff.

- `StateMap.scala`: the extractor, a scala-cli script. It reads every `.tasty` under `dotty/tools/dotc` of the non-bootstrapped compiler with `scala.tasty.inspector.TastyInspector`, collects the `var`s and the `val`s holding mutable collections of the classes in the root package, `core`, `typer`, `interactive`, `cc`, `transform`, `ast`, `util`, `inlines` and `quoted`, and every read and write of them with what encloses it. It writes `state-map.json` and prints a table of kind × protection, the fields no kind rule places, and the validation.
- `expected.tsv`: what §8's I91 table and I92–I96 say of each cache. The extractor reports each field the rules place differently, and checks the `Caching` call sites found in the source text (as `StampedCachesTest` finds them) against the fields it puts under `Caching`.
- `index.html`: the diagram, reading `state-map.data.js` (the same JSON as a script, so that the page works from `file://`). It loads elkjs from jsDelivr for the layout and draws plain SVG.
- `run.sh`: compiles the compiler with sbt and runs the extractor.

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.12+1.1-tem tools/state-map/run.sh
```

The extractor is a scala-cli script beside the sources rather than an sbt project or a test: it needs the TASTy inspector of the reference compiler (3.10.0, the version that compiles the non-bootstrapped classes), and nothing in the build should depend on it. `local/` is ignored by git, so it lives here.

## Rules

Each field's kind, protection and reach are set by the first rule that matches, listed in this order in the JSON's header with the same text. Rules read the TASTy (owner, base classes, declared type, annotations, the trees around each access), never a guess: a field no kind rule matches is `unclassified`, and one with no access outside initialisation is `unknown`. `evidence` says what a protection rests on: `lexical` (the accesses), `annotation`, `type`, `owner`, or `asserted` (a lock held further up the stack, which the access does not show; taken from §8).

Kind:

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
- `C.caching`: read or written in a stamp, value or setter argument of a `Caching` helper.
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

Protection:

- `P.thread-local` → thread-confined: declared `ThreadLocal`.
- `P.confined-owner` → thread-confined: kind A.
- `P.post-typer` → post-typer: reach `post-typer`.
- `P.publish-handle` → Publish: named by a `VarHandle` in `Publish.java`.
- `P.packed` → packed-volatile, `P.volatile` → volatile: `@volatile`, packed as for `C.packed` or not.
- `P.concurrent-type` → concurrent: a `java.util.concurrent` or `scala.collection.concurrent` type, an atomic, `ConcurrentWeakHashSet`, `SyncEqHashMap`.
- `P.on-base` → concurrent: initialised by `Sharing.newMap`, `newEqHashMap` or `new…Uniques`.
- `P.asserted-completion` → completion-lock: kinds `B.denotation`, `B.completer`, `B.unpickler`, written under `lockAndComplete` or `Sharing.exclusive` further up the stack.
- `P.access`: by the accesses outside initialisation and resets. Each access is, innermost first: in a `Caching` stamp, value or setter argument → Caching; in `synchronized` or `Sharing.serialised` → synchronized; in the `op` of `Sharing.exclusive`, `SharingImpl.exclusive`, `CompletionLock.locked` → completion-lock; in the `op` of `Sharing.claimed`, `SharingImpl.claimed`, `CompletionRoots.locked` → root-claim (an inline method that passes its parameter to one of these protects that parameter too); in a method that calls `util.Publish` → Publish; in the else branch of `if x.concurrent`, `if x.parallel` or `if Config.c && x.concurrent` → batch; in a private or local method, the one protection all its calls have; else none. The field is that one protection, `batch-only`, `partial:P` (some accesses P, some none), `mixed:P,Q`, or `unknown`.

Reach: `post-typer` for owners in `cc`, `backend`, `transform` (but `SetRootTree`, `CookComments`), and phases other than the interactive compiler's; `typer` otherwise.

Not modelled: the internals of the mutable collection classes in `util` (modelled where a collection is held), synthetic fields, and `lazy val`s.

Features, by package and class name: `cc`, `match-types`, `inline-macros`, `explicit-nulls`, `tasty-classfile`, `scala2`, `interactive`, `core`. `role` is `cache` for kind C and `semantic` otherwise.

## The diagram

The default view has a node for each of Symbol, SymDenotation, ClassDenotation, the denotations, NamedType, AppliedType, the other types (one node, "Type families", until expanded), Scope, ContextBase, Run, Reader, TreeUnpickler, the interactive Session, the inherited caches, the interners and LazyType. A row per field: the left bar is its kind, the right column its protection, and a red row has none at some access. An edge from a class to another is the fields whose declared types name it. Click a header to add the classes its fields point to (alt-click: and those that point to it), shift-click to drop it; click a row for its source on GitHub, hover it for its rules and the accesses that have no protection.
