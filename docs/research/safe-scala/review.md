# Safe Scala: summary and critical review

Reviewed 2026-10-21 against scala3 `main` (a98b104aaa) and the released 3.10.0 compiler. Probe code is in `probes/Probe.scala`.

## What it is

"Safe Scala" is the marketing name for `import language.experimental.safe`, a restricted dialect of Scala 3 layered on capture checking. It exists for one use case: running LLM-generated code against a typed capability API instead of letting the agent call tools directly. The claim is that if agent code type-checks in safe mode, it can only perform the effects that were handed to it as capability values, and classified data can only flow through functions whose types prove they are pure.

Sources: the EPFL paper *Tracking Capabilities for Safer Agents* (arXiv 2603.00991, Odersky, Zhao, Xu, Bračevac, Pham; best paper at CAIS '26), the [TACIT](https://github.com/lampepfl/tacit) MCP server that implements it, the reference page `docs/_docs/reference/experimental/capture-checking/safe.md`, and VirtusLab's [introduction](https://virtuslab.com/blog/scala/safe-scala-an-introduction). Odersky keynoted Scala Days 2026 on it and there is a workshop on building an agent harness with it.

## Mechanism

Safe mode is small. It is a language import that sets `CompilationUnit.safeMode`, which:

- turns on capture checking, mutation tracking and explicit nulls;
- rejects `asInstanceOf`, `@unchecked*` annotations, `caps.unsafe`, `@assumeSafe` itself, and `$` in identifiers;
- runs `SafeRefs.checker` before PostTyper. Every reference to a *static* symbol must resolve to something compiled in safe mode or carrying `@assumeSafe`; anything carrying `@rejectSafe` is an error. Non-static selections (`x.foo` where `x` is a local or parameter) are not checked at all; the design relies on capture checking for those.

The standard library allowlist is hand-maintained in `compiler/src/dotty/tools/dotc/cc/SafeRefs.scala`: whole packages (`scala`, `scala.runtime`, `scala.util`, `scala.collection.immutable`, `scala.math`, `java.time`, `java.util.stream`, ...) plus a list of `java.lang` classes, with a short member-level denylist (`Predef.println`, `Class.forName`, `UUID.randomUUID`, the `scala.runtime.*Ref` boxes, ...).

Trusted library code is written in ordinary Scala, annotated `@assumeSafe`, and is expected to do its own runtime checks (path roots, host allowlists, command allowlists). Exceptions are explicitly not tracked; the harness wraps execution in `Try`.

## Status

- Landed on `main` 2026-02-25 (#25307); shipped in 3.9.0 and 3.10.0. The import compiles on stable 3.10.0 with no `-experimental` flag, so "nightly only" in the April blog post is stale.
- About 26 commits touching it since, almost all by Odersky; the allowlist has been adjusted by hand several times ("tune", "tweak", "assume safe X").
- 20 or so safe-mode test files, all small.
- No soundness argument for safe mode. The paper says one "can in principle be established formally".
- TACIT: 125 stars, Apache-2.0, pins Scala nightlies, and ships its own regex-based `CodeValidator` as the *default* protection, with safe mode optional.

## What I verified

Compiled and ran a probe in safe mode with 3.10.0. The three sanity lines (`System.getenv`, `Console.println`, `asInstanceOf`) were rejected as documented. Everything below compiled and ran:

| Probe | Result |
|---|---|
| `scala.util.Properties.envOrNone("HOME")`, `propOrNone("user.dir")` | returns the values; reads any env var or system property |
| `scala.runtime.LazyVals.getOffset(classOf[String], "hash")` | returns 12; `LazyVals.CAS`/`setFlag`/`objCAS` are `sun.misc.Unsafe` wrappers, so raw memory writes are reachable (not executed in the probe) |
| `scala.runtime.coverage.Invoker.invoked(id, dir)` | appends to a file in any directory the JVM can write |
| `new Exception(s).printStackTrace()` | writes to stderr |
| `IntStream.range(0,100).parallel().sum()` | runs on ForkJoin threads |
| `new Exception().getStackTrace` | exposes harness class and file names |

More importantly, the last three are accepted inside a function of type `String -> String`, i.e. one the type system calls pure. A `Classified[String]` with a `map(f: T -> U)` like TACIT's leaked its value to stderr and, byte by byte as coverage ids, to `/tmp/safeprobe-leak`. That is exactly the information-flow property the paper's RQ1 measures.

TACIT's `CodeValidator` regexes do not mention `Properties`, `printStackTrace`, `coverage`, or `LazyVals`, so both layers pass. The file write also bypasses TACIT's `requestFileSystem(root)` containment, since it never goes through the library.

None of this is a capture-checking bug. It is the allowlist: whole-package `@assumeSafe` on `scala.runtime` and `scala.util`, and class-level `@assumeSafe` on `Throwable`, with no audit of members. These are easy to fix individually, which is the point: the mechanism makes it easy to be wrong quietly.

## Critique

**1. "Pure" means "no tracked capability", not "no effect".** Capture checking only sees capabilities that libraries declare. Every Java method, and every Scala library method that is not capture-checked against a capability, is pure by fiat. Safe mode's guarantee therefore reduces to: the allowlisted surface has no observable effects. That is a manual, deny-by-exception audit of thousands of members, maintained by one person, with a JDK that grows new members every release. The probe shows the audit is already incomplete in a shipped release. The blog's "once a Safe Scala program passes compilation, it is guaranteed to execute safely" is not true today.

**2. The TCB is the Scala compiler.** The trusted base is dotc with capture checking, an experimental feature with known open soundness issues, plus the `@assumeSafe` libraries. Every type-soundness bug in dotc becomes a sandbox escape. Compared with seccomp, Landlock, WASI or a separate process, that is an enormous and fast-moving boundary. The paper acknowledges this and recommends OS sandboxing as well, which is right, but then the honest claim is "defense in depth that catches a class of information-flow bugs sandboxes cannot see", not "a safety harness".

**3. Allowlist granularity is the wrong shape.** `SafeRefs` allows at package or class level and denies members. For `java.lang.Class` it lists ~60 excluded methods, with duplicates (`cast` twice), and any JDK addition is allowed by default. `Throwable` is allowed wholesale even though `printStackTrace` is a console effect and `setStackTrace` is state mutation. `scala.runtime` is allowed wholesale even though it is precisely the package that wraps `Unsafe`. A deny-by-default member allowlist, generated and diffed against the actual library, is the only form of this that can be reviewed.

**4. Exceptions and control effects are an admitted hole papered over with `Try`.** That is fine for integrity but not for confidentiality: exception messages and stack traces are a channel, and `Throwable` is reachable without any capability. Timing and termination channels are declared non-goals, which is reasonable, but then the "100% security" headline needs that qualifier next to it.

**5. Evaluation is thin for a security claim.** RQ1 uses an in-house benchmark of 12 tasks times 10 attacks on two models, plus a port of AgentDojo compared against CaMeL's published numbers. There is no adversarial evaluation of the harness itself, no fuzzing of the allowlist, no red team. "Blocks every injection except one" is a statement about prompts the models happened to produce, not about what the type system admits. A few hours with the allowlist produced the escapes above.

**6. Soundness is unproven for the thing that matters.** The box calculus and System Capybara proofs cover the core calculus. Safe mode's extra restrictions and their interaction with Java interop, inlining, macros (`inline` calls are handled specially in `SafeRefs`, a sign of fragility), pattern matching, and `@assumeSafe` have no formal treatment. "In principle, like RustBelt" is a research programme, not a property.

**7. Ergonomics and adoption.** Agent code cannot print, cannot use `scala.util.control`, `scala.concurrent`, `scala.io`, `collection.mutable` statics, `java.time.format`, or `Using`. LLMs have little training data on capture-checked Scala; the paper reports up to 7.9% of snippets needing retries. Every deployment needs Scala experts to write `@assumeSafe` facades and to audit them, and those facades carry the real security burden (path containment, host allowlists). Odersky and VirtusLab both say this; it limits the audience to shops that already have Scala expertise and an agent product.

**8. Positioning.** The real competitive claim is sound: Scala 3 is the only production language with statically tracked capabilities, so it is the natural vehicle for this experiment, and a concrete agentic use case is probably the best thing that could happen to capture checking's path to stabilisation. The risk is the reverse: capture checking's reputation now rides on a security product being pitched before its allowlist has been audited. CaMeL-style dynamic taint tracking in a tiny interpreter has a far smaller TCB and no language-lock-in; WASM sandboxes make missing capabilities nonexistent rather than checked. Safe Scala's distinct value is purity-typed data transformations over classified values. It should be sold as that, and as a layer, not as the harness.

## Recommendations

1. Treat `SafeRefs` as a security surface: deny-by-default at member level, generated from the library, with a checked-in snapshot and a test that enumerates every allowed member. Immediately reject `scala.runtime.LazyVals`, `scala.runtime.coverage`, `Properties.env*`/`prop*`, `Throwable.printStackTrace`/`setStackTrace`, and audit the rest of `scala.runtime` and `scala.util`.
2. Audit every `@assumeSafe` Java class method-by-method; stop allowing whole packages.
3. Add an adversarial test suite (a `tests/neg-custom-args/captures/safemode-escapes/` directory) seeded with the probes above, and invite red-teaming before the next release that advertises the feature.
4. Reword the docs and talks: safe mode is a static information-flow layer to be used *inside* an OS sandbox, with an explicit list of what is not covered.
5. Report the escapes to the maintainers before publishing this review more widely.

## Addendum: the type-system restrictions themselves

Follow-up to the allowlist findings (fixed in 68dd58a7e3). Question: setting the allowlist aside, where does safe mode's *type-level* story leak, and how could it be strengthened? Probes are in `probes/t/*`, one directory each, run on 3.10.0 with a `Classified[T]` whose `map` takes `T -> U`.

### What capture checking gets right

All of these were rejected, with accurate capture sets in the error:

| Probe | Mechanism that caught it |
|---|---|
| assign a captured local `var` from a pure lambda | lambda typed `->{leaked}`, not `->` |
| write to a captured `Array` | lambda typed `->{arr}` |
| `var` in a user class or `object` | "defined in a class that does not extend `Stateful` or `Mutable`" |
| `boundary`/`break` out of a pure lambda | `Label` is tracked; lambda typed `->{local}` |
| launder `String => String` into `String -> String` via a type test | pattern-bound `g` keeps `{g, f}` |

So the core calculus does its job for state and control that is *expressed in capture-checked Scala*. The docs' claim of "no unchecked pattern matches" is delivered by capture checking, not by a separate safe-mode rule.

### What still leaks, and why

| Probe | Result | Root cause |
|---|---|---|
| `val sb = new StringBuilder; secret.map(s => { sb.append(s); s })` | leaks | `mutable.StringBuilder` is not capture-checked, so `append` is pure by fiat; alias reachable through the `scala` package object |
| `val b = java.util.stream.Stream.builder[String](); secret.map(s => { b.add(s); s })` | leaks | Java objects are untracked; `java.util.stream` is allowlisted wholesale |
| `secret.map(s => { scala.util.Random.setSeed(s.hashCode); s }); Random.nextLong()` | leaks 32 bits | two gaps compound: `new java.util.Random()` is a Java allocation and therefore pure, so the `^` bound on `class Random(val self: java.util.Random^)` is met by a pure value; and even with a real capability, a top-level `object O extends C(cap)` does not count parent constructor arguments in its capture set (probe `t/M/N2`), so the static is pure |
| `secret.map(s => throw new RuntimeException(s))` | leaks via message | `throw` needs no capability in safe mode (18ea9d309f "Allow throw in safe mode") |
| `java.time.Instant.now()` around a `map` that spins on the secret | timing channel with an in-language clock | static Java method, pure by fiat |

Exceptions deserve a sharper statement than the docs give. The docs justify untracked exceptions by pointing at VM-generated ones (division by zero, stack overflow) and say the harness wraps in `Try`. But VM exceptions carry agent-controlled payload too: `Integer.parseInt(secret)` puts the secret in the `NumberFormatException` message, `arr(secret.length)` puts a number in the `ArrayIndexOutOfBoundsException`. And conditional throw or non-termination inside a pure callback is a 1-bit oracle per call regardless. "Capabilities are barred from exception fields" protects capabilities, not data.

The unifying diagnosis: **capture checking's purity is sound for capture-checked Scala and vacuous for everything else.** Safe mode adds a static-reference allowlist on top, but that is a *name-level* filter. Once a value has been obtained through an allowed static entry point, nothing about the instance is constrained. Every leak above is an untracked mutable cell or an untracked channel reached through an allowed name.

### Ways to strengthen

1. **Fail closed at the foreign boundary.** In safe mode, treat instances of classes that are not capture-checked (Java classes, and Scala classes compiled without the `CaptureChecked` TASTy attribute) as `Mutable` with every method an `update`, unless the class is on a small `@assumeImmutable` list (`String`, boxed numbers, `BigInteger`, `java.time.*` values, `Optional`, ...). Results of foreign methods whose type is foreign-mutable get a fresh `^`. Then `sb.append`, `b.add`, `Matcher.find`, `java.util.List.add` all make the enclosing lambda impure, and `Collectors.toList()` yields a tracked value. This is the same allowlist shape as `SafeRefs` but per class, much smaller, and wrong in the safe direction. It is also the place where the existing `Mutable`/`update`/`freeze` machinery already does the work; what is missing is the default for foreign classes.

2. **Statics with tracked state are not pure.** Direct fields of an object are already counted (`object O { val r: Cap }` makes `O.r` a tracked path), but a capability passed to the parent constructor of a top-level object is not: `object O extends C(new Cap)` yields a pure `O.r` (probe `t/M/N2`), while the same object declared locally, or `val o = new C(new Cap)`, is tracked. `capturesImpliedByFields` excludes class parameters because `new` sites get capture refinements; a module parent has no `new` site. This is a capture-checking gap independent of safe mode, and it is the shape of `object Random extends Random(new java.util.Random())`. Fix: treat a module's parent constructor arguments as contributing to the module's capture set, and pickle the result so TASTy-loaded objects carry it.

3. **Make pure callbacks on classified data total.** Two layers. In the type system: enable `saferExceptions` under safe mode so an explicit `throw` requires `CanThrow`, which a `T -> U` callback cannot have. In the library: `Classified.map` catches `Throwable` and returns `Classified[Try[U]]` (or rethrows a fixed, message-free exception), so neither payload nor the throw/no-throw bit reaches the agent. Non-termination and resource exhaustion remain, and should be stated as the residual channel rather than left under "exceptions are impractical to track".

4. **Split `@assumeSafe` into "callable" and "pure".** One bit currently means both "agent code may reference this" and "capture checking may treat it as effect-free". `scala.util.Random`, `Predef`, and `java.util.stream` are reasonable to make *callable*; none of them are *pure*. With 1 in place, the second bit becomes a per-class `@assumeImmutable` and the first stays a per-member allowlist.

5. **Take the clock away.** Reject `java.time.Clock`, `Instant.now`, `LocalDateTime.now` and friends in safe mode; the paper declares timing out of scope, but there is no reason to hand the agent a stopwatch. (Allowlist-level; included here for completeness.)

Of these, 1 and 2 are the ones that change the shape of the guarantee: after them, safe mode's purity would mean "no observable effect except through a capability, or through an explicitly audited immutable class", which is a claim that can be reviewed. 3 is what makes `Classified` an information-flow boundary rather than a bulk-leak deterrent.
