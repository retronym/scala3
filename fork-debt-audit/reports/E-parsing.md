# E-parsing: Scala 2.13.x -> Scala 3 untracked fork audit (parsing, Java parser, XML, Chars, SourceFile)

Scala 3 checkout: /Users/jzaugg-virtus/code/.worktrees/scala3/scala3-untracked-fork-debt-803909 (a98b104aaa). Scala 2: origin/2.13.x.
Scratch tests (all runnable with the JDK + cached 3.10.2-RC1-bin-20261019 nightly jars, `-Ystop-after:typer|parser`): <scratchpad>/work/E-parsing/ (t1 = text block crash, t9/t10 = `@interface`, x1 = XML, sf = SourceFile/bidi, t12/t13 = Java constants, env.sh = classpath).

## Pairs

| s3 file | s2 file | port date | later s2 commits | notes |
|---|---|---|---|---|
| parsing/JavaParsers.scala | nsc/javac/JavaParsers.scala | 2014-09-04 (03627e71d9) | ~75 since 2013, ~30 are fixes/features | Kept in sync well: records, sealed/permits, non-sealed, text blocks, type annotations, private iface methods, JEP 445, wildcard annots, valueOf param name all ported (often Scala 3 -> Scala 2 direction in 2024-26). |
| parsing/JavaScanners.scala | nsc/javac/JavaScanners.scala | 2014-09-04 | ~10 | Text block port 1dc5fcac04 (2022) has a bug Scala 2 fixed (F1). |
| parsing/JavaTokens.scala | nsc/javac/JavaTokens.scala (+ ast/parser/Tokens) | 2014-09-04 | ~2 | nothing |
| parsing/Scanners.scala | nsc/ast/parser/Scanners.scala | 2013-04-17 | ~100 | Heavily rewritten (sim 0.11). Several Scala 2 features there were ported FROM Scala 3 (`\s`, `$"`, supplementary chars). Only Scala-2-only delta: bidi check (F2). |
| parsing/CharArrayReader.scala | nsc/ast/parser/CharArrayReader.scala | 2013-04-17 | ~8 | Reworked in Scala 3 (unicode escapes as ordinary escapes, 006b647842). Bidi hook missing (F2). |
| parsing/Tokens.scala | ast/parser/Tokens.scala | 2013 | n/a | Scala 3 specific (indent, cc). |
| parsing/ScriptParsers.scala | ast/parser/ (n/a) | 2013 | 0 | shebang support removed in 30ca358d1a. |
| parsing/xml/MarkupParsers.scala | ast/parser/MarkupParsers.scala | 2013-05-09 (cf4c428cc5) | ~12 after 2013 | SI-9027 ported; SI-5154, SI-3368 not (F4, F5). Positions: 9546c5614c ported the span work. |
| parsing/xml/MarkupParserCommon.scala, Utility.scala | ast/parser/xml/* | 2013/2014 | 0 meaningful | in sync |
| parsing/xml/SymbolicXMLBuilder.scala | ast/parser/SymbolicXMLBuilder.scala | 2013-04-19 | ~8 | SI-8253 (xmlns={f}) verified correct in Scala 3; CDATA node difference (F5). |
| util/Chars.scala | reflect/internal/Chars.scala | 2020 move (1054730d74) | 3 | only `isBiDiCharacter` missing (F2); supplementary overloads present. |
| util/SourceFile.scala | reflect/internal/util/SourceFile.scala | 2013 (ca8dc7ada6) | ~20 | Line index treats FF and SU as line breaks (F3). Otherwise rewritten. |
| util/CommentParsing.scala | nsc/util/DocStrings.scala | 2016 (1b20568bdb) | 1 (refactor only, 62ad8e44a3) | in sync |

## Findings

### F1: Unclosed Java text block crashes the compiler (StringIndexOutOfBoundsException) instead of reporting "unclosed text block"
- severity: medium
- confidence: high (reproduced)
- s3: compiler/src/dotty/tools/dotc/parsing/JavaScanners.scala:500 (loop condition) and :528 (`in.getlitch(scanOnly = true, ...)`)
- s2: src/compiler/scala/tools/nsc/javac/JavaScanners.scala ; fix commit: 4a918afd51 2022-05-03 "Improve lookahead during java textblock" (loop condition uses `lookahead.isUnicode`/`lookahead.ch`)
- what: The lookahead pass in `getTextBlock` advances a `LookaheadScanner` (`in`), but the loop guard `while (!blockClosed && (isUnicodeEscape || ch != SU))` tests the OUTER scanner's `ch`, which never moves during the pass. If the closing `"""` is missing, `in` runs off the end of the buffer (`in.getlitch` at EOF keeps calling `nextChar`), the guard never becomes false, and `StringReader.nextChar` ends up indexing with a bogus offset (Int.MinValue) -> `StringIndexOutOfBoundsException` from the parser phase ("An unhandled exception was thrown in the compiler"). The later `if (!blockClosed) error("unclosed text block")` is unreachable. Scala 2's 4a918afd51 reads the guard from the lookahead reader. Observable: a malformed .java file (very plausible while editing in an IDE / presentation compiler) crashes instead of producing a diagnostic.
- verified: ran the 3.10.2-RC1 nightly (20261019, same JavaScanners logic as HEAD) on `String s = """\n abc\n def;\n}` -> crash, trace `JavaScanners.getlitch(472) <- getTextBlock(528) <- fetchToken(120)` (work/E-parsing/t1). Read the HEAD source: guard is on outer `ch` (line 500). Fix is `while (!blockClosed && (in.isUnicodeEscape || in.ch != SU))` (and make sure `in.getlitch` at SU terminates).

### F2: No error for Unicode bidirectional formatting characters in source (Trojan Source, CVE-2021-42574)
- severity: medium (security hygiene; no wrong code generated)
- confidence: high (reproduced)
- s3: compiler/src/dotty/tools/dotc/util/Chars.scala (no `isBiDiCharacter`), compiler/src/dotty/tools/dotc/parsing/Scanners.scala + CharArrayReader.scala (no tracking / reporting)
- s2: src/reflect/scala/reflect/internal/Chars.scala, src/compiler/scala/tools/nsc/ast/parser/{Scanners,CharArrayReader}.scala ; fix commits: fd97b40579 2022-04-26 "Error on source files with unicode directional formatting characters" (scala/bug#12478, backport ec599d3242), loosened by e5883add4f 2022-05-09 (only U+202A-202E and U+2066-2069 are flagged). Tests: test/files/neg/t12478.scala, run/t12478.scala.
- what: Scala 2.13.9+ records bidi control characters while scanning (in string/char literals, comments, identifiers) and reports "found unicode bidirectional character '‮'; use a unicode escape instead" at EOF. Scala 3 has no equivalent, so a source file containing e.g. RLO (U+202E) in a string or comment compiles silently, which is exactly the attack the CVE describes.
- verified: grep -rniE "bidi|directional|202a|202e|2066" compiler/src -> no scanner hits; git log --grep "bidi|directional|trojan" -> none. Compiled a file with U+202E in a string literal, comment and char literal with the nightly -> no diagnostics (work/E-parsing/sf/Bidi.scala).

### F3: FF (form feed) and SU (Ctrl-Z) are counted as line terminators in SourceFile line tables -> wrong line numbers
- severity: low-medium
- confidence: high for the behaviour (reproduced); medium that it is unintentional
- s3: compiler/src/dotty/tools/dotc/util/SourceFile.scala:154-159 (`calculateLineIndicesFromContents` uses `Chars.isLineBreakChar` = LF|FF|CR|SU)
- s2: src/reflect/scala/reflect/internal/util/SourceFile.scala (`isAtEndOfLine` only CR|LF) ; fix commits: bb2e99a692 2013-12-17 / 8be560a1cf 2013-12-18 "SI-8015 Count lines by EOLs"; related f584f5b4b8 "SI-8205 Don't include CR in line", d83a00aaba "SI-9885 Don't return offset past EOF" (this one is covered by Scala 3's `if offset >= length then lastLine -= 1`).
- what: SI-8015 changed Scala 2 line numbering to follow real EOLs only (CR, LF, CRLF), matching editors and javac. Scala 3 still uses the pre-SI-8015 definition from Chars, so each `\f` or `\u001a` in a file shifts every later line number by one in diagnostics, and via `offsetToLine` also in the emitted LineNumberTable / stack traces. Form feeds do occur in GNU-style sources.
- verified: file `object FF {\n  val a = 1\n\f\n  val b: String = 2\n}` -> Scala 3 reports the error at line 5 (physically line 4); the CRLF case is correct (CR LF counted once). (work/E-parsing/sf/FF.scala). Note SU already is an "illegal character" in the scanner so the SU part is moot; FF is the real case.

### F4: XML pattern with leading literal brace `{{` fails to parse (SI-5154)
- severity: low
- confidence: high (reproduced)
- s3: compiler/src/dotty/tools/dotc/parsing/xml/MarkupParsers.scala:458 (xPattern `case '{'`)
- s2: src/compiler/scala/tools/nsc/ast/parser/MarkupParsers.scala ; fix commit: 4d96a8ad0b 2014-12-16 "SI-5154 Parse leading literal brace in XML pattern" (`case '{' if xCheckEmbeddedBlock => ... do { ... } while (xCheckEmbeddedBlock)`)
- what: In `xPattern` the branch for `{` unconditionally treats it as the start of an embedded Scala pattern (`while (ch == '{') { nextch(); xScalaPatterns }`) and never checks for the `{{` escape. So `case <z>{{3}}</z> =>` (no leading space) is parsed as an embedded pattern `{3}}` and gives "pattern expected"; with a leading space the text parser handles it. The expression side (`content_BRACE`) already uses `xCheckEmbeddedBlock`, which is why only patterns break.
- verified: `def g = <z>{{3}}</z> match { case <z>{{3}}</z> => }` with the nightly (-Ystop-after:parser) -> `pattern expected` at the pattern; the variant with a space before `{{` parses. (work/E-parsing/x1/X.scala)

### F5: XML `<![CDATA[...]]>` becomes a plain `Text` node, not `PCData` (SI-3368)
- severity: low-medium (behavioural difference vs Scala 2 / scala-xml expectations)
- confidence: high that behaviour differs; medium that it is accidental (Scala 3 never had it)
- s3: compiler/src/dotty/tools/dotc/parsing/xml/SymbolicXMLBuilder.scala:137 (`charData` -> `makeText1`), MarkupParsers.scala `xCharData`/`content`
- s2: src/compiler/scala/tools/nsc/ast/parser/{MarkupParsers,SymbolicXMLBuilder}.scala ; fix commit: 4df81aab31 2014-12-20 "SI-3368 CDATA gets a Node" (+ 996d066bb3 `-Xxml:coalescing`, 498a07fcd8)
- what: Scala 2 (2.11+) emits `new scala.xml.PCData("...")` for CDATA, so `<a><![CDATA[hi & bye]]></a>.toString` round-trips as CDATA; `-Xxml:coalescing` optionally merges PCData into Text and merges sibling text. Scala 3 emits `new scala.xml.Text("hi & bye")` (so the output is `hi &amp; bye`) and also never has a coalescing mode: adjacent text/CDATA stay separate `Text` children. The same source thus builds different XML trees in Scala 2.13 and Scala 3.
- verified: -Vprint:parser on `<a><b/>start<![CDATA[hi & bye]]><c/>world<d/>stuff<![CDATA[red & black]]></a>` -> `$buf.&+(new Text("start")); $buf.&+(new Text("hi & bye"))...`; grep PCData|coalesc in parsing/xml -> only the charData above. SI-9027 (multi-element lookahead), SI-8253 (xmlns={f("a")}) are fine in Scala 3 (checked output trees).

### F6: Java 7 numeric literals (`1_000`, `0b101`) and hex floats are not understood by the Java scanner, so `static final` constants silently lose their constant type
- severity: low
- confidence: high (reproduced). SHARED gap: Scala 2.13.x JavaScanners.getNumber is identical in this respect, so this is not "fix not ported" but worth listing.
- s3: compiler/src/dotty/tools/dotc/parsing/JavaScanners.scala:679 (`getNumber`, `getFraction`), base detection around :89-99
- s2: src/compiler/scala/tools/nsc/javac/JavaScanners.scala:942 (same)
- what: `getNumber` accepts only digits of the radix; there is no `_` separator, no `0b` prefix and no hex-float. `public static final int I = 1_000;` is not turned into a constant (`val x: 1000 = Foo.I` fails: found `(Foo.I : Int)`), and the same literal as an annotation argument falls into the "cannot parse -> drop the whole annotation silently" path. No error is reported either.
- verified: work/E-parsing/t13 and t12: I (`1_000`), J (`0b101`/32-bit binary), L (`0x1.8p1`), M (`1_0L`) and D (`1_0.5`) all lose the constant type, while `0xFFFFFFFF`, `037777777777`, `0777L`, `-2147483648`, `0x8000...L` are folded correctly.

### F7: A file-leading `@interface` (default package, no imports before it) fails to parse in the Java parser
- severity: low
- confidence: high (reproduced); Scala 2 has the same structure (annotations() consumed before looking at `interface`), not run
- s3: compiler/src/dotty/tools/dotc/parsing/JavaParsers.scala:1122 (`val leadingAnnots = if (in.token == AT) annotations() else Nil`)
- s2: src/compiler/scala/tools/nsc/javac/JavaParsers.scala ; relevant: f9e0b1a03a 2024-04-01 "Java parses leading annotation" (Scala 3's equivalent fdf73696ac). Both guard `modifiers()` with `AT if in.lookaheadToken != INTERFACE` but not the `compilationUnit` prologue.
- what: `compilationUnit` calls `annotations()` whenever the first token is `@`, which does `nextToken(); annotation()` and trips on the `interface` keyword ("identifier expected but interface found"), followed by "illegal start of type declaration". Valid Java such as a file whose first declaration is `@interface P { ... }` (or `@Retention(...) @interface P`, i.e. second `@` is also unguarded) in the default package is rejected. `@interface` after a `package` or `import` works.
- verified: work/E-parsing/t9/P.java and t10/P.java with the nightly -> errors above; same annotation after an import (t3) parses.

### F8: Java `throws` clauses are discarded (Scala 2 now keeps them as `@throws`)
- severity: low
- confidence: low (the Scala 3 consequence is not demonstrated)
- s3: compiler/src/dotty/tools/dotc/parsing/JavaParsers.scala:590 (`optThrows(): Unit` just skips)
- s2: src/compiler/scala/tools/nsc/javac/JavaParsers.scala ; fix commit: c8aab64ef8 2026-10-09 "Retain `throws` clauses of Java methods parsed from source"
- what: Scala 2 now makes source-parsed Java methods carry `@throws[T]()` (type variables erased) so forwarders to them get an `Exceptions` attribute exactly like when the Java class comes from a classfile. Scala 3's `ClassfileParser.parseExceptions` does attach `ThrowsAnnotation`s for classfile-loaded methods and `BCodeSkelBuilder:790` emits `Exceptions` from them, but source-parsed Java methods have none, so any forwarder generated to a Java method compiled in the same run would lack the attribute. Very new in Scala 2, effect on Scala 3 not tested.
- verified: grep `throws` in JavaParsers.scala -> only `optThrows` skip; ClassfileParser.scala:954 / 1037-1082 for the classfile path.

## Already ported / not applicable

Verified present in Scala 3 (by code + behaviour test on the nightly):
- Java records (f7ae7af0b3 etc.: accessors, compact ctors, varargs, generics, static members, member records static), sealed/permits/non-sealed, JEP 445 compact units (1f8442558f), text blocks (a8225a093d / 4a918afd51 content logic except the lookahead guard, F1), private interface methods (3b4dafff15), default/static interface methods, Java annotations incl. trailing commas, empty `{}` arrays, nested annotations, class literals, unsupported expressions skipped (3a4c1c100d; Scala 3's `skipTo` is nesting aware), annotations on arrays/wildcards/qualified types (f8100fde1b, 57b4f239c6, da32bca6f0, f9e0b1a03a), `?` bound -> Object (68f5900a52, `ObjectTpt()`), strictfp/synchronized consumed (eda5d59b3f/e412a12e31), constant folding of literal static finals (81d2c6185e), enum-with-bodies exhaustivity (11783c3c2a, tested), importing from types and static members (9c32846e85, tested), enum valueOf parameter name (d12e8a6063 = 50068e51eb), root-of-selection lookup (f64a398422: `java` field vs `java.lang.Integer` type resolves fine in Scala 3, tested), unicode escapes in Java identifiers/char literals, `\s`, octal escapes, `yield`/`var` as identifiers, receiver-parameter (not supported by either), `module-info.java` (not handled by either; scalac on it reports a parse error in Scala 3).
- Scala 2 features that came FROM Scala 3 (port direction reversed): `\s` escape (0308ce1292 2026-08, Scala 3 702fa74c1c), `$"` escaping the quote in interpolations (b9e49f9b45), Java annotations on array dims (f8100fde1b cites scala/scala3#22391), wildcard annotations (57b4f239c6 / 52af9993cb), SIP-42 binary literals, supplementary-character identifiers (b124a5434f/d97a0a336b; Scala 3 already has `atSupplementary`).
- Scanner numeric literals: `0x`, `0b`, `_` separators, trailing separators ("trailing separator is not allowed"), `1e400`, `1e-400`, `1e50f`, `1e-50f`, 65-bit hex/binary all produce errors; `0x_F` and `1__0` accepted in both compilers.
- SI-9027 (xml multi-element lookahead, `charComingAfter` loop), SI-8253 (xmlns={f("a")}), SI-5702, xml position fixes (85136e3f25, f428cc6d94 -> Scala 3 9546c5614c), `toVector` for XML sequences (71dc0c38d8 = Scala 3 01b34ce852).
- Chars: all Scala 2 differences are Scala 3 additions (escapedString, isValidJVM*); `digit2int`, `isIdentifierStart/Part`, CodePoint overloads identical.
- SourceFile: CR/CRLF handled (CRLF not double counted, tested), offset >= length compensation present, BOM: neither compiler strips U+FEFF (both report "illegal character '﻿'").
- DocStrings / CommentParsing: only a refactor (62ad8e44a3) since the port.

## Method notes
- The Scala 3 scanner (Scanners.scala) is a substantial rewrite (similarity 0.11); for it I tested behaviour with the cached 3.10.2-RC1-bin-20261019-ae374e9 nightly (java -cp scala3-compiler... dotty.tools.dotc.Main -Ystop-after:...) rather than diffing text. Codegen fails with a missing org.objectweb.asm in my ad-hoc classpath, so all tests stop at typer/parser (enough for parse-level issues). HEAD a98b104aaa differs from the nightly by only a few unrelated commits; I re-read the HEAD source for each finding.
- Java parser: the port is mostly an independent re-implementation kept in sync by hand; recent Scala 2 Java-parser fixes are frequently ports FROM Scala 3 (c8aab64ef8 and d12e8a6063 excepted). Findings F6/F7 are shared gaps rather than unported fixes.
- scala/scala 2026 dates in the logs (e.g. 0308ce1292 2026-08-18) are real in this checkout; no filtering by date was applied.
