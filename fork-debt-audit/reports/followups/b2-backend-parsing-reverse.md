# Follow-up check: batch b2-backend-parsing-reverse

Checked 2026-10-22 against origin/2.13.x (local clone) and scala3 origin/main a98b104aaa. gh search was rate-limited at times and phrase-matches multi-word queries, so "none found" for issue searches means a handful of keyword queries plus the local scala-zinc index, not exhaustive.

### C1
- verdict: clean
- refs read: https://github.com/scala/scala/pull/5543 (approved by lrytz; fixes array-set `genLoad(a2)` -> `genLoad(a2, elementType)`; the PR also fixed the same bug in primitive `unbox` emission, a second site beyond what the finding lists; tests t10069, t10069b); https://github.com/scala/bug/issues/10069 (original crash report, nothing more).
- follow-ups: none to the fix itself. 3e8293203b (2018-09-14, "fix BType.conformsTo, update BCode.adapt") later reworked `adapt`, which the fix relies on for the ATHROW-of-Nothing$ trick. Related closed bug: scala/bug#8891 (`???` assigned into primitive Array; the same family, closed). No scala3 issue found for the VerifyError.
- porting note: Take both hunks of 880aec04e8 (array set and the unbox site) and the two run tests. The behaviour depends on the backend's `adapt` emitting ATHROW for Nothing$ -> primitive, so check Scala 3's coercion path does that rather than just passing the type.

### C2
- verdict: clean
- refs read: PR 6284 (deprecated flag on static forwarders, fixes bug#10701; approved, no caveats); PR 6219 (parameter names, fixes bug#10650; approved, test failures reported as unrelated); PR 9561 (LocalVariableTable for mirror methods, fixes bug#12362; lrytz pushed cleanups).
- follow-ups: none to these changes in 2.13.x (git log on BCodeHelpers shows only 2c1ca22ad7 itself). In 2.13 PR 11289 (2026-10-15) states "parameter names of forwarders to Java methods still differ (x$1 vs the source name) unless compiled with -parameters" as intended. No scala3 issue found for forwarders lacking MethodParameters/deprecation/LVT; scala3's recent forwarder work (#26510, #25879, #24916 "also copy param annots at mixin") is about mixin forwarders, not static ones.
- porting note: Three independent upstream commits (flags, MethodParameters, LVT); none of them handles parameter annotations on static forwarders, so the "params = Nil TODO" is not a missed port of these PRs (I did not verify what 2.13 does for annotations).

### C4
- verdict: caveat
- refs read: PR 7975 / commit 659dba63c3 (2.13) and PR 7977 / 9c57e78be5 (2.12 backport). Despite the finding text, the diff is about constructors: it drops the synthetic outer param from the constructor's generic signature, skips the result type in `NeedsSigCollector`, and so omits signatures for anonymous-class constructors. It does touch `classSig` only to pass `sym` to `needsJavaSig(sym, preRebound, Nil)`; the `pre.baseType(sym.owner)` rebind itself is the 2009 fix for bug#2585 (3a75338448). In the 7977 thread lrytz noted constructors need special-casing (return type is void) and jroper asked about binary compatibility of the changed signatures.
- follow-ups: none found in 2.13 on this code. scala3: ee36dd00db "Generic signature fixes" (#25880, 2026-05-26) accidentally removed the `pre.baseType(sym.owner)` rebind; 5b43bb9bda (#26549, 2026-07-16, fixes #26532) restored it, citing bug#2585. Also #26202 (2026-06-01, crash for inner classes of type aliases). Open: scala3#25887 (raw vs wildcard in signatures), #24275, #27307. No issue found reporting the dropped outer type args for `Outer[String]#Inner[Int]`.
- porting note: This area has just been churned in scala3 (#25880 -> #26549). The fix for the C4 symptom must keep the prefix rebinding and also handle AppliedType with a non-trivial prefix; add the test shape `Inner[T]` inside `Outer[U]`, which neither upstream test (t10880, t10889) covers.

### C3
- verdict: clean
- refs read: PR 10735 (sjrd; adds POP after impure static-field qualifier; test is a macro imitating a plugin; approved; moved to 2.13.14, no 2.12 backport seen).
- follow-ups: none found. `git log` on BCodeBodyBuilder since 2024-04 shows nothing touching it.
- porting note: The 2.13 test is a macro generating the tree; a Scala 3 port needs the same synthetic-tree approach (source cannot reach it, as the finding says). Low priority.

### C5
- verdict: caveat
- refs read: scala3 PR 27311 (f831f7df48; fixes #27303 SemanticDB AccessDeniedException through a symlink to sbt's CAS; tested manually only; approved by tgodzik/SolalPirelli; author notes "not great having to do it in multiple places; #26512 [unified IO layer] should help"); scala/scala PR 6673 (1ceafd2275: CREATE|TRUNCATE_EXISTING to avoid a double open on non-Windows, ~1.5% on scalap corpus; nothing about races).
- follow-ups: none found for the case-collision race. Relevant context: PR 26382 (parallel class writing, 2026-09-30) kept the case-collision warning (tests/warn/case-collision.check; lrytz commented on the position of that warning). #27311 touches DirEntryWriter, File.scala and ExtractSemanticDB only. No issue found reporting "Error writing" under a collision.
- porting note: Deleting then CREATE_NEW is not atomic across threads; if two classes collide case-insensitively the second delete+create can see FileAlreadyExistsException. A fix should be a retry or collision-aware key, not a revert to TRUNCATE_EXISTING (that reintroduces symlink-following, see R5).

### E1
- verdict: caveat
- refs read: scala/scala PR 10024 (som-snytt; "improve lookahead during java textblock", fixes bug#12575: the old save/restore lost a character; uses a lookahead scanner). Its text says the Scala 2 fix is the complement of dotty's. Nothing about unclosed text blocks.
- follow-ups: scala3 forward-ported text blocks in #14882 (2022-06, 1dc5fcac04) from 2.13's original a8225a093d; scala3 #18491 (1be790cfc5, 2023-09) fixed the NUL skip, citing 10024 as the Scala 2 counterpart. No 2.13 follow-up to JavaScanners after 4a918afd51 touching text blocks. No scala3 issue found for unclosed `"""` crashing (searched; #18490 and #18586 are different bugs).
- porting note: Porting 4a918afd51 alone is not obviously enough: the finding is that the loop guard reads the outer scanner's `ch` while the lookahead advances. Check the 2.13 loop has the same shape (it was refactored in the same commit) and add a `"""` EOF test.

### E2
- verdict: amended
- refs read: PR 10017 (lrytz; errors on Bidi_Class chars; sjrd requested changes then approved; note that 2.12 backport must let `\u` escapes through in string/char literals, error only on raw code points); PR 10030 / e5883add4f (SethTisue, 2022-05-09): "we were flagging too many characters; a false positive came up in the community build". Removed U+061C and U+200E/U+200F; the test now has a comment with LRM/RLM (Latin etymology line "coævus ... com- ‎(...)"); remaining set is U+202A-202E and U+2066-2069. bug#12478 is the CVE-2021-42574 report.
- follow-ups: d874f79719 / PR 10197 (2022-10-31) spec change: no Bidi_Class characters anywhere in source, so also in comments and identifiers. No later change to `Chars.isBiDiCharacter` in 2.13. No scala3 issue found (searched "bidirectional", "Trojan Source").
- porting note: Port the loosened set from e5883add4f, not the original fd97b40579 set (a port of only the first commit would reject LRM/RLM/ALM in comments). The check applies to raw characters in comments, strings, chars and identifiers but not to `\u` escapes.

### E3
- verdict: clean
- refs read: PR 3285 (som-snytt; "Count lines by EOLs": FF no longer a line break for `pos.line`; "Unicode processing now precedes line ending processing"; second commit 8be560a1cf prints whole line content with control chars as unicode escapes; densh/paulp/adriaanm LGTM). bug#8015 root cause: `isLineBreak` in StripMarginInterpolator treated '\f' as a break.
- follow-ups: SourceFile later tweaks ([SI-8205](https://github.com/scala/bug/issues/8205) 2014 CR handling, 5fc33170c5 ArrayBuilder, 0dceecab19 unboxed) none revert it. scala3 #27292 (ffdc25bf46, 2026-10-15) knowingly keeps `\f` as a line break: its description says dotc uses `isLineBreakChar` "so `\f` also ends a line; scala2's line indices only count CR/LF". So the divergence is documented there, not accidental.
- porting note: Fix has two parts: line-index table uses CR/LF only (SourceFile) and CharArrayReader/Scanners distinguish "line end" from "line break". Check `isLineBreakChar` users in the scanner (newline tokens) before narrowing it; the #27292 test pins current behaviour and will need changing.

### E5
- verdict: caveat
- refs read: PR 4306 ([SI-3368](https://github.com/scala/bug/issues/3368): PCData nodes; `-Yxml:coalescing`; also fixes rejecting consecutive CDATA sections; fixup rounds for pattern-matcher/t5699 failures); PR 4451 (498a07fcd8: "default to coalescing for 2.11": preserve the old behaviour for 2.11; coalescing on by default unless source level is 2.12). 
- follow-ups: 2.13 today: `XxmlSettings.isCoalescing` is only true if `-Xxml:coalescing` is passed (ScalaSettings.scala:259); man page still says "default in 2.11". So in 2.13 the default is PCData (round-trips), while Scala 3 always coalesces (what 2.11 did). 5bcc7222b0 documents the flag. No scala3 issue found.
- porting note: The target behaviour is the 2.13 default (PCData unless `-Xxml:coalescing`), not the 2.11 one. A port needs the setting plus the coalesce pass in the parser, and scala-xml's `PCData` must be available on the classpath.

### E4
- verdict: clean
- refs read: PR 4214 (retronym; "don't consume literal brace as Scala pattern"; previously a leading space let the text parser handle it; LGTM, no caveats); bug#5154.
- follow-ups: none found in 2.13 MarkupParsers (only position fixes 85136e3f25, d6d3a07874). The scala3 MarkupParsers xPattern issue is not filed that I could find; scala3 only has #15635 (xml literal span fix, 2026-04).
- porting note: Small change in 2.13's `xPattern`: check `xCheckEmbeddedBlock` before treating `{` as an embedded pattern; take its test (t5154-style `case <z>{{3}}</z>`).

### E8
- verdict: caveat
- refs read: scala/scala PR 11289 (2026-10-15, lrytz, "Make forwarder signatures independent of the compilation batch"; approved). Second half: JavaParsers now emits `@throws[T]()` for source throws clauses, with a thrown type variable replaced by its erasure; motivation is forwarders to Java default methods getting an Exceptions attribute (and a `^` in the generic signature) only for classfile-loaded Java. Test is DeterminismTest (compiles Java with javac). Note: the same PR's first half (mixin forwarder type-param renaming) is independent.
- follow-ups: none yet (4 days old). Same PR says forwarder parameter names for Java methods are intentionally left differing.
- porting note: Take the type-variable-erasure rule, otherwise `throws E` with type var E creates a bad annotation; Scala 3 already represents classfile throws via ThrowsAnnotation, so source needs to build the same. Effect on Scala 3 unverified.

### E6
- verdict: unknown
- refs read: none given. Searched. Open: scala/bug#10410 (JavaParsers can't fold constants in static final fields), scala/scala PR 11290 (2026-10-09, open, "Give Java fields initialized with constant expressions a constant type", fixes 10410 and "the Java side of scala/bug#6124 (underscores in numeric literals)"; it touches JavaScanners.scala, JavaParsers.scala, new JavaConstantFolder.scala, Namers; motivated by Zinc determinism noise). Prior art: scala3 #7483 (port of 2.13 literal constant types, 2019), scala/bug#3236/#5333.
- follow-ups: scala3 has no matching issue that I found for 1_000/0b101/hex floats in Java sources.
- porting note: Wait for/ride on 11290; if it merges, forward-port JavaScanners number scanning with it. Its tests (JavaConstantsTest, JavaConstantsPropertyTest, t3236-neg) are useful for Scala 3.

### E7
- verdict: caveat
- refs read: scala/scala PR 10734 (lrytz, f9e0b1a03a, "Fix Java parser to handle leading annotation"; som-snytt: forward port skips the package-info check because "it is javac's job", and "the special weirdness on dotty is because the annotations fix(es) weren't ported"; SethTisue notes the Scala 3 forward-port is scala3#20064). 
- follow-ups: scala3 #20064 was closed unmerged (2024-09-15); the fix landed later as fdf73696ac "fix: Java leading annotation parsing" (2026-03-12, test i20063). The existing scala/bug#4071 ("unable to parse java annotations in a file in the empty package with no imports") has retronym's reproducer `@X public @interface Annot {}` + `@interface X {}`, closed by 10734; SethTisue wrote there that Scala 3 still had the issue (3.7.0-RC1). So the finding (an `@interface` first in the default package) is the same family as 4071 and probably not covered by 10734 either.
- porting note: Test the 4071 reproducer (annotation-type declaration first in the empty package) against 2.13 before filing; the leading-annotation prologue consumes `@` then fails on `interface` in both compilers.

### D1
- verdict: caveat
- refs read: none given (port divergence). The 2.13 code comes from 6551d25b7d (PR 10404, som-snytt, fixes bug#12787: IterableOps.head was knocked out by abstract LinearSeqOps.head) and 4499009fd5 (PR 10415, 2.12 backport). 2.13 `findInInterfaces` iterates lazily with `if !visited(i)` inside the loop; scala3's strict `.filter(!visited(_))` runs before any visit, which matches the finding. lrytz noted the earlier non-local-return version "slightly more straightforward".
- follow-ups: none in 2.13 since 2023-05 on this method. In scala3 no commit since the port (5e9465900b, 2026-02-09) changes `findInInterfaces`; BCodeRepository commits after were refactors (#25618, #25747, #26313, #26712, #26328). No scala3 issue found.
- porting note: Fix by checking `visited` lazily (e.g. a loop or `iterator.filter`), not by an up-front filter. Add the test shape from the finding (`implements A, B` with `A extends B` vs the opposite order).

### D2
- verdict: caveat
- refs read: none given. 2.13 (same PRs 10404/10415) uses `otherTp.isSubtypeOf(ownerTp).get` on BTypes for maxSpecific, i.e. transitive; scala3 uses `otherOwner.interfaces.contains(owner.name)`, direct only. PR 10404 says nothing about this; the TODO comment in the code says non-Scala bytecode is the only case since Scala emits mixin forwarders.
- follow-ups: none found.
- porting note: Use a transitive subtype check (scala3's BTypes should have an `isSubtypeOf` for ClassBType; verify it works for classfile-loaded types without forcing missing classes). Same test as D1.

### D4
- verdict: caveat
- refs read: none given. In scala3 `// OptimizerUtils.clearMaxsComputed(method)` is still commented at LocalOptimizer.scala:374; the only repo mention of `maxsComputed` is that line.
- follow-ups: commits touching LocalOptimizer since the port: #26382, #27214, #27290 (adds MethodFingerprint, which covers `maxLocals`/`maxStack` and was designed to detect in-place edits); MethodMax touched by #26826, #26772, 5bdab71269 only. 9f78b660c9 (#27316, port of scala/scala#11299, IINC as a write) is a related correctness fix in the same area but not the maxs cache.
- porting note: Either restore a clear function and call it after mutating, or make the maxs cache keyed by instruction-list fingerprint. #27290's fingerprint already knows when a method changed, so it could invalidate the cache.

### D5
- verdict: caveat
- refs read: scala/scala PR 11327 / c8bc25a6d0 (2026-10-19, ASM 9.11.1; body empty): ASM 9.11 limits work/memory per method when computing frames and analysis. 2.13 lifts both: `ClassWriterWithBTypeLub` calls `setComputeLimits(Int.MaxValue, Long.MaxValue)` and `AsmAnalyzer` calls `analyzer.setComputeLimits(...)`; huge methods (neg/inlineMaxSize) failed with "Too many allocated bytes".
- follow-ups: none. scala3 already sets `writer.setComputeLimits(100*1024*1024, 1_000_000_000)` in CodeGen.scala:250 for the ClassWriter, but nothing for `analysis.AsmAnalyzer`; scala3 project/Dependencies.scala has asm 9.11 for the compiler. #27290 (analysis budget) and the Limits caps mean the analyzer limits are probably not reachable.
- porting note: Cheap to add `setComputeLimits` on the analyzer; no known failure so far. The ClassWriter limit is a finite value in scala3, not unbounded; revisit only if a "Too many allocated bytes" report appears.

### D3
- verdict: caveat
- refs read: none given. scala3 #26997 (69251c8566, 2026-09-08, "addLambdaDeserialize during classnode creation", co-authored by lrytz) moved the table construction from PostProcessor into BCodeSkelBuilder; its description states "this info won't change during inlining because we can move INDYs but not change what they point to". That is the assumption D3 challenges: 2.13 tracks `indyLambdaImplMethods` and builds `$deserializeLambda$` after the optimizer precisely because inlining an invokedynamic into another class needs an entry in that class's table.
- follow-ups: none found; the tested configuration was `-opt -opt-inline:**,!java.**` with no failures. No scala3 issue found for deserializeLambda with the optimizer (27223 is about InlineInfo, unrelated).
- porting note: It holds as long as lambda bodies are private static and cross-class inlining of them is refused (as the finding says). If that restriction changes, this becomes a real bug; a regression test would be: serializable lambda inside an inlined method deserialized in the other class.

### D6
- verdict: unknown
- refs read: none given. OptimizerSettings/OptimizerWarning last changed by #27290 (settings) and #26650, #26236, #26772, #25618 etc. (refactors); no commit touches `no-inline-mixed` or `definedInJavaSource`.
- follow-ups: none found; no scala3 issue.
- porting note: The finding is dead config plus a lost message detail; just wire it up or delete it.

### R1
- verdict: caveat
- refs read: scala3 commit 730eefa650 (2026-02-16; part of PR #25165 "Port the Scala2 optimizer", merged 2026-03-16); no PR of its own. scala/bug#11812 "Inlining doesn't respect JPMS access restrictions" (open since 2019): lrytz "we don't have the infrastructure in place to check module access restrictions, so this is on hold" (related scala-dev#529).
- follow-ups: none to the module logic in scala3 except classpath refactor #26313 (3718b0cf7e, 2026-06-12) and BCodeRepository/Inliner refactors. No 2.13 PR for modules found (searched PRs; recent 2.13 optimizer work: ada4c36996 IINC, c8bc25a6d0 ASM). 
- porting note: Reverse port needs the whole classpath plumbing in 730eefa650 (module-info reading in DirectoryClassPath/ZipAndJar lookup, ClassPath, BTypes accessibility), not only the inliner check. Only unconditionally exported packages (no `exports ... to`) should count as accessible. Would close bug#11812.

### R2
- verdict: caveat
- refs read: scala3 PR 18491 (som-snytt; fixes #18490, which was `'\0'` in a Java source crashing JavaParsers.tryConstant with StringIndexOutOfBounds; replaced the NUL skip sentinel with a `skip` flag; jchyb nits; test t12290 changed; PR text says "Scala 2 previously received the complementary fix" meaning 10024) and backport #20672 (LTS, community build no regressions).
- follow-ups: none to the fix. The 2.13 bug is the same one: no scala/bug issue found for NUL in Java text/char literals. The related #10024 (see E1) handled a different loss (character before an escaped EOL).
- porting note: Port the flag approach, not just `'\0'` handling, and the test: a Java `static final String`/char constant with `\0` read from source, plus the text-block `\<newline>` continuation case so skip semantics are kept.

### R3
- verdict: caveat
- refs read: scala3 PR 27290 (251900e615, merged 2026-10-16, fixes #27289): three parts; the first ("check the class first" before forcing the prod-cons analysis in box-unbox; the finding's mechanical guard) is output-preserving, box-unbox CPU 2.0s -> 0.5s; the second is the per-method budget `-Yopt-analysis-budget` (default 10M) which skips 22 of ~46k library methods; the third is the MethodFingerprint assertion (< 3% of local-opt time, left on). lrytz/SolalPirelli reviewed; retronym kept `Limits` since nullness/aliasing/BasicValue do not charge the budget. Caveat from the PR: `Using.resources` is ~18% slower when optimized (C2 "hot method too big"). PR 27214 (9cf6109dad, lazy analyzers; 1-2% on areWeFastYet), 6f1c8580d0 (2026-03-02, lowered max instrs/locals to 3000/300..600).
- follow-ups: none since 2026-10-16 touching BoxUnbox, LocalOptimizer, Limits or AnalysisBudget (git log origin/main). 9f78b660c9 (#27316) is a separate IINC fix.
- porting note: Backport only the class-check guards (BoxUnbox arms 191/703/762/811) as the safe part. The budget needs the merge-charging interpreter and the fingerprint assert to be safe, and 2.13 has no benchmark of this yet.

### R4
- verdict: caveat
- refs read: scala3 PR 21872 (6f48c3962e, merged 2024-11-12, fixes #21858): new JsonNameTransformer escapes names for the trace JSON; reviewers prolativ/mbovel: "unused" cleanup and mbovel changed the escape condition to `c <= 0x1F || c >= 0x7F` (DEL is also escaped; Node/Python do), comment "crazy we need a JSON escape function in 2024".
- follow-ups: none to the escaper. JsonNameTransformer survived the profile package refactors (#26175, 86f302eb48 and others). No scala/bug issue found for ChromeTrace escaping.
- porting note: Take the final version of the escaper (with the 0x7F bound), not the first revision; escape in every name written (source file, phase and unit names).

### R5
- verdict: caveat
- refs read: scala3 PR 27311 (see C5): deletes first, then CREATE_NEW, NOFOLLOW only where meaningful; manual test only (class symlink and .tasty symlink); issue #27303 had already been closed as a dupe of an sbt issue but the PR author kept the compiler-side fix; fredDJSonos asked if `.tasty` is covered (answered by the manual test). Cross-referenced WIP #26512 (unified IO layer).
- follow-ups: none since merge; the C5 race is the known side effect.
- porting note: 2.13's `TRUNCATE_EXISTING` open (from PR 6673, a perf optimization) is what follows symlinks; changing it costs the double-open saving that PR measured (~1.5% on scalap, Mac). The jar writer path and any `.tasty`/other writers need the same treatment.

### R6
- verdict: caveat
- refs read: scala3 PR 25300 (9717ee771e, merged 2026-02-23; fixes #15850, #24597; subsumes #19622): checks at bytecode generation time and reports with a position. Review notes: author wrote own Java "modified UTF-8" length counter because ASM's is private; lrytz asked for a spec reference and corrected 4 bytes/char to 3, and a late fix handles NUL (2 bytes in modified UTF-8). Applies to method names + descriptors + signatures too, with a helper split by lrytz request. scala/bug#7527 "Very large string literals ..." (closed 2026-04-02) shows 2.13.18 output "UTF8 string too large" plus a separate "bad String constant of length 52705" error, i.e. the two position-less errors in the finding.
- follow-ups: none to the scala3 change since merge (no later commit mentions it).
- porting note: Take the complete counter (modified UTF-8: NUL is 2 bytes; max 3 bytes per Java char) and the checks for strings, names and signatures; don't reuse ASM's internal check.
