# R-reverse-compiler: Scala 3 fixes missing from scala/scala 2.13.x (compiler/backend/IO/parsing area)

Reference: scala/scala `origin/2.13.x` a0ebc676ba (2026-10-20); Scala 3 HEAD 2c1b0d6413. Behaviour was reproduced with 2.13.19-M1 (about 30 commits behind HEAD; the code at every cited 2.13.x location was re-read at `origin/2.13.x` and is unchanged). Scratch: .../scratchpad/work/R/.

## Pairs

| s3 file | s2 file | Result of the reverse check |
|---|---|---|
| backend/jvm/opt/{Inliner,BCodeRepository,BTypes}, classpath/*, io/ClassPath | nsc/backend/jvm/opt/{Inliner,ByteCodeRepository}, nsc/classpath | F1 (JDK 9+ module export awareness, added 2026-02) |
| backend/jvm/opt/BoxUnbox, analysis/AsmAnalyzer, AnalysisBudget | same names in nsc/backend/jvm/opt, analysis | F3 (2026-10, #27290) |
| parsing/JavaScanners | nsc/javac/JavaScanners | F2 (#18491) |
| dotc/profile/{Profiler,JsonNameTransformer,ChromeTrace} | reflect/internal/util/ChromeTrace, nsc/profile/Profiler | F4 (#21872) |
| io/FileWriters, io/File | nsc/backend/jvm/ClassfileWriters | F5 (#27311) |
| BCodeBodyBuilder, BCodeHelpers, BCodeUtils | nsc/backend/jvm/BCode* | F6 (#25300; position only) |
| BCodeIdiomatic/SkelBuilder/SyncAndTry, BTypes (non-opt), GenericSignatures | BCode*, Erasure.javaSig | nothing found (see Already ported) |
| JavaParsers, core/classfile/ClassfileParser | javac/JavaParsers, symtab/classfile/ClassfileParser | nothing new beyond E/F reports (see below) |
| parsing/xml/*, util/Chars, config/CommandLineParser | ast/parser/xml, Chars, sys/process/Parser | nothing found |
| transform/localopt/{FormatChecker,StringInterpolatorOpt} | tools/reflect/FormatInterpolator | nothing found (same author, kept in lockstep; G report) |

Severity is from the Scala 2.13 user's perspective. Sorted by severity.

## Findings

### F1: Inliner has no JDK 9+ module-export check: inlining a JDK method that touches a non-exported package gives IllegalAccessError at runtime
- severity: medium
- confidence: high (reproduced on 2.13.19-M1; code unchanged on 2.13.x HEAD)
- s2: src/compiler/scala/tools/nsc/backend/jvm/opt/Inliner.scala:1190 (`findIllegalAccess`, with `classIsAccessible` :1103 and `memberIsAccessible` :1145), src/compiler/scala/tools/nsc/backend/jvm/opt/ByteCodeRepository.scala:299 (`parseClass`)
- s3 fix: 730eefa650 2026-02-16 "Take JDK 9+ modules into account when determining what can be inlined" (Solal Pirelli; no PR number in the subject, part of the optimizer port series 0735e175a9 / #25187)
- what: Scala 2's access check for transplanted instructions only looks at ACC_PUBLIC/protected flags of the referenced class and member. On JDK 9+ a public class in a non-exported package (`jdk.internal.util.Preconditions`, `sun.*`, ...) passes that check, but the JVM rejects the reference from the unnamed module. Scala 3 added `InlineInfo.isAccessible` (BTypes.scala:620-630). It is computed from the callee class's `module-info.class`, which is looked up via the new `ClassPath.findClassFileAndModuleFile`. The check passes only if the package is unconditionally exported. The hard-coded `name.startsWith("jdk/internal") || name.startsWith("sun/")` list (carried over from the Scala 2 era; there is a "TODO handle Java modules" comment) was replaced by `isInternal(name)` at Inliner.scala:777, which consults it. Scala 2.13 has neither the list nor the module check, so with `-opt:inline:**` (the only way to inline from the JDK) and a call that gets inlined it emits code that cannot run.
- verified: `object A { def f(i: Int, n: Int): Int = (java.util.Objects.checkIndex(i, n): @inline) }` compiled with `-opt:inline:**` on 2.13.19-M1/JDK 21. Running it throws `IllegalAccessError: class A$ (in unnamed module) cannot access class jdk.internal.util.Preconditions (in module java.base) because module java.base does not export jdk.internal.util`. grep for `jdk/internal`, `isAccessible`, `ModuleNode`, `module-info` in the 2.13.x nsc/backend/jvm tree: only `CallerSensitive` descriptors in BytecodeUtils.scala:137. Repro: .../scratchpad/work/R/o1/a.scala.

### F2: Java source parser silently drops `\0` (octal NUL) from string constants, so a `static final String` constant gets the wrong value
- severity: medium (silent wrong constant inlined into Scala code; only for `static final String` literals containing an octal-escaped or `\u0000` NUL)
- confidence: high (reproduced; javac used as oracle)
- s2: src/compiler/scala/tools/nsc/javac/JavaScanners.scala:736 (`if (c != 0 && !scanOnly) putChar(c)`) together with :719/:724 (`0.toChar` used as the "skip" sentinel for the text-block `\<newline>` escape, and `if (x != 0) in.next()`)
- s3 fix: 5f29c2178f 2023-08-31 "Tweak java getlitch not to skip zero" (scala/scala3#18491, backports #20672; test tests/run/t12290). Scala 3 uses a separate `var skip` flag for the line-continuation case so that a real NUL char is kept (JavaScanners.scala:458-474).
- what: In `getlitch`, the line-continuation escape returns char 0 as a "nothing to append" marker, and the final `putChar` is guarded by `c != 0`. A genuine `\0`, `\000` or `\u0000` therefore also vanishes. Scala 2 reads `public static final String E = "\0";` from Java source as `""`. The constant is folded into Scala callers (`final val K = Z.E`), so the Scala code sees the wrong string; it is not the value javac bakes into the class.
- verified: `Z.java` with `E = "\0"` and `S = "a\0b\u0000c\\0"`, compiled together with a Scala file on 2.13.19-M1 with `final val K = Z.E`. The inlined value is `List()` for E and `97,98,99,92,48` for S, while javac/reflection give `[0]` and `[97,0,98,0,99,92,48]`. A text block `"""a\0b"""` loses the NUL too. Char constants `'\0'` happened to come out as 0 (default). A differential run over about 50 other literal and escape forms showed no other wrong values; non-literal constant expressions are simply not constants in 2.13 (already covered by E-F6). Repro: .../scratchpad/work/R/j2, j4.

### F3: `-opt` box-unbox forces the whole SourceValue producer/consumer analysis for every `NEW` in a method; no bound on analysis cost
- severity: medium for compile time of `-opt` builds (no correctness issue)
- confidence: medium-high that the pre-filter applies (code compared line by line), medium on the size of the benefit in 2.13 (measurements are Scala 3's: box-unbox CPU on the library 2.0s to 0.5s; local-opt CPU 7.6s to 5.0s; slowest method 840ms to 95ms)
- s2: src/compiler/scala/tools/nsc/backend/jvm/opt/BoxUnbox.scala:191 (`lazy val prodCons`), and the three `case ti: TypeInsnNode if ti.getOpcode == NEW` arms at :703, :762, :811 which call `BoxKind.checkInstanceCreation(ti, prodCons)` (:605-630) before looking at which class is being instantiated. LocalOpt/CopyProp/ClosureOptimizer have no cost bound either (only the `insns * locals^2` static limits in analysis/AsmAnalyzer.scala).
- s3 fix: 251900e615 2026-10-16 "Optimizer: bound producer/consumer analysis cost in local optimizations" (scala/scala3#27290, Jason Zaugg, fixes #27289). Related: 6f1c8580d0 2026-03-02 "Lower max instrs and locals for local opts" (lower static thresholds), 9cf6109dad 2026-10-05 (#27214, intrinsically lazy analyzers).
- what: (a) The `NEW` arms of the primitive-box, ref and tuple creation checks now start with `ts.primitiveBoxConstructors.contains(ti.desc)` / `srRefConstructors` / `tupleClassConstructors` (Scala 3 BoxUnbox.scala:699, 755, 818). In 2.13 every `new X` in a method (e.g. `new NullPointerException` in try/finally helpers) calls `prodCons.consumersOfOutputsFrom`, which forces the full-method analysis even if no box/ref/tuple is created. It is output-preserving. (b) A per-method merge-work budget (`-Yopt-analysis-budget`, `AnalysisBudget`, charged in `InitialProducerSourceInterpreter.merge`) makes the analysis-based local opts skip pathological methods (nested try/finally after inlining, big loops with many locals). The 2.13 size limits are only a static proxy (rank correlation about 0.7 in the Scala 3 measurements). Part (a) is a mechanical, safe backport; part (b) is a larger design change.
- verified: read 2.13.x BoxUnbox.scala around :183-200, :596-640, :695-815 and diffed against Scala 3 251900e615. Not benchmarked on 2.13.

### F4: `-Yprofile-trace` writes unescaped file/phase/symbol names into the JSON, so a name containing `"`, `\` or a control char gives an invalid trace file
- severity: low
- confidence: high (reproduced)
- s2: src/reflect/scala/reflect/internal/util/ChromeTrace.scala:144-156 (`str`/`str2`: "This assumes no escaping is needed") and :166 (`fld`); callers in src/compiler/scala/tools/nsc/profile/Profiler.scala:245-326 pass `file.name`, `sym.rawname`, `phase.name` unescaped
- s3 fix: 6f48c3962e 2024-11-12 "Ensure to escape characters before constructing JSON profile trace" (scala/scala3#21872, fixes #21858; adds profile/JsonNameTransformer.scala: escapes `"` `\` `\b \f \n \r \t`, and `\uXXXX` for chars <= 0x1F or > 0x7F; Profiler.scala:272/286/324/352 wrap names)
- what: names are written straight between double quotes. A source file named `a"b.scala` (legal on Linux/macOS), a backticked symbol, or a non-ASCII/control name produces trace JSON that Chrome/Perfetto cannot load.
- verified: `scalac -Yprofile-trace:t.json 'a"b.scala'` on 2.13.19-M1 gives a trace that `json.load` rejects ("Expecting ',' delimiter"). A backticked method name `a"b` did not show up in the trace in my small test (symbol events are only emitted for completions that happen), so the file name is the reproducible path. Repro: .../scratchpad/work/R/p3.

### F5: Class/jar writers follow symlinks at the output path
- severity: low (hardening; surprising overwrite of the symlink target)
- confidence: high (reproduced)
- s2: src/compiler/scala/tools/nsc/backend/jvm/ClassfileWriters.scala:276-277 (`fastOpenOptions`/`fallbackOpenOptions` on a `DirEntryWriter`: `CREATE`/`CREATE_NEW`, `TRUNCATE_EXISTING`, no `NOFOLLOW_LINKS`, no pre-delete); the same for `reflect.io.File.output` users
- s3 fix: f831f7df48 2026-10-16 "Do not follow symlinks when writing files" (scala/scala3#27311, fixes #27303). `FileWriters.scala:232` does `Files.deleteIfExists(path)` then `CREATE_NEW`; `io/File.scala:51-52` uses `LinkOption.NOFOLLOW_LINKS`. The commit notes that `NOFOLLOW_LINKS` alone would throw on an existing symlink, so deleting first is the only way to replace the link itself.
- what: if `-d out` already contains `Foo.class` as a symlink (stale build output, a hostile checkout, build caches that hardlink or symlink), scalac writes the new class bytes through the link and clobbers whatever it points to.
- verified: `out/Foo.class -> target.txt`, `scalac -d out Foo.scala` on 2.13.19-M1: target.txt now starts with `cafebabe`. Repro: .../scratchpad/work/R/s1. Note the fix has a (tiny) cost on every write (an extra `deleteIfExists`); Scala 3 accepted that.

### F6: "UTF8 string too large" is reported without a source position (and twice)
- severity: low (diagnostic quality; no crash)
- confidence: medium
- s2: src/compiler/scala/tools/nsc/backend/jvm/PostProcessor.scala:95-123 (`checkString` runs on the finished ClassNode, so it only has `NoPosition` and the class name); the first message "Error while emitting A$ / UTF8 string too large" comes from the class writer catch.
- s3 fix: 9717ee771e 2026-02-23 "Enforce JVM UTF-8 string limits in the backend" (scala/scala3#25300, Lukas Rytz; fixes #15850, #24597; checks at bytecode generation time in BCodeBodyBuilder/BCodeHelpers/BCodeSkelBuilder with a modified-UTF-8 length helper, BCodeUtils.scala:36-60)
- what: Scala 3 now reports a positioned error at the literal (tests/neg/i15850-a.check). On 2.13, `val s = "<70000 chars>"` gives two position-less errors: "Error while emitting A2$ / UTF8 string too large" and "Method <clinit> in class A2$ has a bad String constant of length 70000". The 2.13 detection itself is correct, including multi-byte strings.
- verified: six probes (ConstantValue field, `ldc`, pattern-match string label, 40000 x `é`) on 2.13.19-M1 all give the two position-less errors; no crash. Cosmetic.

## Already ported / not applicable

- Super-call receiver (53e59b1a81, scala/scala3#22628): 2.13 `BCodeBodyBuilder.scala:641` already passes `sup.tpe.typeSymbol` as the specific receiver.
- `==` via `Objects.equals` (17d48cfa40): 2.13 has it (aee8e55ade); `IINC` on callee parameters (9f78b660c9 / 1f91794775): in both.
- Int/Long shift semantics (722da71c24), NaN comparison code (10cc2f895d; Scala 3's was a ConstFold bootstrap bug), [SI-10032](https://github.com/scala/bug/issues/10032) / returns in finalizers (289e48e2d1, 955c560a15), `null.hashCode` (181885adf8; 2.13 compiles it and gives the right runtime NPE): ported from Scala 2 or not a Scala 2 bug. NaN truth tables for 16 double/float forms tested on 2.13: all correct.
- Empty `Class-Path` manifest crash (1279286828) and manifest `Class-Path` expansion (e93430ff5a): scalac does not follow jar manifests on `-classpath`; `ClassPath.manifests` is a different, opt-in path.
- JDK 13 `jrt` `toURI` (d0ca212d97) was a port from scala/scala#8210; `File.outputStream` append (88aa338664), `Path.parent` (f2937c3949, "based on 2.13"), pathSeparator recursion (9bc5452b81), `Jar` as `Iterable` (23f77c4fae): Scala 3 specific classes, 2.13 `reflect.io` is fine.
- ClassfileParser: PermittedSubclasses enum guard (3f3d4c84c7: direction Scala 2 to 3), missing annotation class silently skipped (6f32b62998, ported to 2.13 as 8d4ec394a0), cyclic/F-bounded inner signatures (46585c4703, bc71028ae5: commit text says "wasn't an issue in scala 2"), shaded-broken classfile messages (cecfa8c598), JDK 9+ constant-pool tags (7a5cb6e3d0 / 26852deba2; 2.13 has b7cdf27167), newer-JDK version error (f430e44986: 2.13 reads any version).
- JavaParsers: `override` as identifier (9f346d31aa), empty compilation unit (fbea0ff750), `int.class` / `X.class` in annotation values (5dfd0ccd4e, 07dce3f9f1), `@Ann` between array brackets (9c971be202), leading annotations (fdf73696ac; 2.13 has f9e0b1a03a), private interface methods, records, sealed. I ran the 2.13.19-M1 Java parser (`-Ystop-after:namer`) over all 712 non-neg `.java` files in Scala 3's tests/, sbt-test and compiler/test: no parse errors except 2 files in tests/disabled and tests/untried (casts like `(byte)1` in annotation values, intentionally broken Java).
- Java constants differential (about 50 forms, javac as oracle): only F2 differs in value; hex floats, `0b`, `_` separators and constant expressions are not folded in 2.13 (non-constant, but correct at runtime; already E-F6).
- XML: `<a/><!-- c -->` multi-element lookahead (77aa36303a / #16458) works on 2.13.18 with scala-xml 2.3.0 for comment, CDATA and PI after an element; whitespace-after-XML (624a6e0861) is a forward port of scala/scala#4185.
- Profile: ExtendedThreadMxBean on JDK 9+ (53060a3661) is a port of 30b3d58745; Thread.getId deprecation (55ed6fa2c6 / f7f51edb42).
- FormatChecker / StringInterpolatorOpt Scala 3 changes (74c83106dd, 547a186b53, dee5e7e4cf, 12b9217f9b, e6e549cffb): same author works on both compilers; behaviour is identical or only message-kind plumbing.
- Scala 3 specific (no Scala 2 analogue): generic-signature commits (ee36dd00db, 5b43bb9bda, 3527b6646f, 2e0fea3844, 4e07b523db, 8a32c2e553, e3f9fc1a6c, 466fa592b8), `isPrivate`/flagsUNSAFE static forwarder determinism (b9d85e049b), `javaFlags` ACC_STATIC on classes (5b27797fc2), synchronized boxing (3d33baef21), tailrec local slots, mixin-forwarder commits (9f20aa55eb forward-ported from scala/scala#8037), classfile-collision error (632278e9c7; 2.13 only warns for case-insensitive collisions, exact duplicates are caught by the namer), pipelining, Java TASTy, tasty-first classpath ordering.
- Optimizer features newer than the port (248321349d null/nonnull equality, a77b2c3d8a TupleN.apply, de3bf137fd trait forwarder inlining, da27cc73a0 ClassTag newArray, bef28058a6, ce87e1ff24, f8c6e8d95d): optimizations, not fixes; not listed.
- `LineNumber` de-duplication across `LabelNode`s (b9b3ed5a12, #15107): the 2.13 `lineNumber` (BCodeSkelBuilder.scala:497) only looks at `lastInsn`. I could not find a 2.13 shape that yields two entries with the same start_pc (match, nested match, inlined `@inline` forwarder), so not listed.

## Method notes

- Took every s3 commit since 2021 (and the keyword-matching older ones) for the in-scope paths (`git log -- <paths>`, about 800 subjects filtered by fix/crash/regression/issue keywords plus a manual read of the rest), then classified each as: Scala 3 specific, forward-port from Scala 2 (the C/D/E/F/G reports), or candidate. Candidates were verified by reading the 2.13.x file at `origin/2.13.x`, then run on a released 2.13.19-M1 (scala-cli, JDK 21) wherever a runtime or compile-time symptom was expected. Nothing in either repo was modified.
- Direction of travel: for ClassfileParser, JavaParsers, FormatChecker, CommandLineParser, Chars and the classpath/IO code the fix flow since about 2024 has been largely Scala 3 to Scala 2 (often by the same authors: som-snytt, lrytz, Solal Pirelli), and the cross-checks found them already landed. The yield is concentrated in (a) the optimizer, ported to Scala 3 in February 2026 and developed further there (F1, F3), (b) code Scala 3 reimplemented independently (profile JSON, FileWriters, JavaScanners `getlitch`), (c) diagnostics (F6).
- Throwaway harnesses: Java parser sweep (`java -cp scala-compiler/reflect/library 2.13.19-M1 scala.tools.nsc.Main -Ystop-after:namer @files`), Java constants differential vs javac with a stub `Z` class on the runtime classpath to prove that a value was inlined: .../scratchpad/work/R/{jp,j2,j3,j4}.
- Not done: reverse comparison of `GenericSignatures` shapes beyond the C report's 25 shapes; full ClassfileParser fuzzing on JDK jars; `DirectoryClassPath`/`ZipArchive` performance changes (Scala 3 perf work around Windows `isDirectory` caching and JrtClassPath caching is Dotty-specific regressions).
