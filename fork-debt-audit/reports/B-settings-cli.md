# B-settings-cli

## Pairs
| s3 file | s2 file | port date | later s2 commits | notes |
|---|---|---|---|---|
| compiler/src/dotty/tools/dotc/config/CommandLineParser.scala | src/library/scala/sys/process/Parser.scala (+ tools/cmd/CommandLineParser) | ~2017 (@argfile, 7ac2fbce3f) | 7 since 2019 | Has the 2022 escaped-quote fix (102ab30359, scala/bug#12611) but NOT the 2023 trailing-escape fix. Direction partly reversed: s2 05001580b4 "CommandLineParser is process.Parser" |
| config/ScalaVersion.scala | nsc/settings/ScalaVersion.scala | 2013 | 5 (all -Xsource churn) | s3 diverged (Try-based parse, regex differs); no meaningful s2 bugfix missing |
| config/Properties.scala (PropertiesTrait) | util/PropertiesTrait.scala + Properties.scala | 2013 | ~40, mostly copyright | s3 copy frozen; isMac stale; unused extras |
| config/WrappedProperties.scala | tools/reflect/WrappedProperties.scala | 2013 | 5 | s2 only changed AccessControlException handling; s3 already catches SecurityException |
| config/Settings.scala, ScalaSettings.scala, ScalaSettingsProperties.scala | nsc/settings/MutableSettings, ScalaSettings | 2013 | ~60 | s3 Settings rewritten (sim ~0); fixes d44332d022 (option-like arg not consumed by string setting), 0f749a3084 (space-separated choice arg), 4bbb7e3be6 (-release/-target alignment) are present in s3 (`!args.head.startsWith("-")`, `useNextArg`, aliases -release/-Xtarget) |
| config/CliCommand.scala, CompilerCommand.scala | nsc/CompilerCommand.scala | 2013 | ~20 | argfile handling differs (F2) |
| dotc/Driver.scala, Main.scala, ScalacCommand.scala | nsc/Driver, Main | 2013 | few | structurally different |
| tools/MainGenericCompiler.scala, tools/scripting/* | MainGenericRunner, ScriptRunner | n/a | n/a | Scala 3-original; no real s2 counterpart |
| config/SourceVersion.scala, ScalaSettingsProperties.scala | -- | -- | -- | Scala 3 only |

## Findings

### F1: CommandLineParser.tokenize throws StringIndexOutOfBounds on a trailing backslash
- severity: medium
- confidence: high
- s3: compiler/src/dotty/tools/dotc/config/CommandLineParser.scala:52-60 (`skipToDelim`) and :96-104 (`loop`)
- s2: src/library/scala/sys/process/Parser.scala ; fix commit: bdde5ad0ff 2023-07-14 "Trailing escape is error in command parser" (follow-up to 102ab30359 for scala/bug#12611, whose escape handling s3 already has)
- what: Both versions treat `\` as escape and `bump()` past the next char. With a trailing `\` the escape consumes the (nonexistent) next char so `pos` ends at `line.length + 1`. Scala 2 added `else if (pos > line.length) { badescape(); Nil }` reporting "trailing backslash". Scala 3's `loop` lacks it, so `text()` calls `line.substring(start, pos)` with pos > length and throws StringIndexOutOfBoundsException instead of calling `errorFn`. Reachable through `@argfile` lines ending in `\` (e.g. Windows paths `C:\dir\`) and tests/utils (`CommandLineParser.tokenize(args)`), producing a crash/stack trace instead of a diagnostic.
- verified: copied the s3 file to scratchpad and ran under Scala 3.7.4 with scala-cli: `tokenize("a\\")` -> `StringIndexOutOfBoundsException: Range [0, 3) out of bounds for length 2`; same for `"hello, world!\\"` and `"\\"`. `"a \\\\"` -> `List(a, \\)` fine. Scala 2 test added: test/junit/scala/sys/process/ParserTest.scala `trailing escape is error`.

### F2: @argfile expansion differs from current Scala 2 (joins all lines; missing file is a soft error)
- severity: low
- confidence: medium
- s3: compiler/src/dotty/tools/dotc/config/CommandLineParser.scala:118-128 (`expandArg`), used from config/CliCommand.scala:47
- s2: src/compiler/scala/tools/nsc/CompilerCommand.scala `expandArg` ; history: 85609210e8, 4960a7a6ea (2023-02-19 "Re-revert untokenized argfile, write quoted args"), 205f1c532d (close file)
- what: Scala 2 strips comments + trims each line, drops empties, then tokenizes each line separately (`settings.splitParams`), and throws FileNotFoundException when the file is missing. Scala 3 joins all lines with " " and tokenizes once, so an unmatched quote on one line silently swallows the following lines (or reports one confusing "Unmatched quote" for the whole file) and a missing argfile only prints to stderr and expands to Nil, letting compilation proceed with the remaining args. Comment stripping `#` is unconditional in both (breaks `-Dx=#y`, quoted `#`).
- verified: read both expandArg implementations; Scala 3 test grep shows no test for multiline quoted args. Behavioural, may be intentional.

### F3: `ScalaVersion.unparse` for SpecificScalaVersion emits a spurious dot
- severity: low
- confidence: high
- s3: compiler/src/dotty/tools/dotc/config/ScalaVersion.scala:34
- s2: src/compiler/scala/tools/nsc/settings/ScalaVersion.scala (`s"${major}.${minor}.${rev}${build.unparse}"`)
- what: s3 has `s"...${rev}.${build.unparse}"` giving `2.13.0.-RC1` and `3.3.1.` for finals; it can't be parsed back (the doc says unparse round-trips). Also the s3 `parse` regex differs: `[^-]*` for rev so non-numeric garbage like `a.b.c` -> NumberFormat failure is handled, but `3.3.1-RC1` yields RC(1) OK. No caller of `unparse` in compiler/src (grep), so no user-visible effect.
- verified: grep `unparse` across compiler/src finds no external use.

### F4: `defaultPageWidth` crashes on non-numeric `$COLUMNS`
- severity: low
- confidence: medium
- s3: compiler/src/dotty/tools/dotc/config/ScalaSettingsProperties.scala:36 (`columnsVar.toInt`) and :39 (ANSICON `substring(...).toInt`, guarded by regex)
- s2: no equivalent (Scala 2 doesn't read COLUMNS) -- Scala 3-original hygiene bug
- what: `COLUMNS=""` or `COLUMNS=" 80"` throws NumberFormatException while the `ScalaSettings` object initialises (default of `-pagewidth`), i.e. any compile fails with a stack trace.
- verified: read code; not run.

### F5: `Properties.isMac` is stale
- severity: low
- confidence: high
- s3: compiler/src/dotty/tools/dotc/config/Properties.scala:127
- s2: src/library/scala/util/Properties.scala `lazy val isMac = osName.startsWith("Mac OS X")` (changed from `javaVendor.startsWith("Apple")`, earlier than ad8d076b72)
- what: Apple's JVM vendor string disappeared with JDK 7; on any JDK Scala 3 supports, `isMac` is false on macOS. Unused in compiler/src (grep) so no current effect. `isWin` is fine. Also missing: `isLinux`, `javaSpecVersion`, `isJavaAtLeast` (the compiler uses `scala.util.Properties.isJavaAtLeast` from the library copy instead; JavaPlatform.scala:13).
- verified: grep isMac across compiler/src.

### F6: MainGenericCompiler drops empty script arguments
- severity: low
- confidence: medium
- s3: compiler/src/dotty/tools/MainGenericCompiler.scala:44-51 (`withResidualArgs`, `withScriptArgs`, `withScalaArgs` filter `_.nonEmpty`)
- s2: n/a (Scala 3-original). Scala 2's MutableSettings (comment in processArguments) discards empty args only where an option is expected, "because an empty string is valid as an argument to an option, e.g. -cp ''".
- what: `scala script.sc ""` loses the empty script arg, shifting positional args; `scalac -Xfoo ""` loses a legitimately empty option value. Scala 3 `Settings.processArguments` also doesn't discard empty args in option position (they go to skipped/source files and report "file not found"), the reverse trade-off from Scala 2's `case "" :: xs => loop(xs)`.
- verified: read both.

## Already ported / not applicable
- 102ab30359 (escaped quotes in tokenizer, bug#12611): present in s3 `skipToDelim`.
- d44332d022 string setting must not consume an option-looking arg: present (`!args.head.startsWith("-")`, Settings.scala ~:296; IntTag exempted for negatives).
- 0f749a3084 space-separated choice arg: s3 `doSet` handles `-opt v`.
- 4bbb7e3be6/018d404816 -release/-target: s3 originated this (`-java-output-version` alias -release, `-Xunchecked-java-output-version` alias -Xtarget, ranges 17..28 / 17..current JDK).
- WrappedProperties f87a7db7f0 (don't catch AccessControlException): s3 already catches SecurityException.
- 7d285a9b5a MultiStringSetting preserves remaining args, ec5b54f4fd -Wconf:x,y, -Vprint:all: different Settings design; not comparable.
- `ScalaVersion` ordering/RC/M/Development comparisons match s2 semantics (RC < Final < Development, M < RC).
- scripting/*, MainGenericCompiler, SourceVersion, CompilerCommand: Scala 3-original.

## Method notes
- Settings.scala has ~0 line similarity to MutableSettings: complete redesign (state-based ArgsSummary), so fixes were compared by behaviour, not hunks.
- `-Xplugin` path splitting and `@file` Windows separators: Plugin loading lives outside this area; the argfile tokenizer keeps backslashes (as s2 after revert 864148d9f0), so `C:\a\b` paths survive except a trailing `\` (F1).
- F2 classpath-related s2 fix eef877c370/5e46039e99 ("jar or zip must be a regular file"): a directory named `x.jar` given to `-d` makes s3 `setFileContainer` (Settings.scala ~:241) take the Jar branch (`path.ext.isJar` without isDirectory check); belongs to classpath area, low confidence, not verified at runtime.
- JAVA_OPTS/SCALA_OPTS: no handling in MainGenericCompiler (done by dist scripts, not compared).
