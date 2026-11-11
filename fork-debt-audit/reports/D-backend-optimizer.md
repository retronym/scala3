# D-backend-optimizer: Scala 3 backend optimizer vs scala/scala 2.13.x

Paths: S3 = /Users/jzaugg-virtus/code/.worktrees/scala3/scala3-untracked-fork-debt-803909/compiler/src/dotty/tools/backend/jvm ; S2 = src/compiler/scala/tools/nsc/backend/jvm (origin/2.13.x).
Working files (normalized diffs `*.diff`, repro sources, `run.sh`): <scratchpad>/work/D-backend-optimizer/ (repro in `t1/`).

## Port base

- Everything landed in one commit: `e86be00cfb` 2026-02-09 "Port analysis and opt directories" (Solal Pirelli; no PR description, files cite no scalac version). `BCodeRepository`/`BTypesFromClassfile` came in `5e9465900b` (same day). Nothing in the files names a scalac version; the content matches 2.13.x as of ~Feb 2026.
- Evidence that it is current-tip: the 2025-03 s2 fix b682a511ea (NoBytecode check) is present (InlinerHeuristics.scala:136), the 2024-04 ASM-9.7 fix f759e6a2c4 (ProdConsAnalyzer.newExceptionValue) is present (ProdConsAnalyzer.scala:467), the 2023-05 `findInInterfaces` rewrite 6551d25b7d/4499009fd5 is present (but see F1/F2), `findSignaturePolymorphic` (abc6c20df7, 2021), "Load return values before jumping to post-call" (5fd3ca4f19, 2022), box-unbox-around-branches (13a4254830) all present.
- Direction is partly REVERSED for the newest changes: s3 `1f91794775` (2026-02-20 "Fix inliner bug when inlining IINC on parameters") and `9f78b660c9` (#27316, 2026-10-16) were backported INTO scala/scala as ada4c36996 (2026-10-16). s3 has both halves (Inliner.scala:475-480, OptimizerCallGraph.cloneInstructions IINC branch).
- s3 also carries optimizer features that do not exist in s2 (so not "missing" in s3): `Objects.equals`/`Object.equals` nullness folding (LocalOptimizer.scala:179-194), `ClassTag(X.TYPE).newArray` -> NEWARRAY (CopyProp.classTagNewArrayInstr), `TupleN.apply` box elimination (BoxUnbox Tuple.checkKind), `instanceof Object` pop-removal, `java/lang` static-load removal, std-lib module alias calls, null-frame guard in `inlineCallsite`, `Lnull;` guard in redundant casts, unconditional `removeEmptyLineNumbers`, inline-info `isAccessible` bit / JPMS check, analysis budget, tighter size limits.

## Pairs

| s3 file | s2 file | port date | later s2 commits (since port base) | notes |
|---|---|---|---|---|
| opt/Inliner.scala (GlobalOptimizer, UndoLog, InlineLog, MethodInlinerState) | opt/Inliner.scala | 2026-02-09 | 2 (b682a511ea NoBytecode - ported; ada4c36996 IINC - from s3) | cake-pattern -> classes; indy-lambda-impl tracking dropped (F3); findIllegalAccess now uses `isInternal` (inlineInfo.isAccessible) |
| opt/InlinerHeuristics.scala | opt/InlinerHeuristics.scala (+ earlyCanInlineCheck from Inliner) | 2026-02-09 | 1 (b682a511ea, ported) | `shouldInlineForwarder` rewritten in 921403e0d8; mixin-forwarder callsite exclusion dropped in de3bf137fd (deliberate); StrictfpMismatch deleted |
| opt/LocalOptimizer.scala | opt/LocalOpt.scala | 2026-02-09 | 0 | removeIndyLambdaImplMethod dropped; clearMaxsComputed commented out (F4) |
| opt/BoxUnbox.scala | opt/BoxUnbox.scala | 2026-02-09 | 0 | logic identical except new Tuple.apply case |
| opt/CopyProp.scala | opt/CopyProp.scala | 2026-02-09 | 0 | new instanceof-Object, java/lang static load, ClassTag newArray |
| opt/ClosureOptimizer.scala | opt/ClosureOptimizer.scala | 2026-02-09 | 0 | identical logic (+`applyVoid` spec); altMetafactory handling identical |
| opt/OptimizerCallGraph.scala + CallGraph.scala | opt/CallGraph.scala | 2026-02-09 | 0 | Callsite split into Known/Unknown; FLazy removed (eager paramTps) |
| opt/BCodeRepository.scala | opt/ByteCodeRepository.scala | 2026-02-09 | 0 (2023-05 changes already in) | F1, F2 |
| opt/BTypesFromClassfile.scala | BTypesFromClassfile.scala | 2026-02-09 | 0 | rewritten; JPMS module-info lookup added |
| opt/InlineInfoAttribute.scala | opt/InlineInfoAttribute.scala | 2026-02-09 | 0 | VERSION still 1; attribute renamed `Scala3InlineInfo`; flag bit 8 = isAccessible |
| opt/OptimizerWarning.scala | BackendReporting.scala | 2026-02-09 | 1 (b682a511ea ported) | `definedInJavaSource`/no-inline-mixed dropped (F9); Strictfp, SI9111 warnings dropped |
| opt/OptimizerUtils.scala, opt/MethodMax.scala, opt/OptimizerKnownBTypes.scala, analysis/AnalysisUtils.scala, GenericSignatureVisitor.scala | analysis/BackendUtils.scala (split) | 2026-02-09 | 4 (ada4c36996, ASM/target bumps) | LambdaMetaFactoryCall, looksLikeForwarder..., computeMaxLocalsMaxStack textually identical; tables expanded |
| analysis/AliasingAnalyzer, NullnessAnalyzer, ProdConsAnalyzer, TypeFlowAnalyzer, InstructionStackEffect, package.scala | same names | 2026-02-09 | 1 (f759e6a2c4 ported) | only nullability annotations, `AnalysisBudget` hook, ASM package rename |
| analysis/AsmAnalyzer.scala | analysis/AsmAnalyzer.scala | 2026-02-09 | 1 (c8bc25a6d0 setComputeLimits; see F5) | now lazy; no `computeMaxLocalsMaxStack` (F5) |
| opt/FifoCache.scala, opt/LogUtils.scala | opt/FifoCache.scala, AsmUtils | 2026-02-09 | 0 | identical |
| BCodeUtils.scala | opt/BytecodeUtils.scala | 2026-02-09 | 0 | identical optimizer part; extra Scala-3 symbol helpers appended |
| opt/Limits.scala, analysis/AnalysisBudget.scala, analysis/MethodFingerprint.scala, opt/OptimizerSettings.scala | (AsmAnalyzer.sizeOKFor*) / none | n/a | n/a | Scala-3-only |

## Findings

### F1: findInInterfaces visits an interface twice (strict `filter(!visited)`), so a unique default method resolves to None
- severity: medium
- confidence: high
- s3: compiler/src/dotty/tools/backend/jvm/opt/BCodeRepository.scala:192-201 (`owner.interfaces.asScala.filter(!visited(_)).collectFirst(...)`)
- s2: opt/ByteCodeRepository.scala (`for (i <- owner.interfaces.asScala if !visited(i)) ... return`); logic from 6551d25b7d / 4499009fd5 2023-05 (Som Snytt)
- what: s2 checks `!visited(i)` lazily per element inside the loop. The Scala 3 rewrite to `collectFirst(...).unlift` filters eagerly before iterating. For `class C implements A, B` where `A extends B`, `B` is recursed into while handling `A`, and then handled again by the outer loop, so `found` holds the same `(MethodNode, ClassNode)` twice. With `found.size == 2` and `other ne method` false for the duplicate, `maxSpecific` has 2 entries, `nonAbs` has 2, and the function returns `None` instead of the single default method. Consequence: callsites resolved through that class are not "statically resolved", so the inliner/closure optimizer silently skips them. Only reachable for classfiles that list redundant interfaces (Java classes, e.g. JDK `LinkedHashSet implements SequencedSet, Set`; Scala 3 minimizes the interface list itself), and only when `-opt-inline` matches those classes.
- verified: ran the repo's compiler jar (3.10.2-RC1 nonbootstrapped, `-opt -opt-inline:** -Yopt-inline-heuristics:everything`) on Java `JB{default int m(){return 42;}} JA extends JB; final JC1 implements JA, JB; final JC2 implements JB, JA`. `new JC2().m()` is inlined to `bipush 42`; `new JC1().m()` stays `invokevirtual JC1.m` (only the interface order differs). Sources: t1/jsrc, t1/src2/E.scala; output t1/out3.

### F2: maximally-specific check only looks at DIRECT superinterfaces (s2 uses transitive isSubtypeOf)
- severity: medium
- confidence: high
- s3: compiler/src/dotty/tools/backend/jvm/opt/BCodeRepository.scala:209-215 (`otherOwner.interfaces.asScala.contains(owner.name)`)
- s2: opt/ByteCodeRepository.scala (`bTypesFromClassfile.classBTypeFromClassNode(otherOwner).isSubtypeOf(ownerTp).get`); 6551d25b7d/4499009fd5 2023-05
- what: when two interfaces in the hierarchy declare the method, the more specific one must hide the less specific one. s3 only recognizes it if the overriding interface lists the overridden one directly. Any intermediate interface (`JB{m}; JA extends JB; JD extends JA {m}`) leaves both in `maxSpecific`, both concrete, so the result is `None` (no inline), where s2 returns `JD.m`.
- verified: same harness, `final JF implements JD` (JD overrides JB.m via JA): `new JF().m()` not inlined in s3 (t1/jsrc/JD.java, JF.java, t1/src2/G.scala, output t1/out4). Scala-compiled hierarchies are not affected because Scala 3 emits mixin forwarders (`final class F extends D` with `trait D extends A; trait A extends B`: `F.m` forwarder exists, `h()` inlines to `bipush 7`, t1/src2/H.scala).

### F3: Serializable-lambda bookkeeping (`indyLambdaImplMethods` / `addLambdaDeserialize` after optimization) was dropped
- severity: low
- confidence: low
- s3: opt/Inliner.scala (no equivalent of the `callsiteLambdaBodyMethods` copy at the end of `inlineCallsite`), opt/LocalOptimizer.scala (no `removeIndyLambdaImplMethod`), opt/BCodeRepository.scala (no `addIndyLambdaImplMethod`); `$deserializeLambda$` is generated at codegen time in BCodeSkelBuilder.scala:289/322 from `serializableLambdas` (BCodeBodyBuilder.scala:1767-1856: all `scala.FunctionN` lambdas are FLAG_SERIALIZABLE)
- s2: analysis/BackendUtils.scala:56,462-497 (`indyLambdaImplMethods`), opt/Inliner.scala (copies tracked handles to the destination method on inline), UndoLog.saveMethodState, PostProcessor calls `addLambdaDeserialize` after the optimizer; f33ce2a2d2 (2018) "track indyLambdaImplMethods more precisely"
- what: in s2 the `$deserializeLambda$` of a class is built after inlining from the lambdas actually present in that class, so an inlined serializable `indy` also appears in the destination class's `$deserializeLambda$`. In s3 the method is fixed before the optimizer runs, so an `indy` inlined into a different class would not be deserializable there (IllegalArgumentException "Illegal lambda deserialization" / missing `$deserializeLambda$`). Currently masked: Scala 3 lambda bodies are `private static final` (verified), and `findIllegalAccess` rejects inlining an `indy` whose impl handle is private in another class, so the problem needs a non-private impl method (not seen in Scala 3 output).
- verified: javap of `A.mk$$anonfun$1` (private static final) from t1/A.scala; grep shows no deserialize handling under opt/ or analysis/. Not reproduced as a failure.

### F4: `clearMaxsComputed` call was commented out, the ACC_MAXS_COMPUTED pseudo-flag is never cleared
- severity: low
- confidence: medium
- s3: opt/LocalOptimizer.scala:374 (`// OptimizerUtils.clearMaxsComputed(method)`), opt/MethodMax.scala:187-190 (no `clearMaxsComputed` at all); commented out by 374f6f43ec (#26174), not mentioned in the PR title
- s2: opt/LocalOpt.scala:412 `BackendUtils.clearMaxsComputed(method)` at the end of `methodOptimizations`; flag introduced 741d5892a3, targeted fix 6d1cdaecb3 (2020)
- what: s2 deliberately invalidates the cached maxLocals/maxStack when local optimization finishes so that the next consumer (inliner, closure optimizer, analyzers in the next round) recomputes from the current instructions. In s3 the first computed value sticks forever; every later change must update `method.maxStack/maxLocals` by hand. I checked the mutators (BoxUnbox sets both; CopyProp.eliminatePushPop bumps maxStack at CopyProp.scala:497; Inliner/ClosureOptimizer/UndoLog set/restore them; DCE/copy-prop/stale-store/cast rewrites do not grow stack or locals) and found no case that leaves the values too small, so this is a latent hazard (and stale-too-large values waste local slots) rather than a demonstrated bug. The stale pseudo-flag bit in `access` is harmless in the classfile (ASM `putShort` truncates to 16 bits).
- verified: read all `maxStack =`/`maxLocals =` writers in opt/; no failing program found.

### F5: AsmAnalyzer no longer calls `computeMaxLocalsMaxStack` nor lifts ASM 9.11 compute limits
- severity: low
- confidence: medium
- s3: analysis/AsmAnalyzer.scala:21-35
- s2: analysis/AsmAnalyzer.scala (`computeMaxLocalsMaxStack(methodNode)` before analysis, since 2017/2020; `analyzer.setComputeLimits(Int.MaxValue, Long.MaxValue)` added by c8bc25a6d0 2026-10-19 "Upgrade ASM to 9.11.1", which fixed "Too many allocated bytes" on huge methods, neg/inlineMaxSize)
- what: (a) s2 made every analyzer self-sufficient w.r.t. maxs; s3 requires each caller to have touched `MethodMax` first. I checked every `new *Analyzer` in opt/ and each is preceded by a `Limits.sizeOKFor*` or `MethodMax.max*` call (Inliner.scala:146/418 rely on OptimizerCallGraph.addMethod having called `Limits.sizeOKForBasicValue`), so it works today but is fragile. (b) ASM 9.11's Analyzer throws `LimitExceededException` at 400MB / 5e10 ops (verified in asm-analysis-9.11.1-sources Analyzer.java:57-66). s3 builds with ASM 9.11 (project/Dependencies.scala:11) and doesn't lift them for analysis; the writer limits are lifted (CodeGen.scala:250, 10x). With s3's tight `Limits` (<=3000 insns, <=600 locals) the analyzer limit seems unreachable, but inlining can grow a caller (aliasing analysis in Inliner.scala:146/418 runs on the grown callsite method). No repro attempted.
- verified: read ASM source for the limits, enumerated analyzer construction sites.

### F6: `no-inline-mixed` warning category / `definedInJavaSource` messaging lost
- severity: low
- confidence: high
- s3: opt/OptimizerWarning.scala (`ClassNotFound(internalName)`), opt/OptimizerSettings.scala:27 (`optWarningNoInlineMixed` is read but never used), dotc/config/ScalaSettings.scala:415
- s2: BackendReporting.scala:98-101 (`ClassNotFound(internalName, definedInJavaSource)`, `javaDefinedClasses`)
- what: in s2 a class defined in a Java source of the same run produces a dedicated message and is gated by `-opt-warnings:no-inline-mixed`; in s3 it is reported as a generic missing classfile under `no-inline-missing-bytecode`, and `no-inline-mixed` is a dead setting.
- verified: `grep -rn noInlineMixed|optWarningNoInlineMixed` in compiler/src: only the definition and the settings copy.

### F7: Heuristics divergences from the "gnarly code" simplification (921403e0d8) and de3bf137fd
- severity: low
- confidence: medium
- s3: opt/InlinerHeuristics.scala:116-121 (isGeneratedForwarder), 205-240 (shouldInlineForwarder)
- s2: opt/InlinerHeuristics.scala:200-243
- what: (a) s2 excluded mixin forwarders as inlining TARGETS (`isMixinForwarder(callsiteMethod)` in `isGeneratedForwarder`); s3 dropped it on purpose (de3bf137fd "Inline trait static forwarders") so trait bodies get copied into each mixin forwarder (observed: `F.m()` becomes `bipush 7`, t1/out9); this is bytecode-size growth for `@inline`d/trivial trait methods but intended. (b) s2 treated trait super accessors specially and always labelled them `GenericForwarder` (small limit 1000) after checking the single nested call; s3 evaluates every callee with `allowPrivateCalls` if its only callsite is a trivial forwarder, and the result can be `SyntheticForwarder` (limit maxSize 3000, higher priority in `selectRequestsForMethodSize`) or Trivial/Factory. Net effect: different inlining budget/ordering for trait `m$` accessors; not a correctness issue.
- verified: diffed heuristics; javap'd output.

### F8: GenericSignatureVisitor no longer swallows malformed signatures (deliberate, 9db016f530)
- severity: low
- confidence: high
- s3: GenericSignatureVisitor.scala:35-55 (`assert` / `indexOf >= 0` assert instead of `safely`/`raiseError`)
- s2: analysis/BackendUtils.scala:960-1105 (`safely`, `raiseError` -> warning)
- what: s2 degraded to a warning on a signature it can't parse; s3 now throws (AssertionError / StringIndexOutOfBounds) from `CodeGen.serializeClassNode` -> `collectNestedClasses` for EVERY class (not only with -opt). Intentional per #26632 "Stop hiding errors", listed so nobody "fixes" it by porting `safely`. Also `getClassIfNested` uses `previouslyConstructedClassBType(...).get` (CodeGen.scala:209-212), which would throw for a nested class that was never constructed; with `-opt-inline:**` over JDK nested classes (List.of, Map.entry, Optional) I saw no failure (t1/src2/L.scala).

## Already ported / not applicable

- b682a511ea (2025-03, NoBytecode early check): present, InlinerHeuristics.scala:136, OptimizerWarning.scala:167.
- f759e6a2c4 / 5e1b137aa5 (2024-04, ASM 9.7 `newExceptionValue` stack index): present, ProdConsAnalyzer.scala:467-472.
- 6551d25b7d, 4499009fd5, 8b878ec986 (2023-05 interface lookup, messages): rewritten; see F1/F2 for the two regressions introduced by the rewrite.
- 25b5b885fc (FifoCache lock-free): identical.
- ada4c36996 / s3 9f78b660c9 / 1f91794775 (IINC on callee params): present in both; s3 was the origin.
- ASM/JDK target bumps (b4f4b9d4f3, 825c26c600, a87afcb703, 786153f88a, 3499adb1e9, 9588f99457, f27e08e91d, 5aa5189e13, dc2dc34955...): BackendUtils `classfileVersion` only; outside this area, not checked in s3 target handling.
- aee8e55ade (Objects.equals in `==` codegen, 2025-05): codegen, not optimizer; s3 optimizer handles `Objects.equals` nullness.
- 2019-2022 optimizer fixes (13a4254830/2ebf54dafe box-unbox around branches, b705e3b467, 5fd3ca4f19, abc6c20df7, 2ebf...): text identical in s3 BoxUnbox/Inliner/BCodeRepository.
- Dead-code / Scala-2-only removals that are fine: Strictfp mismatch, SI9111 warnings, `GenBCode.INSTANCE_CONSTRUCTOR_NAME`, `javaSams` comment block, `FLazy`.
- Checked identical (modulo syntax and nullness annotations): BoxUnbox consumers/creations and copy-op replacement (escape analysis, `valueOf` handling), ClosureOptimizer rewrite incl. `LambdaMetaFactoryCall` (metafactory + altMetafactory, checks (1)-(3)), LocalOpt fixpoint loop (request flags, DCE, nullness again), `removeJumpAndAdjustStack`/jump simplification, AliasingFrame/AliasSet sizes (`Array.copyOf` equivalent), NullnessAnalyzer (ACONST_NULL, INSTANCEOF, ifNullAliases), NonLubbingTypeFlowAnalyzer (`refLub = ObjectValue`; s3 adds an array-descriptor guard), InstructionStackEffect, InlineInfoAttribute VERSION=1, Inliner `callsiteOrdering`/`inlineRequestOrdering`/`collectAndOrderInlineRequests`/`selectRequestsForMethodSize`, `MethodMax.computeMaxLocalsMaxStack`.

## Method notes

- s3 side is a re-architecture, not a copy: cake-pattern `abstract class X { val postProcessor }` became classes with constructor args; `Either`-wrapped `ClassBType.info` became plain; `Callsite` became sealed Known/Unknown. Line-level similarity (56-96%) understates logical similarity, so I diffed normalized copies (`sed` strip comments/imports/private/final/override, `diff -wB`) per pair: BoxUnbox 235 lines of diff, CopyProp 200, LocalOpt 436, Inliner 928 (mostly moved InlineLog/MethodInlinerState/UndoLog), Closure 207, Heuristics 370, CallGraph 350, Alias 134, ProdCons 14, Null 69, TypeFlow 47, ISE 18, IIA 22, Repo 146, Warning 181 and read all of them.
- Limits are intentionally much stricter than s2 (6f1c8580d0 "Lower max instrs and locals for local opts"): aliasing/nullness need <300 locals and <3000 insns, basic-value <600/<3000, source-value <500/<2500, versus s2's `insns*locals^2` thresholds (up to 9000 insns). Consequence: more methods skipped by the local optimizer and absent from the call graph (no inlining into them); expected, not a bug.
- `Scala3InlineInfo` attribute name: s3 cannot read `ScalaInlineInfo` from 2.13-compiled jars; they get `NoInlineInfoAttribute`-style missing info (only matters for libraries compiled by scalac).
- Empirical harness: running the repo's built nonbootstrapped compiler jar (3.10.2-RC1, /Users/jzaugg-virtus/code/scala/scala3/compiler/target/...) via `t1/run.sh` with the stdlib jar; JDK 21 for running, JDK 17 javac for the Java fixtures. Not in s2 scope: nothing was built or edited in either checkout.
- s2 repo history includes commits dated after 2026-10 (e.g. c8bc25a6d0, ada4c36996); I treated origin/2.13.x as ground truth.
