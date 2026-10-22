# Scala 2 → Scala 3 untracked fork audit

Scala 3 copied large parts of the Scala 2 compiler (classpath, IO, settings, backend, Java parser, classfile reader, utilities) between 2013 and 2017, the JVM optimizer in 2026, and the whole 2.13 standard library in 2025. There is no process for cross-porting fixes that land in scala/scala 2.13.x afterwards. This directory holds a one-off audit of that debt, done on 2026-10-22 against scala3 `a98b104aaa` and scala/scala 2.13.x `a0ebc676ba`.

The audit was LLM-assisted: Claude ran the scans, drove one Sonnet agent per area, and wrote the reports; the reproductions named as "reproduced" were run, and a few were re-checked by hand.

Rendered dashboard: https://claude.ai/artifact/UHFiTQqeEsX6ByHSiRHxWy (same content as `dashboard.html`).

## Contents

- `dashboard.html` – the findings table (66 findings: 4 high, 24 medium), filterable by direction, severity, category and follow-up verdict, avenue summaries, assessment, with GitHub links for every commit and the PR it landed in. Standalone; open in a browser.
- `findings.json` – the findings with their extracted commit, PR and issue references.
- `reports/followups/` – for every cited fix, what happened after it landed: its PR thread, linked issues and later commits in both repositories, with a verdict (clean, amended, regressed, caveat, unknown) and a porting note. `00-followup-brief.md` is the instruction given.
- `reports/` – one markdown report per audited area, in a fixed format: file pairs with port date and later upstream commit count, findings with severity, confidence, locations and the upstream fix commit, what was already ported, and method notes. `00-agent-brief.md` is the instruction every agent was given.
- `sim.tsv` – for each scala3 source file, the scala/scala 2.13.x file sharing the most exact (whitespace-normalized) lines, with counts.
- `sim_tok.tsv` – the same using identifier 6-gram shingles, which still matches files rewritten into Scala 3 syntax.
- `shas.json` – every commit sha cited in the reports, resolved to its repository, commit URL and pull request.
- `tools/sim.py`, `tools/sim2.py` – the two similarity scans. Arguments: scala3 root, a plain export of scala/scala `src/`, output path.
- `tools/resolve.py` – resolves shas found in a file to commit and PR links using local clones of both repositories.

## Areas

Forward (2.13 → 3): A classpath and IO; B settings and CLI; C backend codegen; D backend optimizer; E parsing (Java, XML, scanner, line table); F classfile reader and Scala 2 unpickler; G utilities (profiler, string interpolator rewrites, constant folding, doc comments); I standard library fork. Reverse (3 → 2.13): H the TASTy reader in scala/scala, which was copied from Scala 3 and must track its format; R a sweep of areas A-G for Scala 3 fixes never forward-ported. Not covered: `transform/` phases (conceptual ports), sbt-bridge, Scala.js backend.

## Findings in brief

Three kinds of debt turned up, not one.

Missed upstream fixes, concentrated where no re-sync ever happened: classpath resource management (2019-2025 scala/scala work: closeable registry, refcounted jar cache, `-release` in the cache key, ct.sym caching), the string interpolator rewrites (deep `+` chains overflow the stack; duplicate `f` flags only fail at runtime), array stores of `Nothing`-typed values on primitive arrays (VerifyError, scala/bug#10069), scanner and line-table details, the argfile tokenizer.

Drift from refactoring the copies without an upstream reference: the 2026 IO encapsulation refactor broke `VirtualDirectoryClassPath` for every non-root package; the "warn when catching Errors" sweep narrowed `Throwable` to `NonFatal` in `Promise`, `BatchingExecutor` and `LazyList` (reproduced on 3.10.1-RC1, see scala/scala3#26794); the optimizer port's interface lookup stops inlining Java default methods behind redundant or transitive interfaces.

Reverse debt: fixes made in the Scala 3 copies that 2.13.x never received (the inliner's JDK module-export check, NUL dropped from Java string constants, box-unbox cost guards, and others), and an inner fork where `library-js/src` full-file overrides missed fixes to `library/src`. The TASTy reader is not drifting: Scala 2's format layer is re-synced per Scala 3 minor and is identical to 3.7.x, with a deliberate freeze at 28.7.

## Follow-up pass

Every cited PR and issue was read for regressions and amendments before anyone ports a fix. Three fixes regressed after landing (the StringContext chunking in scala/scala#7996 broke non-String splices and was corrected in #8315; the classpath close/refcount scheme needed six follow-up PRs; the Scala 3 IO refactor behind the VirtualDirectory regression), nine were amended later, and several should not be ported at all: per-line argfile tokenizing (scala/scala#10319 moved toward whole-file), classfile buffer reuse (scala3#26481 removed it after measuring), the TASTy 28.7 freeze (documented policy). ArrayOps in Scala 3 is bytecode overlaid from the 2.13 jar, so its source fixes have no effect until the next stdlib bump.

## Reproducing the scans

```
git -C <scala2 clone> archive origin/2.13.x src | tar -x -C <export dir>
python3 tools/sim.py <scala3 root> <export dir> sim.tsv
python3 tools/sim2.py <scala3 root> <export dir> sim_tok.tsv
```
