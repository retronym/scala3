# Compiler coverage corpus

A small set of Scala/Java sources, each paired with compiler options, chosen to execute as much of the Scala 3 compiler as possible on successful compilations. The intended use is a smoke test for bytecode/TASTy compatibility: compile the corpus under two configurations (different source batching or ordering, phase fusion on/off, two compiler versions) and diff the outputs.

The corpus is selected by measurement rather than by hand: every candidate is compiled with JaCoCo attached, and greedy set cover picks the cases that add coverage per unit of size.

## Contents

- `harness.py` — everything: measuring, selection, materialising, compiling, diffing, reporting.
- `driver/CoverageDriver.java` — runs many compilation jobs in one warm JVM and dumps per-job JaCoCo data. Calls the compiler reflectively, so it works with any Scala 3 release.
- `pools.txt` — the vulpix test directories used as the candidate pool, with the options `CompilationTests` uses for each.
- `cases/` — the materialised corpus. `cases/synthetic/` holds hand-written cases; the rest are copies of selected vulpix tests (copied, so the corpus stays stable as `tests/` changes).
- `manifest.txt` — `<case under cases/> <variant> [-- <pool options>]`, one compilation job per line.
- `.work/` (ignored) — toolchain caches, campaign results, reports.

## Cases

Vulpix conventions: a case is a file or a directory. In a directory, files suffixed `_1`, `_2`, … are compiled as ordered separate stages, each against the output of the previous ones. Java files in a stage go through dotc's Java parser and then javac.

Directives in a case's files:

- `//> using options …` — scalac options for the stage containing the file. `{classes}`, `{tmp}`, `{case}` and `{libcp}` expand to the output dir, a scratch dir, the case dir and the library classpath.
- `//> using javacOpt …` — javac options.
- `//> using dep g:a:v` — an extra classpath dependency via coursier (the Scala library is excluded). `@compiler` puts the compiler itself on the classpath, e.g. to build a plugin in stage 1.
- Files under a `_sourcepath/` dir are not passed to the compiler; use them with `-sourcepath {case}/_sourcepath`.
- Non-source files in a case dir (e.g. `plugin.properties`) are copied into the output dir.

## Variants

A variant is an option set layered on top of the case's own options (see `VARIANTS` in `harness.py`): `base`, `check` (`-Ycheck:all`), `lint` (`-Wall -Wsafe-init …`), `pickler` (`-Ytest-pickler`), `opt` (`-opt -opt-inline:**`), `print` (`-Vprint:all …`), `sbt-dump`, `docs`, `profile`, `jar`, `from-tasty`, `print-tasty`, `rewrite-*`, and others. Set cover chooses (case, variant) pairs, so a case can appear under several variants.

## Workflow

Use an explicit JDK (17), and a built compiler (`--compiler local` exports the nonbootstrapped classpath via `sbt --client`; `--compiler 3.7.3` fetches a release).

```bash
python3 coverage-corpus/harness.py measure vulpix --pool @pools.txt --variants base check lint
python3 coverage-corpus/harness.py measure syn --pool cases/synthetic cases/synthetic/lang --variants base check lint
python3 coverage-corpus/harness.py select vulpix syn --out .work/sel.txt
python3 coverage-corpus/harness.py curve .work/sel.txt vulpix syn --out .work/curve
python3 coverage-corpus/harness.py materialize .work/sel.txt
python3 coverage-corpus/harness.py corpus-measure
python3 coverage-corpus/harness.py report corpus --html
python3 coverage-corpus/harness.py gaps --report latest --filter typer
```

`select` takes `--min-gain`, `--job-overhead` and `--ms-weight` to trade coverage for size; `curve` shows the trade-off (line coverage against cumulative corpus kLOC along the greedy order).

For writing new cases, keep a warm compiler running and send cases to it:

```bash
python3 coverage-corpus/harness.py serve
python3 coverage-corpus/harness.py try cases/synthetic/lang/t-gadts.scala --variants base check
```

## Comparing outputs

```bash
python3 coverage-corpus/harness.py compile --out /tmp/a
python3 coverage-corpus/harness.py compile --out /tmp/b --order reverse
python3 coverage-corpus/harness.py diff /tmp/a /tmp/b --show 40
```

`compile` takes `--compiler`, `--extra-opts`, `--order sorted|reverse|shuffle:<seed>` and `--joint` (merge separate-compilation stages into one batch). `diff` compares job status, then classfiles, TASTy and Scala.js IR byte for byte, with an optional `javap` diff per differing classfile. It exits non-zero on any difference.

## How measurement works

Each worker JVM runs the JaCoCo agent with `output=none`; after each job the driver dumps and resets the agent's probes, so every job gets its own coverage. A warm-up compile at JVM start takes the one-time initialisation coverage. Exec files are parsed directly into per-job bitsets over a per-toolchain registry of class slots, which makes set cover over tens of thousands of jobs cheap. Reported percentages are JaCoCo line coverage over all compiler classes, computed from a merged exec file synthesised from the bitsets.

Workers are `nice`d and default to one fewer than the number of cores.

## Future work

- A Scala.js variant (needs the Scala.js library classpath).
- An option to disable MegaPhase fusion, for the unfused-vs-fused comparison.
- Normalising known-benign differences in `diff` (e.g. source paths in TASTy).
