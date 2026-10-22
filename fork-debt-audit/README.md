# Scala 2 → Scala 3 untracked fork audit

Scala 3 copied large parts of the Scala 2 compiler (classpath, IO, settings, backend, Java parser, classfile reader, utilities) between 2013 and 2017, the JVM optimizer in 2026, and the whole 2.13 standard library in 2025. There is no process for cross-porting fixes that land in scala/scala 2.13.x afterwards. This directory holds a one-off audit of that debt, done on 2026-10-22 against scala3 `a98b104aaa` and scala/scala 2.13.x `a0ebc676ba`.

The audit was LLM-assisted: Claude ran the scans, drove one Sonnet agent per area, and wrote the reports; the reproductions named as "reproduced" were run, and a few were re-checked by hand.

Rendered dashboard: https://claude.ai/artifact/UHFiTQqeEsX6ByHSiRHxWy (same content as `dashboard.html`).

## Contents

- `dashboard.html` – the findings table (52 findings: 3 high, 17 medium), avenue summaries, assessment, with GitHub links for every commit and the PR it landed in. Standalone; open in a browser.
- `reports/` – one markdown report per audited area, in a fixed format: file pairs with port date and later upstream commit count, findings with severity, confidence, locations and the upstream fix commit, what was already ported, and method notes. `00-agent-brief.md` is the instruction every agent was given.
- `sim.tsv` – for each scala3 source file, the scala/scala 2.13.x file sharing the most exact (whitespace-normalized) lines, with counts.
- `sim_tok.tsv` – the same using identifier 6-gram shingles, which still matches files rewritten into Scala 3 syntax.
- `shas.json` – every commit sha cited in the reports, resolved to its repository, commit URL and pull request.
- `tools/sim.py`, `tools/sim2.py` – the two similarity scans. Arguments: scala3 root, a plain export of scala/scala `src/`, output path.
- `tools/resolve.py` – resolves shas found in a file to commit and PR links using local clones of both repositories.

## Areas

A classpath and IO; B settings and CLI; C backend codegen; D backend optimizer; E parsing (Java, XML, scanner, line table); F classfile reader and Scala 2 unpickler; G utilities (profiler, string interpolator rewrites, constant folding, doc comments); I standard library fork. TASTy (`tasty/src`) was skipped: Scala 2 copied it from Scala 3, so the debt flows the other way. Not covered: `transform/` phases (conceptual ports), sbt-bridge, Scala.js backend.

## Findings in brief

Three kinds of debt turned up, not one.

Missed upstream fixes, concentrated where no re-sync ever happened: classpath resource management (2019-2025 scala/scala work: closeable registry, refcounted jar cache, `-release` in the cache key, ct.sym caching), the string interpolator rewrites (deep `+` chains overflow the stack; duplicate `f` flags only fail at runtime), array stores of `Nothing`-typed values on primitive arrays (VerifyError, scala/bug#10069), scanner and line-table details, the argfile tokenizer.

Drift from refactoring the copies without an upstream reference: the 2026 IO encapsulation refactor broke `VirtualDirectoryClassPath` for every non-root package; the "warn when catching Errors" sweep narrowed `Throwable` to `NonFatal` in `Promise`, `BatchingExecutor` and `LazyList` (reproduced on 3.10.1-RC1, see scala/scala3#26794); the optimizer port's interface lookup stops inlining Java default methods behind redundant or transitive interfaces.

Reverse debt: fixes made in the Scala 3 copies that 2.13.x never received, and an inner fork where `library-js/src` full-file overrides missed fixes to `library/src`.

## Reproducing the scans

```
git -C <scala2 clone> archive origin/2.13.x src | tar -x -C <export dir>
python3 tools/sim.py <scala3 root> <export dir> sim.tsv
python3 tools/sim2.py <scala3 root> <export dir> sim_tok.tsv
```
