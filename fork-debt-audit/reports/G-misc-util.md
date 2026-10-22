# G-misc-util findings

Repro scratch: `<scratchpad>/work/G-misc-util/` (Fl.scala, Big.scala, Cf.scala, Es.scala; run with `scala-cli --server=false -S 3.7.3`; the source paths cited were checked on the current worktree).

## Pairs

| s3 file | s2 file | port date | later s2 commits | notes |
|---|---|---|---|---|
| dotc/typer/ConstFold.scala | nsc/typechecker/ConstantFolder.scala | 2013-07 (1ed37543f0, rewritten since) | 24 | Semantics match for ops/shifts/div-by-zero/NaN/-0.0. Only the `-Xlint:constant` overflow warnings (1b1bc81484, 69af723ba4) are absent. |
| dotc/core/Constants.scala | reflect/internal/Constants.scala | 2012-12 initial | ~35 | `equals`/`hashCode` raw-bits NaN/-0.0 handling present; `convertTo` uses `isFloatRange` (no representability test) but Scala 3 has its own `LossyWideningConstantConversion` warning in Typer. Nothing to port. |
| dotc/transform/localopt/FormatChecker.scala | tools/reflect/FormatInterpolator.scala | 2021-03-20 (ffeaa5f584) then actively developed by the same author through 2025 | ~25 | Mostly in sync (Boolean null test, toString lint, OrType lint, ids). Gaps: F1, F4, F5, F6. |
| dotc/transform/localopt/StringInterpolatorOpt.scala | tools/reflect/FastStringInterpolator.scala | 2018-02-03 | ~8 | F2, F5. |
| dotc/util/CommentParsing.scala | nsc/util/DocStrings.scala | 2016-04-13 | 7 (all refactor/lint) | Clean; Scala 3 has two extra helpers (`groupedSections`, `removeSections`). |
| dotc/core/Comments.scala | nsc/ast/DocComments.scala | 2016-08-24 | ~14 | Heavily reworked (backtick/fenced-code skipping, self types). F7 only. |
| dotc/util/WeakHashSet.scala | reflect/internal/util/WeakHashSet.scala | 2017-04-25 | 18 (all refactor/lint/collection-API churn) | Adapted (abstract `hash`/`isEqual`, Stats, nullability). No missed fix. |
| dotc/profile/ExtendedThreadMxBean.java | nsc/profile/ExtendedThreadMxBean.java | 2018-01-11 | 3 | Identical modulo package. `30b3d58745` (JDK9+ via `Class.forName("com.sun.management.ThreadMXBean")`) already present. |
| dotc/profile/{ChromeTrace,FileUtils}.scala | reflect/internal/util/{ChromeTrace,FileUtils}.scala | 2024-10-22 (ecc332fe6f forward-port) | 2 | Near verbatim. F8. |
| dotc/profile/Profiler.scala, ProfiledThreadPool.scala | nsc/profile/{Profiler,ThreadPoolFactory}.scala | 2018-01-11 / 2026 refactor | many | Redesigned (opaque TracedEventId, inline onX wrappers, no plugins, CallerRunsPolicy). Nothing identifiable as a dropped fix. |
| dotc/util/NameTransformer.scala | library/scala/reflect/NameTransformer.scala | 2009 | 20 (none functional apart from `<init>` in decode) | Scala 3 version works on SimpleName; same algorithm. |
| dotc/util/{HashSet,GenericHash*,HashMap,EqHashMap,IntMap,LinearSet,PerfectHashing,...} | -- | -- | -- | Not ports (sim_tok ratio 0.00-0.01). Skipped. |
| dotc/reporting/{ConsoleReporter,MessageRendering}.scala | -- | -- | -- | Not ports (sim ratio <=0.01). Skipped. |

## Findings

### F1: f-interpolator does not reject duplicate flags (`f"$d%,,d"`) -> runtime DuplicateFormatFlagsException
- severity: medium
- confidence: high
- s3: /Users/jzaugg-virtus/code/.worktrees/scala3/scala3-untracked-fork-debt-803909/compiler/src/dotty/tools/dotc/transform/localopt/FormatChecker.scala:~156 (`goodFlags` inside `Conversion.verify`)
- s2: src/compiler/scala/tools/reflect/FormatInterpolator.scala ; fix commit: 27fa48c6f6 2023-04-05 "Check duplicate flags" (Som Snytt)
- what: Scala 2.13.11 added `for (dupe <- flags.diff(flags.distinct).distinct) errorAt(Flags, flags.lastIndexOf(dupe))(s"Duplicate flag '$dupe'")` to `goodFlags`, so that `f"$d%,,d"`, `f"%--5d"` etc. are compile errors (they would otherwise throw `java.util.DuplicateFormatFlagsException` at runtime). Scala 3's `goodFlags` only checks `okFlags`, so these compile and fail when executed.
- verified: grep "Duplicate flag" over compiler/src and tests finds nothing. Compiled and ran `f"$d%,,d"` with 3.7.3: compiles cleanly, prints `java.util.DuplicateFormatFlagsException: Flags = ','` at runtime. (The same commit also guards `goodFlags`/`goodIndex` with `flags.isEmpty ||`/`!isIndexed ||`; no behavioural difference in Scala 3.)

### F2: s"..." with many parts builds a left-deep `+` chain -> StackOverflowError in the compiler
- severity: medium
- confidence: high
- s3: compiler/src/dotty/tools/dotc/transform/localopt/StringInterpolatorOpt.scala:96-113 (`mkConcat`: `result = result.select(String_+).appliedTo(tree)` per element, depth = number of parts)
- s2: src/compiler/scala/tools/reflect/FastStringInterpolator.scala (`concatenate`, `chunkSize = 32`) ; fix commit: aaedc0f14f 2019-04-24 "Avoid deep ASTs in StringContext rewrite" (Jason Zaugg; also touched BCodeBodyBuilder and RefChecks, test t10870)
- what: Scala 2 groups concatenation operands in chunks of 32 and concatenates the chunks, bounding AST depth at max(32, N/32) rather than N. Scala 3 nests every operand, so tree depth equals the part count and the recursive tree traversals in later phases overflow the (default 512KB-1MB) stack for large generated interpolations. Observable: a crash ("unhandled exception ... erasure"/MegaPhase) rather than an error or success.
- verified: generated `s"${a}x${a}x..."` with n=100 pairs compiles; n=250 (500 parts) crashes with `java.lang.StackOverflowError` in a MegaPhase; n=500/1000/2000 crash in erasure/MegaPhase; with `-J-Xss8m` n=3000 compiles. (Tested on released 3.7.3; the `mkConcat` source in this worktree is unchanged in structure.) Plain hand-written `a + a + ...` chains of that length would crash similarly, so this is about generated code; the Scala 2 fix chose to reduce depth for interpolations specifically.

### F3: no "arithmetic error in constant expression" warning (-Xlint:constant analogue)
- severity: low
- confidence: high
- s3: compiler/src/dotty/tools/dotc/typer/ConstFold.scala:213-216 (`catch case ex: ArithmeticException => null // the code will crash at runtime`)
- s2: src/compiler/scala/tools/nsc/typechecker/ConstantFolder.scala ; commits: 1b1bc81484 2016-05-02 "Emit a warning when a constant expression evaluates to an ArithmeticException (#5123)", 69af723ba4 2024-01-25 "Warn more constant inexactitude" (also warns on Int/Long add/sub/mul overflow and MinValue / -1 under -Xlint:constant)
- what: Scala 2 warns when folding `1/0`, `5L % 0`, `Int.MaxValue + 1`, `Int.MinValue / -1`, and falls back to the wrapped value; Scala 3 silently declines to fold (div by zero) or silently wraps (overflow). Fold results themselves are identical (value-wise), so this is purely a missing diagnostic.
- verified: grep for "arithmetic error"/`Xlint:constant` in compiler/src: none. Compiled `final val a = 1/0; Int.MaxValue + 1; 5L % 0L; Int.MinValue / -1` with `-Wconf:any:w` on 3.7.3: no warning; `a` throws ExceptionInInitializerError at runtime. Shift semantics checked by reading: Int<<Long and Long<<Int match Scala 2 (eb954cafa2, 963414f6ae are reflected).

### F4: missing "Index is not this arg" warning for `$x$y%1`
- severity: low
- confidence: medium
- s3: FormatChecker.scala:~99-101 (`else if cv.isIndexed then if cv.index.getOrElse(-1) == n then accept(cv) else insertStringConversion()`)
- s2: FormatInterpolator.scala ; present since f8e0f9810b (2014, SI-8092); stayed in Scala 2 `interpolated`
- what: Scala 2 emits `c.warning(cv.groupPosAt(Index, 0), "Index is not this arg")` when an indexed conversion that follows a splice refers to a different arg, then inserts the default `%s`. Scala 3 silently does the insertion. The expected-output file `tests/untried/neg/stringinterpolation_macro-neg.check` still contains the warning at lines 145,151, i.e. the test was never enabled.
- verified: grep "Index is not this arg" in compiler/src: absent; only in tests/untried.

### F5: no compile-time folding / concatenation shortcut for f-interpolator (`f"abc"`, `f"${"x"}%s"`)
- severity: low
- confidence: medium
- s3: StringInterpolatorOpt.scala `transformF` (always emits `new StringOps(fmt).format(args)`), FormatChecker.scala `checked`
- s2: FormatInterpolator.scala `interpolated` ; commits 7fb9052101 / 29f116f1f2 / 0f566b3acd (2023-04 "Concatenate if not formatting", "Format constant args constantly consistently", "s interpolator constantenates")
- what: Scala 2 tracks `formatting` (anything other than a plain `%s`); if no real formatting is done it returns a constant (no args) or plain string concatenation (with `-Wtostring-interpolated` suppressed to avoid a double warning), and if all args are literal Strings it computes `format.format(...)` at compile time. Scala 3 always goes through `StringOps.format` + varargs boxing at runtime. Semantics equal; perf/size only. Also not ported: Scala 2 `s`/`raw` fold when all args are literal strings (Scala 3 relies on later ConstFold of the produced `+` chain; I did not verify it folds).
- verified: read both sources; no `formatting`/`constantly` analogue in Scala 3.

### F6: f-interpolator escape-error position covers whole part, not the bad escape
- severity: low
- confidence: high
- s3: FormatChecker.scala:~105 (`badPart`: `report.partError(t.getMessage, index = n, offset = 0)`)
- s2: FormatInterpolator.scala ; d856d1510c 2024-04-13 "Use correct position for error in escape", 55ef130e96 2023-04-05 "Improve message for unsupported escapes", cb0d205522 2026-02-27 "Further improve error pos in raw"
- what: Scala 2 positions an invalid-escape error at the offending escape (`adjustedEscPos(part.pos, ie.index)`); Scala 3's `s` path (StringInterpolatorOpt) does this properly but `f` reports at offset 0 with end = part end.
- verified: compiled `f"abcdef \q gh $d%d"` on 3.7.3: caret underlines the entire part `"abcdef \q gh $d%d"`... (whole span from the interpolator start), message includes "at index 7".

### F7: `$variable` lookup ignores companion class for objects
- severity: low
- confidence: low
- s3: compiler/src/dotty/tools/dotc/core/Comments.scala:486-503 (`lookupVariable`)
- s2: src/compiler/scala/tools/nsc/ast/DocComments.scala ; fix commit: 568b43d1c3 2022-11-08 "Find more doc vars and distinguish C from CC" (Som Snytt)
- what: Scala 2 now builds the search list as `if site.isModule then examine(site.companionClass); examine(site)`, i.e. `@define` entries on `class C` are visible when expanding docs of `object C`; it also caches hits per site. Scala 3 adds `site` and its self type to `baseClasses` for modules but never the companion class. Scala 3 already has the self-type part of the same commit. Impact depends on whether anything beyond REPL/`-Ydoc` consumers uses this expander (scaladoc has its own parsing), hence low confidence.
- verified: read both; no `companionClass` in lookupVariable.

### F8: profile.FileUtils async writer catches `Exception`, Scala 2 catches `Throwable`
- severity: low
- confidence: low
- s3: compiler/src/dotty/tools/dotc/profile/FileUtils.scala:~187 (`case ex: Exception => asyncStatus.tryFailure(ex); throw ex`)
- s2: reflect/internal/util/FileUtils.scala `case t: Throwable` (since 856c111449 2018-04-17)
- what: An `Error` (OOM, NoClassDefFoundError) thrown inside the background writer leaves `asyncStatus` incomplete so `close()` awaiting it would hang; Scala 2 completes the promise with the failure. Only reachable with `-Yprofile-trace`, and I did not test the hang.
- verified: diff -w of the two files.

## Already ported / not applicable
- ConstFold: shift Int/Long (SI-8462, SI-9516), div-by-zero no-fold, folded `toX`/unary selections (f4ddef727b/5a79d92de8), singleton/literal val folding (2e0c0972f9), NaN/-0.0 compare semantics, Null ops.
- Constants: raw-bits NaN equality, tag in hashCode (a2ffb7e113), `convertTo` and lossy-widening diagnostics (Scala 3 has own `LossyWideningConstantConversion`, Typer.scala:1232-1245, 5112). 7ed7368ad2 "Build constant string" is printer-only.
- FormatChecker: Boolean null-test warning, toString/Unit lint (+OrType), `%` literal width warning, `<` flag handling, BigInt/BigDecimal/Calendar/Date/Formattable acceptable variants, `%n`/`%%` rules, all flag/width/precision validation tables (same as Scala 2).
- DocStrings -> CommentParsing: identical logic; Scala 2 commits are lint/refactor. `{@inheritDoc}` replace, `expandInheritdoc`, `merge` match. c5ec0a3931/824c6b2e5e (getter/field inheritdoc) are N/A: Scala 3 has no field/getter split at this level.
- ExtendedThreadMxBean JDK9+ fix (30b3d58745) and reflection-free MXBean access (536988631c is replaced by direct ManagementFactory use in Scala 3's ChromeTrace; fine for dotc).
- WeakHashSet: all Scala 2 changes since 2017 are refactor/lint.
- NameTransformer: unchanged algorithm.

## Method notes
- Port direction is Scala 2 -> Scala 3 for everything above. The HashSet/HashMap/IntMap/EqHashMap/LinearSet/PerfectHashing family, ConsoleReporter and MessageRendering are original Scala 3 code (similarity ~0), not audited.
- FormatChecker and FastStringInterpolator/FormatInterpolator have the same upstream author, so the Scala 3 side was recently updated in lockstep (2024-2025); the remaining diffs are the ones listed.
- Unverified side observation (not a finding): `Constant.stringValue` is `value.toString`; for NullTag it NPEs and for ClazzTag prints `Type.toString` (Scala 2: "null" / `signature(tpe)`). Callers in compiler/src (e.g. sbt/ExtractAPI.scala:638 for `ConstantType`) would only hit it if a null/classOf constant type reaches them; I did not find a reproducer.
- Test compiler was released 3.7.3 (scala-cli), not a build of this worktree; for each claim the corresponding source in this worktree was also read.
