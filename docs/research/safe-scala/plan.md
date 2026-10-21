# Safe mode: closing the foreign-state gap

Status: design draft, 2026-10-21. Nothing implemented. Builds on the review in `review.md` and the probes in `probes/t/`. Written with LLM assistance (Claude Code); all probes were run against the released 3.10.0 compiler.

## Problem

Safe mode promises that a function typed `A -> B` has no observable effect. Capture checking delivers that for state and control expressed in capture-checked Scala: local vars, arrays, `var` fields, `boundary` labels and type tests are all charged correctly (probes `t/B_*`, `t/C_*`, `t/A_*`, `t/J_*`, `t/E_*`, `t/F_*`). It delivers nothing for state that lives in classes capture checking never looked at. A `String -> String` may append to a `StringBuilder`, add to a `java.util.stream.Stream.Builder`, reseed `scala.util.Random`, or `throw` a payload, and a `Classified[String].map` built on `->` leaks its value through each of these (`t/D1_*`, `t/D2_*`, `t/I_*`, `t/H_*`).

Two mechanisms produce this, and they compound:

1. **Foreign allocation is pure.** `new java.util.Random()` and `new StringBuilder` have empty capture sets. Non-capture-checked classes (Java, and Scala classes compiled without the `CaptureChecked` flag) get "fluid" member types in `Setup`, which is the right thing for migration and the wrong thing for a sandbox. Every such object is a mutable cell the type system cannot see, and the `^` a library author writes on a parameter such as `class Random(val self: java.util.Random^)` is only an upper bound, satisfied by the pure value the Java constructor returns.

2. **Module parents drop constructor-argument captures.** `capturesImpliedByFields` deliberately excludes class parameters because `new C(args)` sites get capture refinements instead. A top-level `object O extends C(new Cap)` has no `new` site, so `O.r` comes out pure even when `Cap` is a real `SharedCapability` (`t/M/N2`). The same object declared locally, or `val o = new C(new Cap)`, is tracked (`t/M/N6`, `t/M/N1`), and a direct field `object O { val r: Cap }` is tracked (`t/M/N3`). `object Random extends Random(new java.util.Random())` is this shape exactly.

Fixing only the allowlist (68dd58a7e3 closed the specific members found) does not change the guarantee. Every allowlisted Java package and every non-cc Scala class remains an unbounded source of invisible cells. The guarantee has to come from a default that fails closed.

## Goals

- In safe mode, a value of a class capture checking has not verified is treated as mutable state unless someone has audited the class and said otherwise.
- A static whose state is tracked is a tracked path, whether the state arrived through a field or a parent constructor argument, and whether the object was compiled in this run or loaded from TASTy.
- No change to the meaning of programs outside safe mode for goal 1. Goal 2 is a soundness fix and applies everywhere capture checking is on.

Non-goals: exceptions, non-termination, timing (separate proposal: `saferExceptions` under safe mode plus a total `Classified.map`), and the allowlist itself.

## Key decisions

### D1. Foreign classes are `Mutable` by default in safe mode

A class is *foreign* when it is Java-defined, or Scala-defined without the `CaptureChecked` flag. In a safe-mode compilation unit, a foreign class is treated as if it extended `caps.Mutable` with every member an `update` method, unless it is on an immutability list (D2).

Consequences, using machinery that already exists for `Mutable` and for the built-in `Array` treatment (`isArrayUnderStrictMut` is the precedent for "treat this class as `Mutable` without changing its declaration"):

- `new Foreign(...)` yields a fresh exclusive capability, like `new Array[T](n)` under strict mutability.
- Calling a member on a foreign receiver is an update and requires an exclusive capture of the receiver. A closure doing so captures the receiver and is impure.
- A foreign-typed result of any method call is fresh. `Collectors.toList()` and `Stream.builder()` return cells; the cells are tracked.
- A foreign-typed field of a capture-checked class contributes to `capturesImpliedByFields`, so a Scala wrapper around a Java object is stateful whether or not its author wrote `^`.
- Foreign-typed parameters of library methods keep their declared types. A library `def f(sb: StringBuilder)` accepts a `StringBuilder^` from safe code because `^` is the default for a `Mutable` type and the parameter type is read as a bound. Read-only access (D3) covers the common case of passing an immutable view.

The rule is applied in safe-mode units only. Trusted `@assumeSafe` libraries keep today's behaviour, since they are already outside the guarantee and already carry a proof obligation. That keeps the change out of the migration story for ordinary capture checking.

Why `Mutable` and not merely "fresh `^`": freshness alone would make `val sb = new StringBuilder` tracked but would not make `sb.append` an exclusive use, so a read-only capture of `sb` (`->{sb.rd}`) could still mutate. Treating members as `update` gives the right distinction for free and lets D3 relax it.

### D2. An audited immutability list, per class, deny by default

A foreign class escapes D1 only if named in an immutability list: `String`, the boxed primitives, `BigInteger`/`BigDecimal`, `java.time` value types, `Optional*`, `Class` minus its reflective members (already denied), `UUID`, `Locale`, and the Scala library's immutable collections and value classes that are not compiled with capture checking.

Shape: the same place as `SafeRefs` today (annotations added at `init`), but a separate `@assumeImmutable` marker rather than overloading `@assumeSafe`. `@assumeSafe` means "agent code may name this"; `@assumeImmutable` means "capture checking may treat instances as effect-free". Today one annotation means both, which is how `Predef`, `java.util.stream` and `scala.util.Random` became pure.

The list is small because it is per class and because most of the standard library can instead be made genuinely capture-checked over time, at which point it leaves the list. It is reviewable because every entry is a positive claim that someone can falsify by finding a mutating member.

Open point: whether `@assumeImmutable` should be a member-level override as well (an immutable class with one cache-clearing method). Start class-level only.

### D3. Read-only access to foreign values

With D1, `s.length` on a `java.util.List` would be an update. That is too coarse to be usable. Options:

a. Accept it. Safe-mode agent code mostly transforms immutable data; Java collections are rarely needed, and the library facade can hand out Scala views.
b. A member-level `@readOnly` marking in the immutability list for foreign classes that are mostly mutable (`List.size`, `Matcher.group`). This reuses the existing read-only capability machinery (`rd` captures).

Start with (a). Measure on the TACIT facades and the paper's benchmark harness before adding (b).

### D4. Parent constructor arguments contribute to the instance capture set

Findings from the implementation attempt (2026-10-21, probes `probes/t/M/N7`..`N13`, patch `probes/d4-coarse-rule.patch`):

- The gap is not module-specific. `class D extends C(new Cap)` with `val d = new D` gives a pure `d`, and `() => d.r.use()` type-checks as `() -> Unit` (`N7`). `object O extends C(new Cap)` is the same gap (`N2`).
- Mechanism: `markPathFree` charges a path `p.r` only when its root `p` is tracked. That is sound as long as every instance whose fields hold capabilities is itself tracked, which `capturesImpliedByFields` ensures by giving such instances a fresh capability. Refining parameter accessors are deliberately excluded from that computation because the `new C(args)` site refines the instance type with the argument captures. A subclass or module has no such site for the parent's parameters, so the instance is pure and every path through the inherited accessor is silently uncharged. `O.r` and `d.r` *are* tracked references (`N13`); they are just never marked.
- Coarse fix (count inherited refining param accessors in `capturesImpliedByFields`, see the patch): closes `N2` and `N7`, passes the captures suite, and breaks 112 sites in `scala3-library-bootstrapped`, almost all `SeqView`/`IndexedSeqView`/`ArrayBuffer` classes that forward their own refined parameter to the parent constructor. In that pattern the parent's accessor holds a value the subclass's own refinement already accounts for, and the extra fresh capability is pure loss of precision. Not viable.
- Precise fix: the extra captures are exactly the terminal (fresh) capabilities in the rechecked parent constructor applications that are not references to the subclass's own parameters. `recheckClassDef` has `parent.nuType` after `super.recheckClassDef`, so computing this for classes in the current unit is a few lines. The cost is persistence: `new D` in another unit must see the result, so it has to be pickled per class. Options: (a) reuse `@retains` on the class symbol with `caps.any`, making it the class's use set, which `refineConstructorInstance` already maps to a fresh capability at `new` sites, at the price of top-level classes no longer always having empty use sets; (b) a new internal marker annotation consulted by `creationCapset`; (c) fold it into the class's self type capture set, which is already pickled in `ClassInfo`, and derive `creationCapset` from it for classes loaded from TASTy. Order of rechecking within a unit also matters for (b) and (c): `new D` may be rechecked before `D`'s template.

Decision: (a). Implemented as a rule in `recheckClassDef`: the terminal capabilities in the capture set of a rechecked parent constructor application, minus the one implied by the parent's own fields (already in `capturesImpliedByFields`), must be covered by the class's use set. Local classes infer it; a class visible elsewhere has to extend `Capability` or declare it with a `uses` clause, which is the existing `@retains` pickling and matches 1d4acc1572's rule for `any`-typed fields. No new pickled information. Library fallout: one site, `object Random extends Random`, now `uses caps.any`. Note that the pickler runs before the capture checker, so inferred capture sets never cross units; everything cross-unit has to be derivable from declared types and annotations.

Found while verifying: a fully qualified reference to a static capability (`scala.util.Random.nextLong()`, `test.M.c`) was never charged, only the imported or unqualified form was, because only `recheckIdent` marks capability paths and the ident at the root of a qualified reference is a package. Fixed in `recheckSelect`. Without it the `Random` declaration was hollow.

### D5. What stays out

- Making `Random`, `StringBuilder` and friends properly `Mutable` in the library is desirable and independent. D1 is what protects safe code until that happens and what protects it from every third-party library forever.
- No attempt to restrict `throw`, clocks, or termination here.

## Prior art

- Joe-E tamed the Java class library with a per-member allowlist and an `Immutable` marker interface that the verifier trusted; everything not tamed was unavailable. D1 and D2 are that design expressed in capture sets, with the difference that Joe-E could not express "this specific instance is mutable but confined", which `Mutable` plus exclusivity can.
- Rust's FFI boundary: foreign functions are `unsafe` by construction, and safety is reclaimed only by a wrapper that asserts an invariant. `@assumeImmutable` is the wrapper.
- The existing built-in `Array` treatment under separation checking is the in-tree precedent for retrofitting `Mutable` onto a class that cannot declare it.

## Plan

- [x] Allowlist fixes for the members found so far (68dd58a7e3).
- [x] Probes establishing the gaps (`probes/t/`), including the module-parent case with a Scala capability (`t/M/N2`).
- [x] D4: parent constructor captures must be in the class use set (inferred locally, declared otherwise); `object Random` declares `uses caps.any`. Plus the qualified-static-reference fix. Tests: `inherited-param-captures{,-sep}`, `qualified-static-refs`.
- [ ] D1 behind a flag (`-Ycc-foreign-mutable`, implied by `language.experimental.safe`): foreign classes as `Mutable` with all-`update` members in safe units. Reuse the `Array` strict-mutability path.
- [ ] D2: `@assumeImmutable` annotation in `caps`, initial list in `SafeRefs.init`, deny by default. Split the existing `assumeSafe` entries into callable versus immutable.
- [ ] Re-run every probe in `probes/t/` as neg tests under `tests/neg-custom-args/captures/safemode-*`; the `Classified` leaks must fail to compile with capture errors, not allowlist errors.
- [ ] Compile the TACIT library and its facades in this mode; record what breaks and whether D3(b) is needed.
- [ ] Docs: rewrite the "Safe Mode" guarantee paragraph to state the actual claim: no observable effect except through a capability or through a class on the audited immutability list.

## Future work

- D3(b) member-level read-only marks for foreign classes.
- `saferExceptions` under safe mode and a total `Classified.map` (separate note).
- Migrate `scala.util.Random`, `StringBuilder` and the mutable collections to real `Mutable`/`Stateful` declarations so they leave the foreign set.
- Deny-by-default member allowlist generated from the library, replacing the hand-maintained `SafeRefs` lists.
