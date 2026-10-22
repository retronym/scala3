# C-backend-codegen: Scala 3 JVM backend vs Scala 2.13.x backend

Scratch/repro files: <scratchpad>/work/C-backend-codegen/ (t1..t9). Runtime repros were done with the installed `scalac` 3.10.0 (Homebrew); the relevant code is unchanged at the worktree HEAD (a98b104aaa).

## Pairs

| s3 file | s2 file | port date | later s2 commits (no-merge, since 2017-09) | notes |
|---|---|---|---|---|
| backend/jvm/BCodeBodyBuilder.scala | backend/jvm/BCodeBodyBuilder.scala | initial port ~2017-09 (63754e7298 "Write generic java signatures..."), moved 2019-02-08 ff15f0445b; GenBCode refactor re-port 2022-06-27 fa9648259d | 60 | heavily rewritten (stack tracking, Objects.equals, LoadDestination); many 2.12-era fixes present |
| BCodeSyncAndTry.scala | BCodeSyncAndTry.scala | same | 13 | rewritten (stack stash for try-catch with non-empty stack); logic matches s2 otherwise |
| BCodeHelpers.scala (BCAnnotGen, BCForwardersGen, JMirrorBuilder, BCSignatureGen) | BCodeHelpers.scala | same | 59 | static forwarders are the main divergence (F2) |
| BCodeIdiomatic.scala | BCodeIdiomatic.scala | same | 24 | indy concat, iinc, conformsTo all ported |
| BCodeSkelBuilder.scala | BCodeSkelBuilder.scala | same | 40 | local var start fix 93d1b5cf6c, max param count 051659595b, SyntheticUnit line numbers e4fa961542 all present |
| BTypes.scala, BTypeLoader.scala, KnownBTypes.scala | BTypes.scala, BTypesFromSymbols.scala, CoreBTypes.scala, BTypesFromClassfile.scala | 2019 / re-split 2026-02 (0735e175a9) and 2026-05 (be49502453) | 54 / 47 / 15 | maxValueType Long/Float fix 42bfebf8c2 present |
| CodeGen.scala, GenBCode.scala | CodeGen.scala, GenBCode.scala, PostProcessor.scala | 2022-06-27 fa9648259d, parallelised 2026-09-30 e4d58c37e6 | 16 / 18 / 27 | |
| GenericSignatures.scala, GenericSignatureVisitor.scala | transform/Erasure.scala (javaSig, NeedsSigCollector) | 63754e7298 2017-09-29 in Erasure-era file; moved to backend 0e0d6833a5 2026-05-26 (#26158) | 65 (Erasure.scala; ~10 touch javaSig) | actively reworked in 2026; one remaining divergence (F4) |
| BCodeUtils.scala | opt/BytecodeUtils.scala, analysis/BackendUtils.scala, AsmUtils.scala | 2026-02-16 (optimizer port) | - | EnclosingMethod/InnerClass helpers live here |
| io/FileWriters.scala | ClassfileWriters.scala | 2024-02-14 5eb0845653 (earlier version was in dotc/ and ported from 2018 ClassfileWriters) | 28 | essentially in sync (see F5 for one difference) |
| backend/ScalaPrimitivesOps.scala | ScalaPrimitives.scala | 2019 | 0 meaningful | no semantic s2 commits since 2018 |
| ClassNode1/LabelNode1/MethodNode1.java | MethodNode1/LabelNode1 etc | - | 0 | identical |

## Findings

### F1: `arr(i) = <Nothing-typed expr>` on a primitive array produces a VerifyError (scala/bug#10069 never ported)
- severity: high
- confidence: high (reproduced)
- s3: compiler/src/dotty/tools/backend/jvm/BCodeBodyBuilder.scala:216 (`genLoad(a2)` in the `isArraySet(code)` branch of genArrayOp)
- s2: src/compiler/scala/tools/nsc/backend/jvm/BCodeBodyBuilder.scala (`genLoad(a2, elementType)`); fix commit: 880aec04e8 2016-11-18 "[SI-10069](https://github.com/scala/bug/issues/10069) Fix code gen errors with array updates, Nothing" (scala/bug#10069)
- what: Scala 2 loads the RHS of an array store with the array element type as expected type, so `genLoad` -> `adapt` inserts the ATHROW for a `Nothing$` value (and the following store becomes dead code). Scala 3 calls `genLoad(a2)` without an expected type, so the phantom `scala/runtime/Nothing$` is left on the stack and the following `dastore`/`iastore` fails verification. Any class containing such a method fails to load with `java.lang.VerifyError: Bad type on operand stack` (the whole class, not just the method).
- verified: compiled and ran on Scala 3.10.0: `def f(a: Array[Double]) = { a(0) = throwExpected }` (throwExpected: Nothing), same for `Array[Int]`, and with an extra local (`val v = 42`) -> VerifyError (stack: `'[D', integer, 'scala/runtime/Nothing$'`). Array[String] and `Array[Long] = if (c) 1L else throwExpected` verify fine. Repro: work/C-backend-codegen/t6/V0.scala..V3.scala. `git log --all --grep=10069` in scala3 finds nothing. The second half of the s2 commit (box nativeKind from `fun.symbol.firstParam.info`) was probed with `val x: Any = throwExpected` / `val i: Int = throwExpected` and verified fine in Scala 3.

### F2: Static forwarders (mirror/companion statics) lack ACC_DEPRECATED, MethodParameters, LocalVariableTable and parameter annotations
- severity: medium
- confidence: high (reproduced for deprecation/param names; param annotations by code reading)
- s3: compiler/src/dotty/tools/backend/jvm/BCodeHelpers.scala:283-345 (`BCForwardersGen.addForwarder`; flags at ~L295, `val params: List[Symbol] = Nil` at ~L318)
- s2: src/compiler/scala/tools/nsc/backend/jvm/BCodeHelpers.scala `addForwarder`; fix commits: 10b09dd228 2018-01-27 "Deprecate static forwarders along with their forwardees"; 06347fc82d 2017-12-07 "emit parameter names for static forwarders" (also 2c1ca22ad7 2021-04-01 "Emit LocalVariableTable for mirror methods"); `emitParamAnnotations(mirrorMethod, m.info.params.map(_.annotations))` is also in s2
- what: Scala 2 sets `ACC_DEPRECATED` on a forwarder when the forwardee is `@deprecated`, calls `emitParamNames` (MethodParameters), emits a LocalVariableTable entry per parameter, and copies parameter annotations. Scala 3 emits only PUBLIC|STATIC|VARARGS|SYNTHETIC, passes `params = Nil` (so `emitParamAnnotations` is a no-op, with a TODO comment saying it cannot be done), and writes no names. Observable: Java callers of `Foo.deprecatedMethod()` get no deprecation warning via the static forwarder; reflection/IDE/Kotlin see `arg0,arg1`; Java parameter annotations (e.g. `@Nullable`, validation annotations) on the object's method are lost on the static forwarder.
- verified: compiled `object M { @deprecated def dep(x:Int,y:String); def plain(name:String,count:Int) }` with 3.10.0 and `javap -v`: forwarders have `flags: (0x0009) ACC_PUBLIC, ACC_STATIC`, no `Deprecated`, `MethodParameters` or `LocalVariableTable`, while the instance methods in a class `Foo` do get `Deprecated: true`/`MethodParameters` (work/.../t1). Symbol-level param info is available (`m.paramSymss` / `methodInfo` param names) so the TODO can be done.

### F3: Static field access with an impure qualifier leaves the qualifier on the operand stack (missing POP)
- severity: low
- confidence: medium (code identical to the pre-fix s2; not reachable from plain source, only via macros/compiler-plugin trees)
- s3: compiler/src/dotty/tools/backend/jvm/BCodeBodyBuilder.scala:425 (`genLoadQualUnlessElidable`) and :1258 (`genLoadQualifier`)
- s2: BCodeBodyBuilder.scala `genLoadQualifier(tree, drop = true)`; fix commit 911d375755 2024-04-02 "Emit missing POP in backend when accessing static fields" (Lukas Rytz)
- what: for `sym.isStaticMember` / module selects whose qualifier is not idempotent, Scala 3 calls `genLoadQualifier(tree)` which leaves the qualifier value on the stack and then emits GETSTATIC/GETMODULE, giving a "Bad type on operand stack"/stack-height mismatch at runtime or an ASM frame crash. Scala 2's fix loads the qualifier with expected type UNIT (drop). In Scala 3 this requires a tree like `Select(impure, staticField)` (macro/inline expansion or a plugin); Java statics cannot be selected from an instance in Scala 3 source (verified: "value F is not a member of J").
- verified: read the code (identical to pre-fix s2); source-level repro impossible (t3 above); no Scala 3 test or commit mentions it.

### F4: Generic signature drops the outer class' type arguments for generic inner classes (`Outer[A]#Inner[B]`)
- severity: low (medium for Java interop of inner generic classes)
- confidence: high (reproduced, compared against 2.13.16 output)
- s3: compiler/src/dotty/tools/backend/jvm/GenericSignatures.scala:~190-215 (`classSig` reboundPre handling) combined with the `RefOrAppliedType.unapply` AppliedType case (~L505) which returns the *tycon* as `pre`
- s2: transform/Erasure.scala `javaSig0.classSig` (`val preRebound = pre.baseType(sym.owner) // #2585`); fix commits 659dba63c3 / 9c57e78be5 2019-04-09 "Fix interop between Java and generic inner Scala classes" (scala/bug#10889) and the long-standing scala/bug#2585 logic
- what: for an inner class with its own type arguments, `RefOrAppliedType` returns `(pre.typeSymbol, pre, args)` where `pre` is the AppliedType's tycon (`Outer[String]#Inner`) rather than the prefix (`Outer[String]`), so `pre.baseType(sym.owner)` yields nothing and the outer arguments are never written. For inner classes without own type arguments it works. Scala 2 emits `LOuter<Ljava/lang/String;>.Inner<...>;`, Scala 3 emits `LOuter$Inner<...>;` (outer treated as raw). Same for classes in objects nested in generic classes: `Outer.Mod2.InMod[T]` -> `LOuter$Mod2$InMod<TT;>;` vs s2 `LOuter<TT;>.Mod2$InMod<TT;>;`, and for `Inner[X]#Innermost[Y]`. Java consumers see `Outer.Inner<Integer>` with a raw `Outer`.
- verified: compiled the same file with 3.10.0 and `scala-cli --scala 2.13.16`; javap Signature diff: `def a(x: Outer[String]#Inner[Int])` -> s3 `(LOuter$Inner<Ljava/lang/Integer;>;)V` vs s2 `(LOuter<Ljava/lang/String;>.Inner<Ljava/lang/Object;>;)V`; `def b(x: Outer[String]#Plain)` is identical in both (`LOuter<Ljava/lang/String;>.Plain;`). Existing tests/generic-java-signatures/inner-of-generic* only cover inner classes without their own type parameters. (java.lang.reflect parsed all sigs without error.) Files: work/C-backend-codegen/t9/{Q.scala,o2,o3}.

### F5: DirEntryWriter uses delete + CREATE_NEW; concurrent writes to the same path can fail (case-insensitive FS)
- severity: low
- confidence: low-medium (code reading only, not reproduced)
- s3: compiler/src/dotty/tools/io/FileWriters.scala:~165 (`Files.deleteIfExists(path); Files.write(path, bytes, CREATE_NEW, WRITE)`), introduced by f831f7df48 2026-10-16 (#27311) on top of the parallel class writing from e4d58c37e6 2026-09-30 (#26382)
- s2: ClassfileWriters.scala DirEntryWriter.writeFile; commits 1ceafd2275 / 1181fdd24b 2018-05-23 "Avoid double open when overwriting a classfile on non-Windows" (opens with CREATE|WRITE|TRUNCATE_EXISTING, no delete)
- what: Scala 3 warns (not errors) when two classes differ only in case (`warnCaseInsensitiveOverwrite`, CodeGen.scala) and then writes both on pool threads. With delete-then-CREATE_NEW, two writers racing on the same (case-insensitively equal) path can both pass `deleteIfExists` and the loser gets `FileAlreadyExistsException`, reported as `Error writing ...` instead of the previous last-writer-wins overwrite. Also the `symlink` rationale (don't write through symlinks) is not served by s2's approach, so this is a deliberate Scala 3 divergence; the race is the only concern.
- verified: read code; did not attempt a stress repro.

## Already ported / not applicable

- 93d1b5cf6c local var start after store: present (BCodeBodyBuilder.scala:332-336 `bc.store` before `currProgramPoint`).
- 051659595b max param count counts Long/Double as 2: present (BCodeSkelBuilder.scala:~908 `paramsSize`).
- e4fa961542 no line number for synthetic unit: present (`SyntheticUnit` attachment in `lineNumber`).
- 42bfebf8c2 / 4eb1d8788f `BTypes.LONG.maxValueType(FLOAT)`: present and correct (BTypes.scala:268-272).
- 3e8293203b `conformsTo`/`adapt` rework (prim array non-conformance, BOOL/BYTE/SHORT/CHAR vs INT, Nothing/Null adapt with `to == UNIT`): ported (BTypes.scala:85-123, BCodeBodyBuilder.scala:1182-1253).
- 7b7e497b1c abstract+final inner class (Kotlin interop): `javaFlags` already suppresses ACC_FINAL when abstract (BCodeUtils.scala ~505); Scala 3 does not use the `enteringTyperPrivate` scheme (class flags are always PUBLIC), so 4746d541b0/f5ff2107ac/ad19d1aa2a are not applicable.
- 6abb6ba47a (scala-dev#329, Float/Double `equals`): behaviour verified correct at runtime in Scala 3 (`java.lang.Double` `==`: 0.0 == -0.0 true, NaN == NaN false).
- b7dc31fb27 / 2a351ca9b0 indy string concat: ported incl. 200-slot spilling, `\u0001`/`\u0002` constant escaping, no-op `""` removal, box elimination; additionally Scala 3 keeps the stack model consistent when spilling. Runtime tested with 210 args + literal containing \u0001.
- ea9ca653fd iinc: present. aee8e55ade `Objects.equals` for `==`: ported 2026-02 (7039d4e906 Scala 3 side), `Objects_equals` used in genEqEqPrimitive.
- f18b249405 (scala/bug#7936 super call receiver), 5a25c2a6e3 (don't minimise parents of super calls = `superCallTargets`), 66a3b0e976 (no serialVersionUID on traits; Scala 3 warns instead), 7f20b1ce6c (private serialVersionUID): present.
- 659dba63c3 (outer param excluded from inner-class constructor signature): verified `(TU;TT;)V` for `class I[U](u: U, x: T)` inner of generic.
- 369c74329b (scala/bug#11321 derived value class boxed inside type args): verified `Option<V$VC>` style output; handled by `ValueClassBoxing`.
- 3edeaac047/8afd256521/0763168ca6/b6d7e0c42c bridge handling in static forwarders; scala/bug#10812: handled via `nextOverriddenSymbol`.
- 88e4b7f221 (remove assertion in EnclosingMethod): the assertion does not exist in Scala 3 (BCodeUtils.scala:~590).
- 04cd5c392c delayedInit$body top-level artifact: Scala 2-specific, n/a.
- fa403e88d6 (t13142, expand lambdas to anonymous classes when LMF adaptation differs): probed null-unboxing through `F1[-T,+R]` sub-SAM traits in Scala 3; Scala 3 does not produce LMF lambdas for these and `apply(null)` returns 1, no NPE.
- BCodeSyncAndTry: genLoadTry/genSynchronized structure matches s2 (EH ranges, cleanup chain, SD-233 comment, NOP for empty ranges); runtime-tested try/finally+return, return in finally, nested synchronized with early return/exception, loops with try-catch-finally: all correct.
- genSwitch string switch: hash collisions ("Aa"/"BB", "AaAa"/"BBBB") and null selector tested OK. Minor: `casesByHash` prepends (`newCase :: existing`) so for two equal-hash strings the later case is tested first; only matters for duplicate literals (unreachable in practice).
- ClassfileWriters: jar manifest (Main-Class, Scala-Compiler-Version), `-Yjar-compression-level` STORED CRC handling, Windows device-name check, ClosedByInterrupt cleanup, symlinked output dir: all ported. Neither side sets deterministic zip entry timestamps.
- MethodTooLarge/ClassTooLarge: reported as errors in CodeGen.finish (tested: `Error while emitting W$ / Method too large: W$.big`), without a source position; same in s2.
- Annotation argument encoding (`emitArgument`): nested annotations, arrays, enums (incl. final val aliases), class literals via `erasure`, defaults via `_`: no divergence found vs s2; `isRuntimeVisible` default RUNTIME when no @Retention matches s2.
- ScalaPrimitivesOps: no s2 semantic commits since 2018.

## Method notes

- The Scala 3 backend is not a plain fork any more: it was ported 2017, refactored to the 2.12.x/2.13 `CodeGen/PostProcessor` layering in 2022 (fa9648259d, "Port GenBCode refactor Scala#6012"), and the 2.13 optimizer was ported in Feb 2026 (0735e175a9), so many s2 fixes up to ~2021 were pulled in with it. The remaining un-ported fixes are mostly in code the 2022/2026 re-syncs skipped (static forwarders, array stores) or in generic signatures.
- Line-count similarity (sim.tsv) is very low for BodyBuilder/SkelBuilder/Helpers (0.13-0.31) because of the Scala 3 syntax rewrite and the `LoadDestination`/stack-tracking restructure; I diffed semantically around the commits above rather than relying on it.
- GenericSignatures.scala is a ground-up Scala 3 implementation (type-lambda/AND-OR/`TypeParamRef` handling); Scala 2's existential/refinement/`BoundedWildcardType` branches have no direct analogue. I checked Scala 2 vs Scala 3 output on ~25 signature shapes (wildcards, bounds, arrays, value classes, `Nothing`/`Null`, modules, inner classes, varargs/by-name): the only structural difference was F4; others differ only because Scala 3 erases `Int` type args to `Integer` where Scala 2 emits `Object`.
- Not covered (time): opt/analysis packages (assigned elsewhere), `BCodeSkelBuilder` lambda deserialization, SD/async. Reverse-port direction: no case found where a Scala 3 backend fix is missing in Scala 2 beyond already-landed ones (`Objects.equals`, `IINC` on callee parameters ada4c36996 was ported to Scala 3 as 9f78b660c9 2026-10-16).
