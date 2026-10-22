# F-classfile-pickle

## Pairs

| s3 file | s2 file | port date | later s2 commits | notes |
|---|---|---|---|---|
| core/classfile/ClassfileParser.scala | nsc/symtab/classfile/ClassfileParser.scala | 2013-02-15 (5e219c1d84) | ~190 non-merge (2013-02..2026-10) | Heavily rewritten (lazy MemberCompleter, TASTy/Record/Permitted support); many 2.x fixes independently re-implemented in s3 (SI-9937, SI-7455, SI-9268, CONSTANT_Dynamic, PermittedSubclasses, JDK21 missing param name index 0). |
| core/classfile/ClassfileConstants.scala | reflect/ClassfileConstants.scala | 2013-02-06 | 14 | Numeric constants diffed: identical (only s3 adds JAVA8_MAJOR_VERSION). |
| core/classfile/ByteCodecs.scala | reflect/pickling/ByteCodecs.scala | 2013 | 8 | Logic identical (diff -w shows only docs/headers). |
| core/classfile/DataReader.scala | nsc/symtab/classfile/{DataReader,ReusableDataReader,AbstractFileReader}.scala | 2013-01-31 | 4 | s3 has no buffer reuse (see F3). AbstractFileReader/ReusableDataReader/ClassfileTastyUUIDParser do not exist in s3. |
| core/unpickleScala2/Scala2Unpickler.scala | reflect/pickling/UnPickler.scala | 2013-02-15 | ~70 | Reimplemented on denotations; stub/MissingRequirementError machinery replaced by Scala 3 stubs. |
| core/unpickleScala2/PickleBuffer.scala | reflect/pickling/PickleBuffer.scala | 2013 | 12 | Read/write logic identical; only growth strategy differs (irrelevant). |
| core/unpickleScala2/PickleFormat.scala | reflect/pickling/PickleFormat.scala | 2013 | 5 | LITERALsymbol (37) not present in s3, not needed (see below). |
| core/unpickleScala2/Scala2Flags.scala (+ flag maps in PickleBuffer.scala) | reflect/Flags.scala | 2013 | n/a | Only bits < 48 mapped; JAVA_ENUM/JAVA_ANNOTATION/SYNTHESIZE_IMPL_IN_SUBCLASS (bits 48-50) are not pickled in Scala sigs. |
| core/unpickleScala2/Scala2Erasure.scala | reflect/transform/Erasure.scala | 2021 (written against v2.13.5) | 4 since | intersectionDominator body re-compared with current 2.13.x: same algorithm. |
| core/SymbolLoaders.scala | nsc/symtab/SymbolLoaders.scala | 2013-01 | ~60 (almost all perf/refactor/ReusableInstance) | Scala 3 specific design; no correctness commit applies. |
| util/ShowPickled.scala | nsc/util/ShowPickled.scala | 2013 | 10 | debug tool only. |

## Findings

### F1: MethodParameters attribute count read as signed byte; desynchronises attribute parsing for >=128 params
- severity: low
- confidence: high
- s3: compiler/src/dotty/tools/dotc/core/classfile/ClassfileParser.scala:1013 (`val paramCount = in.nextByte`)
- s2: src/compiler/scala/tools/nsc/symtab/classfile/ClassfileParser.scala (MethodParametersATTR branch uses `u1()`, unsigned); no dedicated fix commit, s2 was simply written correctly (c78d771e6f SI-9437 / 6587b19f6f)
- what: `parameters_count` in JVMS 4.7.24 is a u1, but `DataReader.nextByte` returns a signed Byte. For a method with 128..255 parameters (compiled with `-parameters`) the count is negative, the `for i <- 0 until paramCount` loop does not run, and none of the attribute's bytes are consumed. `parseAttribute` has no `in.bp = start + attrLen` resync, so the following attribute name/length is read from the middle of the MethodParameters payload, which yields garbage or a bad-constant-index exception. Rare (needs >127 params AND -parameters) but the failure mode is a classfile read error.
- verified: read the branch; `DataReader.nextByte = bb.get` (signed). Did not compile a repro.

### F2: Constructor param names from MethodParameters are shifted by one for inner (non-static) Java classes
- severity: low
- confidence: medium (reading only; not run)
- s3: compiler/src/dotty/tools/dotc/core/classfile/ClassfileParser.scala:1012-1019 (namedParams keyed by MethodParameters index) used in `fillInParamNames` at ~:965
- s2: src/compiler/scala/tools/nsc/symtab/classfile/ClassfileParser.scala ; fix commit: 954ea7e2e9 2017-03-17 "SI-10231 Skip outer parameter when classfile parsing java param names"
- what: For an inner (non-static) class constructor the descriptor/MethodParameters include the synthetic/mandated outer-instance parameter first. s3's `normalizeConstructorParams` (and the Signature attribute, which omits it) strips the outer param from the Scala-side type, but `namedParams` is indexed by the raw MethodParameters position and applied by `namedParams.getOrElse(n, oldp(n))` with the stripped index. Result: parameter 0 gets the name of the outer param (`this$0`, which is flagged MANDATED not SYNTHETIC so it is recorded), parameter 1 gets name 0, etc. Observable: wrong parameter names for named-argument calls to Java inner-class constructors compiled with `-parameters`. s2 test: test/files/run/t10231.
- verified: checked s3 `parseAttributes`/`AttributeCompleter.complete`/`normalizeConstructorParams` ordering (descriptor type -> strip outer -> overwritten by Signature -> fillInParamNames); no offset adjustment anywhere; `git grep SI-10231|hasOuterParam` finds nothing in s3. Also checked s2 fix 6587b19f6f (names survive Signature): s3 handles that by design (applies after sig).

### F3: No classfile read-buffer reuse (ReusableDataReader not ported)
- severity: low (perf)
- confidence: medium
- s3: compiler/src/dotty/tools/dotc/core/classfile/DataReader.scala:10-19
- s2: src/compiler/scala/tools/nsc/symtab/classfile/ReusableDataReader.scala ; commits: 30a688cc90 2019-02-13 "Reuse the buffer for classfile reading", 2826c6cb96 2019-11-25 "Avoid temporary byte arrays when reading .class/.sig or source files from VirtualFile", 2ccc7489a2 2020-04-23
- what: s2 reads each classfile into a reusable (power-of-two sized) buffer held by a `ReusableInstance`, allocation-free per class. s3 does `ByteBuffer.wrap(file.toByteArray)` plus a new anonymous `InputStream` and `DataInputStream` per `DataReader`, i.e. one array (+ jar entry copy) and 3 objects per loaded class. Likely a small allocation/GC share of classpath-heavy compilations; not measured here.
- verified: grep for ReusableDataReader/ReusableInstance in s3 compiler/src: none.

### F4: Kotlin-style `abstract final` class flags not reset
- severity: low
- confidence: low
- s3: compiler/src/dotty/tools/dotc/core/classfile/ClassfileParser.scala:358,394 (`classRoot.setFlag(sflags)`)
- s2: src/compiler/scala/tools/nsc/symtab/classfile/ClassfileParser.scala:539-541 ; fix commit: a03973a353 2025-07-16 "Reset disallowed FINAL in ClassfileParser" (scala/bug#13110, test run/t13110)
- what: s2 now clears FINAL when a class read from a classfile is also ABSTRACT (JVMS forbids the combination, Kotlin interop produced it, and the s2 backend asserted on the InnerClasses flags). s3 translates ACC_FINAL and ACC_ABSTRACT independently, so such a class would carry Final|Abstract. s3's backend may not trip, so the consequence in s3 is uncertain (possibly spurious "cannot extend final class" on Kotlin-compiled abstract/final classes).
- verified: `classTranslation` has no combination handling; no `Kotlin` mention in s3 ClassfileParser. Did not try the repro.

### F5: Local dummy / `<local child>` symbols entered into the owner's scope when unpickling Scala 2
- severity: low
- confidence: low
- s3: compiler/src/dotty/tools/dotc/core/unpickleScala2/Scala2Unpickler.scala:507-537 (`finishSym` / `canEnter`)
- s2: src/reflect/scala/reflect/internal/pickling/UnPickler.scala ; fix commits: 14fa7bef12 2014-07-01 SI-8708 (don't enter LOCAL_CHILD in owner scope), bcd1e4f3a3 2014-09-30 SI-8868 (local dummy symbols: create via newLocalDummy, don't enter)
- what: s2 explicitly keeps `<local child>` classes and `<local Foo>` dummy owners out of the member scope of the enclosing class. s3's `canEnter` filters refinement classes, roots, existentials, case-accessor privates and `$extension`, but not these names, so a sealed Scala 2 class/trait with local children exposes a member named `<local child>` in its decls (TypeOps.scala:762-771 handles the name specially when computing children, so the type is known to occur). Likely harmless except for decls listings / -Ycheck style tools; no known user-visible failure.
- verified: grep `LOCAL_CHILD|isLocalDummy` in s3: only StdNames/TypeOps; not filtered in Scala2Unpickler.

## Already ported / not applicable
- Constant pool tag sizes incl. CONSTANT_MODULE/PACKAGE/DYNAMIC/INVOKEDYNAMIC/METHODHANDLE (b7cdf27167, e302f77d7b): present (ClassfileParser.scala:104-117).
- JDK 21 MethodParameters name index 0 (52ce4b1ad9, bug 12783): handled (`if index != 0`).
- PermittedSubclasses -> Sealed + Child (5849a58460): done, with enum-specific refinement.
- Skip annotation if class missing, silently like javac (8d4ec394a0, f768c54142): done (`StubInfo` check in `parseAnnotation`); direction is Scala 3 -> Scala 2 (s2 commit message says "like javac and Scala 3").
- SI-9937 inner class lookup when InnerClasses entry missing (04d4f4c61a): done in `classNameToSymbol` (comment cites bug 9937 / dotty#12086).
- SI-7455 drop trailing dummy arg of synthetic access ctor: done in `normalizeConstructorParams`.
- SI-9152 / b94afc7086 direct reading of Java inner class files with outer type vars: s3 `addEnclosingTParams()` seeds `classTParams` from enclosing classes; only a hard `assert` remains for unknown `T..;` if not isParent (unreachable for normal inner classes).
- Java 8 default methods via Code attribute (SI-7398): done; static interface methods live in the module class so are unaffected.
- SI-9915 modified UTF-8: `readUTF` used. ByteCodecs decode (0xC0 0x80 and 0x7f regeneration): identical code.
- ConstantValue for boolean/byte/char/short stored as CONSTANT_INTEGER: `convertTo` special-cases Boolean; others via Constant.convertTo.
- Pickle version check, PickleBuffer read/write numerics, Flags bit positions below 48: identical to s2.
- LITERALsymbol (993ab3d515): s2 now marks it "never pickled, to be dropped"; no need.
- Param-accessor alias disambiguation with overloads (7321c7abe0): s3 disambiguates with `info.matches(memberInfo(alt))` on `ParamAccessor`/SuperAccessor, not `=:=`; the s2 failure mode does not obviously apply. Not tested.
- SI-7596 (singleton type to overloaded symbol): s3 uses `readDisambiguatedSymbolRef(_.info.isParameterless)`.
- Erasure.scala changes since 2021 (e1f22aedb1 erase constant type, 5f24a827b8 bridge check): backend/Scala 2 specific, not relevant to Scala2Erasure.
- SymbolLoaders: all s2 post-2013 commits are perf/refactor/reflection-runtime; no correctness fix to port.

## Method notes
- Sim table shows low exact-line similarity (ClassfileParser 0.07, Scala2Unpickler 0.12) because both files were rewritten in Scala 3 style; comparison was done via s2 commit history, not diffs. Both areas are actively maintained in Scala 3 (many s2 fixes were re-implemented independently, a few flow Scala 3 -> Scala 2), so the yield of "missed fix" findings is low.
- ByteCodecs/PickleBuffer/ClassfileConstants were diffed mechanically (identical modulo docs/headers; constants table diffed with grep+diff).
- Nothing was compiled or run; findings F1/F2 are by code reading only. A repro for F2 needs javac `-parameters` with a non-static inner class ctor; for F1 a method with 130 params.
