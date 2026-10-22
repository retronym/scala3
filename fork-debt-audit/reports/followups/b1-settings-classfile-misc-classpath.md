# Follow-up check: batch b1-settings-classfile-misc-classpath

Sources: gh (scala/scala, scala/bug, scala/scala3, scalameta/metals), git log of /Users/jzaugg-virtus/code/scala/scala (origin/2.13.x @ a0ebc676ba, 2026-10-20) and the scala3 worktree. Scala 3 issue searches for every id returned nothing unless stated.

### B1
- verdict: clean
- refs read: https://github.com/scala/scala/pull/10468 (fixes scala/bug#12828; body is one line, lrytz "Thanks"; bug thread: som-snytt says trailing backslash "is an error but should go through the correct channel"). Commit bdde5ad0ff adds `badescape()` ("trailing backslash") when `pos > line.length` after skipToDelim, plus ParserTest cases (`"\\"`, `"hello, world!\\"` must fail; `"\\\\"` ok).
- follow-ups: none after bdde5ad0ff in Parser.scala. History before it matters: 416536e7fd (2022-08) "process.Parser strips escaping backslash" was reverted by 864148d9f0 (2022-09) because backslashes break on Windows (som-snytt in bug#12828: PR 10114 "reverted because of backslashes on Windows"). No scala3 issue found for the trailing-backslash crash.
- porting note: take the whole bdde5ad0ff hunk (badescape branch + the tests). Do not port or re-add backslash-stripping (416536e7fd): it was reverted for Windows paths, which is exactly the argfile case in the finding. In scala3 the trailing check should report through `errorFn`, not throw.

### B2
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/10319 (fixes scala/bug#12732 "Make argfile work (more) like javac"): this PR moved Scala 2 TOWARD whole-file tokenizing like javac, Kotlin and Scala 3 (michelou: "Kotlin and Scala 3 do behave the same as Java"). som-snytt: it is "really a reversion" of 2.13.10's per-line change (arrived in 2.13.9, reverted, re-reverted in 4960a7a6ea). The finding's "Scala 2 tokenizes line by line" describes the pre-10319 behaviour.
- follow-ups: none in CompilerCommand.scala after 4960a7a6ea (only copyright/merge commits). Related scala3 issues are old and closed (#11454 scaladoc @-files, #13552 classpath via @-file); none about missing argfile.
- porting note: joining lines and tokenizing once is the javac-compatible, intended behaviour; do NOT port the per-line tokenizing (it was the regression 10319 undid). Only the missing-file case is a real delta: 10319 still keeps Scala 2 throwing on a missing file, javac errors too, so reporting an error instead of `Nil` is the defensible change.

### B3
- verdict: unknown
- refs read: none (no refs). I did not locate a Scala 2 commit fixing `unparse`; no scala3 issue found for "ScalaVersion unparse"/"RC1".
- follow-ups: none found.
- porting note: trivial, unused in compiler/src; fix only if something starts calling `unparse`.

### B4
- verdict: caveat
- refs read: none (Scala 3-only).
- follow-ups: ALREADY FILED: https://github.com/scala/scala3/issues/15796 (open, 2022) "NumberFormatException because of tput No such device or address": NumberFormatException `For input string: ""` at `ScalaSettings$.defaultPageWidth`, from an empty COLUMNS in a docker build (maintainer could not reproduce locally). git -L on the line shows no change since 2b3c05aa0e (2024-03-13).
- porting note: reference #15796 instead of filing a new issue; fix is `columnsVar.toIntOption.getOrElse(default)` (same for ANSICON).

### B5
- verdict: unknown
- refs read: none. No scala3 issue found for isMac/isLinux/isJavaAtLeast in config/Properties.
- follow-ups: none found.
- porting note: dead code; nothing to carry.

### B6
- verdict: unknown
- refs read: none (Scala 3-only). MainGenericCompiler.scala `withResidualArgs`/`withScriptArgs` still filter `nonEmpty` (unchanged by f33acfe864 / a450ea4908, 2026-09). No scala3 issue found for "scala script.sc ''" losing an empty argument (searched "empty argument", "empty string argument script").
- follow-ups: none found.
- porting note: not filed upstream as far as the search shows; a new report would be original.

### F1
- verdict: unknown
- refs read: none. Scala 2 reads the count with `u1()` (unsigned) in ClassfileParser readParamNames; Scala 3 line 1013 uses signed `in.nextByte` (introduced with the MethodParameters parser in 0902c3ba3e, 2021-02-23, never changed). No scala3 issue found for "MethodParameters".
- follow-ups: none found.
- porting note: one-line fix (`in.nextByte & 0xff`); no known caveat.

### F2
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/5783 (SI-10231): description only; "Nested java classes have a synthetic outer parameter, which the classfile parser skips for the constructor symbol. When assigning parameter names from MethodParameters we also need to skip the first name". No comments. scala/bug#10231 is a crash (scalac) for `new o.Inner` with javac -parameters, repro Outer/Inner.
- follow-ups: the 2017 fix was subsumed by a rewrite: ae6da154c1 (2018-05, PR 6653 area, "Eagerly read from the constant pool") and 6587b19f6f / PR 6283 (2018-01, "Ensure that parameter names read from bytecode aren't obliterated by generic signatures"). Current 2.13 code (ClassfileParser ~l.1396-1418) shifts by `hasOuterParam`, skips ACC_SYNTHETIC names, and has `devWarningIf` for a length mismatch ("there's not anything we can do, but it's slightly worrisome"). Scala 3: no issue found; related recent PR scala/scala3#27277 (2026-10-10, java enum valueOf param name) touches only JavaParsers.
- porting note: port the shift-by-outer logic as a unit with 6283's ordering (names applied after signature parsing; scala3 already does this at l.973-975 via namedParams) and keep the length-mismatch tolerance; do not assume MethodParameters length == descriptor length.

### F3
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/6653 (retronym, cc648bfe14): perf/laziness PR; buffer reuse was ONE part, measured gc.alloc.rate.norm 37.7 -> 36.36 MiB (0.96x) for the whole PR; PR's own TODO noted reentrancy ("classfile parsing does re-enter ... package objects") so reuse is a one-element cache. https://github.com/scala/scala/pull/8556 is a different feature (-Ypickle-write-no-privates); 2826c6cb96 just avoids temp byte arrays from VirtualFile. https://github.com/scala/scala/pull/8915: "Fixes a incorrectly resolved merge conflict in the backport" i.e. the reuse was silently not active in 2.12 for months; retronym: 1.8% of allocation in the scalap JMH benchmark.
- follow-ups: 9429c4e205 (2020-03) ReusableInstance changed from single var to a mutable Stack because of re-entrancy; 9cbcb7ff8e, bb2585dede tweak it. IMPORTANT scala3 side: scala3#26481 "Simplify classfile reading" (31a7440b6f, 2026-07-09) deliberately removed ReusableDataReader/ReusableInstance: "The only thing to reuse is one allocation of a stream ... not worth the machinery", benchmark showed slightly better perf.
- porting note: do not port; the Scala 3 maintainers already measured and removed it. If ever revisited, the reentrancy requirement (stack of readers, not a single buffer) is the known trap.

### F4
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/11090 (fixes scala/bug#13110, Kotlin enum inner class compiled abstract+final in InnerClasses -> ClassFormatError "Illegal class modifiers ... 0x4419" in the SCALA-compiled class). PR text: "It follows Dotty in dropping final and retaining abstract"; som-snytt: "Scala 3 creates the symbol from package completion (which is why it's not final)"; issue reporter: "this works with Scala 3.3 and 3.7". Reproduced here: scala-cli, scala 3.7.3, markdown-jvm:0.7.3, `abstract class C extends MarkerBlock { def acceptAction(c: MarkerBlock.ClosingAction) }` -> javap shows `InnerClasses: public static abstract ... ClosingAction` (no final). Scratch: <scratchpad>/b1f4/t.scala. (Not re-run on the worktree build.)
- follow-ups: none to a03973a353 in the Scala 2 ClassfileParser (later commits are unrelated ct.sym ones: f768c54142, 8d4ec394a0).
- porting note: nothing to port; the Scala 3 behaviour is the reference Scala 2 copied. The finding's "keeps both flags" did not produce a bad classfile in the Kotlin case.

### F5
- verdict: clean
- refs read: https://github.com/scala/scala/pull/3867 (SI-8708, LOCAL_CHILD dummy had a trait as first parent and was entered into the enclosing class scope; lrytz, adriaanm "thorough testing", no caveats), https://github.com/scala/scala/pull/4013 (SI-8868 local dummy symbols unpickled as owners of annotation-argument symbols; LGTM). Both are Scala 2 unpickler/compiler crash fixes.
- follow-ups: none reverting them; UnPickler.scala later churn is stub/cycle related (8efcb4236b "unpickler cycle avoidance" 2019, 699eba8b63 2022, 37c134b0b4 2025) not touching these two hunks.
- porting note: the Scala 2 bugs were crashes in scalac's own symbol table; the finding itself says no user-visible Scala 3 failure, so only port with a reproducer (sealed + local child pickled by 2.13, then unpickled by dotty).

### G2
- verdict: regressed
- refs read: https://github.com/scala/scala/pull/7996 (aaedc0f14f, 2019-04/06; fixes scala/bug#10870; "More ambitious version of #7995"; merged 2019-06-26, no review caveats). The PR is more than the chunking: it ALSO rewrote BCodeBodyBuilder.liftStringConcat to be a non-recursive/right-recursive `loop` (`Apply(fun, rarg :: Nil)` matching `loop(larg); loop(rarg)`) so chunked right-nested `+` flattens, and removed the old guard `args.lengthCompare(64) <= 0` from the intrinsic.
- follow-ups: d6083a4dcf (2019-08-06, PR https://github.com/scala/scala/pull/8315, scala/bug#11665) "Fix regression in large string interpolations with non-String typed splices": 7996 started each chunk with `group.head`, so for >32 parts of non-String type (e.g. Int splices) the chunk was `1 + 1 ...` = arithmetic (ClassCastException at 33 parts in 2.12.9; broke Twitter code, drove a hotfix discussion). Fix: seed each chunk with a `""` String literal. Current 2.13 code: FastStringInterpolator.concatenate, chunkSize = 32, `chunkResult = Literal(Constant(""))`. Test run/t11665 (it had to be split into defs to avoid "Method too large").
- porting note: port ONLY the post-8315 form (each chunk seeded with `""`; Scala 3's mkConcat `result.select(defn.String_+)` has the same Int+Int hazard if chunks start at the first element). Scala 3's BCodeBodyBuilder.liftStringConcat (l.1513) is still left-recursive and takes `rarg` as a list, so a right-nested chunk becomes one opaque operand; check backend/indy limits and consider taking the 7996 liftStringConcat change too. No scala3 issue found for the StackOverflow.

### G1
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/10364 (merged 2023-07-10, "Concatenate strings if not formatting", fixes bug#9784, bug#8650). 27fa48c6f6 is one of ~8 commits in it: adds `for (dupe <- flags.diff(flags.distinct).distinct) errorAt(Flags, flags.lastIndexOf(dupe))(s"Duplicate flag '$dupe'")` in goodFlags and the neg test `f"$d%,,d"`. No review caveats on the duplicate-flag part.
- follow-ups: none touching duplicate flags; FormatInterpolator later only gets 1ac2451178 (2024-05, toString lint) and copyright. No scala3 issue found for DuplicateFormatFlagsException.
- porting note: Scala 3 has its own FormatChecker, so this is a one-line check (error at the position of the repeated flag char), not a code port.

### G3
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/5123 (1b1bc81484, warning when folding throws ArithmeticException, moved under -Xlint:constant per som-snytt/liff, LGTM). https://github.com/scala/scala/pull/10674 (69af723ba4, supersedes https://github.com/scala/scala/pull/10587): warns on overflow (`Int.MaxValue + 1`, `fooExact`); "The overflowed result is retained. Only divide by zero retains the original tree." It touched RedBlackTree/VectorTest/ArrayBufferTest in the library because the new warning fired there; som-snytt noted `divideExact` is JDK 18 only.
- follow-ups: PR 10587 (closed) was rejected by sjrd: "the warning says folding threw ArithmeticException but the code would not throw at run-time; more problematic, code that does constant-fold today will stop being constant-folded ... surprising performance regressions". No later ConstantFolder change in 2.13 after 69af723ba4. No scala3 issue found for these warnings.
- porting note: keep folding results unchanged and warn only (the sjrd objection); gate behind a lint/-W flag; avoid `Math.divideExact` (JDK 18) in compiler code.

### G4
- verdict: unknown
- refs read: none. No scala3 issue found for "Index is not this arg"/f-interpolator %n$ after a splice. The expected warning lives only in tests/untried per the finding.
- follow-ups: none found.
- porting note: not reported upstream as far as the search shows.

### G5
- verdict: amended
- refs read: all three commits are inside https://github.com/scala/scala/pull/10364 (see G1). 29f116f1f2 "Concatenate if not formatting" first folded when args were `Literal` with `isSuitableLiteralType`; 803dd5773e (same PR, 2023-04-05) "Only assume the string value of strings" narrowed this to `treeInfo.isLiteralString` in both FastStringInterpolator and FormatInterpolator; 7fb9052101 handles constant args "consistently" for f; 0f566b3acd makes `s` concatenate (purpose: annotation arguments must be constants, bug#8650 / bug#9784, test neg/t8650).
- follow-ups: 803dd5773e (fixup), 1ac2451178 (2024-05, toString lint in the concatenation path). No regression issue found.
- porting note: take 803dd5773e's restriction (fold only string literals) along with 29f116f1f2, or the fold will format non-string constants differently from runtime `format`.

### G6
- verdict: amended
- refs read: https://github.com/scala/scala/pull/10740 (d856d1510c, fixes scala/bug#12981: bad caret/range for escape errors since 2.13.0; also changed WrappingPosAccumulator so an out-of-range default point is ignored; lrytz asked for assertions on call sites). 55ef130e96 is the 10364 message tweak.
- follow-ups: https://github.com/scala/scala/pull/11219 (cb0d205522, 2026-02-27, fixes scala/bug#13161): "Further improve error pos in raw ... the error pos is now just the `\u`. Previously the end was wrongly shifted" i.e. 10740's range was still wrong for raw. Also 8ef7080c08 (2024-03) "Don't process ignored escapes for raw".
- porting note: if porting positioning, take d856d1510c AND cb0d205522 together (otherwise raw"\u" gets a shifted end). In scala3 the fix is local to FormatChecker's badPart (start at the escape, span = escape length); the library-wide pos change in 10740 is not needed.

### G7
- verdict: amended
- refs read: https://github.com/scala/scala/pull/10218 (568b43d1c3, fixes scala/bug#12681 and #12908): self-type search, companion-class search for modules, `ccoll` for CC. Review: "It seems redundant for both a class and its companion to define coll"; the PR thread shows spurious "Variable ... undefined" scaladoc warnings in the library that had to be worked around inside the PR. The new code also memoizes `defs(site) += vble -> str`.
- follow-ups: d155007b89 (2025-10-15, "Include object in search list", scala/bug#13129): 568b43d1c3 REGRESSED objects whose own comment @defines the variable (`object Main` with `$test`): the module itself was no longer in the search list. Fix: `include(site)` for modules before the companion class, and guard `examine` with `k.exists` (companionClass can be NoSymbol). Tests t13129.
- porting note: scala3's current lookupVariable already does `site :: searchList` for modules (so it avoids the 13129 regression) and self types; adding the companion class must keep `site` first and handle `NoSymbol` companion. Skip the memoizing write unless needed.

### G8
- verdict: clean
- refs read: https://github.com/scala/scala/pull/7364 (856c111449, "Emit detailed compiler trace under -Yprofile-trace", merged 2018-10-31): nothing about the catch clause. The Scala 2 file (now src/reflect/scala/reflect/internal/util/FileUtils.scala l.183) catches `Throwable`, and did from the first commit (856c111449 is the only commit adding `asyncStatus.tryFailure(t)`); the scala3 copy narrowed it to `Exception`.
- follow-ups: none; FileUtils.scala has only copyright/typo commits (e67e2566eb, b42fa57797). scala3 has -Yprofile-trace issues (#21858 escaping, #22612 -Yprofile-enabled buggy) but none about the writer.
- porting note: a straight `Exception` -> `Throwable` change in the catch matches upstream.

### A5
- verdict: regressed
- refs read: https://github.com/scala/scala3/pull/26398 (da99eb87b0, merged 2026-07-06, "Improve IO package encapsulation", Fixes #23057, refactor, "Covered by existing tests"). The diff replaced a split-based lookup (`"p/q/".split("/")` drops the trailing empty segment) with `AbstractFile.lookupPath(packageDirName, separatorChar, directory = true)` whose loop ends with `lookupName(path.substring(idx) + suffix)` on an empty last segment; callers pass `PackageNameUtils.dirPathTrailingSlash`. Present in HEAD of this worktree (VirtualDirectoryClassPath.scala l.11).
- follow-ups: none fix it. Later PRs touching the same files: #26712 / #27154 (dcc910fe0b "Simplify classpath code", 2026-09, backported to 3.10.0 in #27154), #27251 (fast path merging). No scala3 issue found (searched VirtualDirectoryClassPath, VirtualDirectory package).
- porting note: not a port; a fix is to strip the trailing separator in getSubDir (or make lookupPath ignore an empty last segment). Check the 3.10.0 backport (#27154) carries the same regression. Add a test with a non-root package in a VirtualDirectory (REPL hides it because wrappers are in the root package).

### A6
- verdict: amended
- refs read: https://github.com/scala/scala3/pull/26398 (see A5). Follow-up https://github.com/scala/scala3/pull/26513 (1b5b36203b, 2026-07-10, "Fix windows build from VirtualFile crash ... I think I broke it in a refactor"): same Path.of problem, but only exempted names starting with `<` or `"` (fake names). Zip entries still reach `Path.of(path).getFileName` via ZipArchive.Entry extends VirtualFile (HEAD l.20-27), so jar entries with `<>:"|?*` or backslash would still throw InvalidPathException on Windows; the code comment itself says "TODO: Proper path support for VirtualFile".
- follow-ups: 1b5b36203b is the only follow-up; no scala3 issue found for InvalidPathException on jar entries (existing InvalidPathException issues are unrelated: #19853, #4356, #7492).
- porting note: Scala 2 uses a string baseName (no java.nio.file.Path). Same approach avoids both the Windows throw and the per-entry allocation.

### A1
- verdict: amended
- refs read: https://github.com/scala/scala/pull/9557 (retronym, 2021-05-18; ZipFile pool + "caching was not considering -release"; 493f985520 is the first attempt at the key). https://github.com/scala/scala/pull/9671 (31d8d058fa, merged 2021-06-23): "This change was buggy -- it did not include the selected release in the cache key, which could lead to a compiler that specifies -release X seeing the results of another compiler using -release Y"; untested because the MultiReleaseJarTest is JDK9+ and not run on 2.12.x CI. PR 9671 ALSO fixed a second multi-release-JAR bug: IllegalArgumentException in ReusableDataReader when buffers were sized from the non-versioned entry size when different -release values were used.
- follow-ups: 493f985520 (2021-05-04, ZipAndJarFileLookupFactory: `FileBasedCache[K, T]` keyed `(release, paths)`) then 31d8d058fa (2021-06-17, same for ct.sym); 33a331c52b (#9946, 2022-02) and 683f698459 (#11009) unrelated to the key. Cache key in HEAD of Scala 2 is `(K, Seq[Path])`.
- porting note: key on `(release, path)` like 493f985520/31d8d058fa; and check the second 9671 bug: scala3's DataReader now reads `file.toByteArray` (26481 removed `sizeOption`), so the buffer-size half should not apply. Note Scala 3's scala3 source cache (ZipAndJarSourcePathFactory) is a separate instance, so only the class cache needs the key.

### A3
- verdict: regressed
- refs read: https://github.com/scala/scala/pull/7366 (retronym; "Resource management for macro/plugin classloaders, classpath JARs": Global.close(), ref-counted close after a short delay) is the actual PR behind cfbb2a2d16 (a [backport] commit that landed via https://github.com/scala/scala/pull/7712 build pipelining; the ref list's PR attribution to 7712 is the merge path, not the design). https://github.com/scala/scala/pull/7644 (b4d31c7e4c): "Fix logic error in reference counted closing; add policy to cache regardless of timestamps; be optimistic with non-file classpath entries". https://github.com/scala/scala/pull/11009 (683f698459, scala/bug#13094): `FileZipArchive` leaked a file handle under `-Dscala.classpath.closeZip=true` (sbt straight-to-jar) on Windows, "bug introduced in 31255c38f0" (the pool). https://github.com/scala/scala/pull/11081 (211cc41a15, 2025-06): "bugfix: try and avoid potential race condition" in the Closeable returned from `referenceCountDecrementer` (plain `var closed` -> AtomicBoolean), after metals#7591 reported `assertion failed: (List(Stamp(...)),0)` at FileBasedCache.getOrCreate (the `assert(count > 0)`).
- follow-ups (chain): bcf47b165c (2017, bug#10295 stale jars) -> c3ffa1b24b (#6314) -> 5175550b16 -> cfbb2a2d16 (#7366/#7712) -> b4d31c7e4c (#7644 fix refcount logic error) -> 3e0ab870ce (#7813 "Honour CachePolicy.None ... I broke this in a recent refactoring") -> e4406b94c1 (#8021 cancel stale timer on cache hit) -> 31255c38f0 (#9557 pool) -> 683f698459 (#11009) -> 211cc41a15 (#11081). Still-open TODO in 2.13 getOrCreate: a stamp mismatch with live references only closes if refcount==0, else "TODO: What do do here?" (leak); `clear()` still says "TODO support closing" there too. FileBasedCache.deferCloseMs=1000 comment: a too-long delay breaks overwriting JARs on Windows. No "invalid LOC header" issue exists in scala/scala or scala/bug (searched).
- porting note: porting the refcount scheme means taking all of 7644, 7813, 8021 and 11081 (refcount bug, stale-timer bug, atomic close) plus the closeZip handling from 11009, not just 7366. A simpler alternative for scala3 is closing replaced cache entries and closing on ContextBase shutdown without timers.

### A4
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/9557 (0714941e30 "Cache JRT and --release (ct.sym) classpath elements"; description: "I also included Java 9+ classpath repositories (JrtClassPath and CtSymClassPath) in the caching scheme") and https://github.com/scala/scala/pull/9671 (31d8d058fa fix: the first version of the ct.sym cache lacked the release in the key, so concurrent compilers with different -release saw each other's view).
- follow-ups: 87286ab623 (2024-01, "-Yrelease and related ctsym/jrt tweaks" backport), 8f55db50f9 (#10543), 98726c0e1f (#10801 -system), f768c54142 (2025-10 skip synthetic ct.sym symbols), 8d4ec394a0, 9049721db5 (2026-10-16 value-based classes under -release). In #10801 som-snytt links JDK-8260621 (jrt filesystem memory leak, fixed in JDK 17). 2.13 key is `ctSymClassPathCache: FileBasedCache[String, CtSymClassPath]` with `checkStamps = true` on ct.sym and `closeableRegistry` close. scala3 recent: #27132/#27318 (-release classes/value-based) don't touch ct.sym FS lifetime.
- porting note: a cache for CtSymClassPath MUST include the release in the key (the 9557 -> 9671 sequence is exactly this mistake); also needs the refcount/close machinery of A3 or it just moves the leak. A cheaper scala3 option: one shared FS per ct.sym (stamp-keyed), per-release roots opened lazily.

### A2
- verdict: caveat
- refs read: 3e500b21a5 ("Use file size as part of the cache invalidation", 2019-03-04, merged via https://github.com/scala/scala/pull/7712 build pipelining; commit message one line). Background: https://github.com/scala/bug/issues/10295 (stale jars, retronym: "use file timestamps/size/content-hash"), bcf47b165c (2017: lastModified + inode fileKey), and the abandoned https://github.com/scala/scala/pull/6381 (CRC-based invalidation, closed unmerged after long debate about policies).
- follow-ups: current 2.13 `Stamp(lastModified, size, fileKey)`, and `getOrCreate` catches `NoSuchFileException` and uses a dummy stamp `(FileTime 0, -1, new Object)` (from b4d31c7e4c/cfbb2a2d16). 3e0ab870ce (#7813) added the CachePolicy.None bypass.
- porting note: take size + NoSuchFileException handling together; do not revive CRC checks (6381 stalled on cost/policy).

### A7
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/10336 (f69089cb7d; `-javabootclasspath` supersedes jrt; to compile against a JDK 8 rt.jar use `-javabootclasspath rt.jar -nobootcp`; author corrected it to only use javaBootClassPath on JDK 8 "as intended", no automated test since rt.jar can't be checked in; intentionally 2.13.11). https://github.com/scala/scala/pull/10543 (8f55db50f9 `-Yrelease:sun.misc`): SethTisue asked "do you intend to port this to Scala 3? We're reluctant to add things in 2 unless the path in 3 is clear" and wanted a clearer (niche) name; private Y option. https://github.com/scala/scala/pull/10801 (98726c0e1f `-system`, szeiger): merged with an unresolved `-target` deprecation question; JDK-8260621 jrt leak noted.
- follow-ups: 87286ab623 (2024-01 backport of -Yrelease tweaks), f768c54142 (2025-10). No scala3 issue found for -javabootclasspath, -system, or -Yrelease:sun.misc.
- porting note: these are features, not bug fixes; any port needs a decision on -Y vs public naming (10543 review) and an integration test with a second JDK (10336/10801 have none). Scala 2 gave `-nobootcp` semantics, which the scala3 PathResolver would also need.

### A8
- verdict: amended
- refs read: https://github.com/scala/scala/pull/9557 (31255c38f0): reason is `j.u.ZipFile` serializes reads; retronym first tried zipfs, then switched to "a pool of plain-old java.util.ZipFiles". https://github.com/scala/scala/pull/9946 (33a331c52b, 2022-02): "On systems with large values of available CPUs, highly concurrent compilation, and large classpaths, the pool was observed to have exhausted file handles"; default capacity reduced to 4 (property `scala.classpath.zipFilePool.capacity`; 5+ concurrent compilers open/close a ZipFile each).
- follow-ups: 683f698459 (#11009, 2025-03; fd leak in closeZip mode "introduced by me in 31255c38f0"); 211cc41a15 (#11081). In 9557 retronym first tried zipfs, found it also contended on reads, and fell back to the pool.
- porting note: the pool needs a small bounded capacity (4) and correct release in the closeZip mode (11009); an unbounded per-thread pool is what exhausted fds. Only worth it if concurrent compilers in one JVM are a Scala 3 target; scala3 currently shares one ZipFile per cached jar.
