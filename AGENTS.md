# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

**Important**:
- All AI-assisted contributions, including  code, PR/issue descriptions, comments, and code reviews, must comply with the [LLM usage policy](LLM_POLICY.md) . **Important**: state LLM usage clearly in descriptions, commit messages, etc.
- Read `CONTRIBUTING.md`, in particular the "Forbidden" section!

## Project Overview

Dotty is the Scala 3 compiler (`dotc`). It compiles Scala source code into JVM bytecode (and optionally Scala.js IR). The compiler is itself written in Scala 3.

We care about maintainability. "The tests pass" is not enough to justify that a change is good.

## Build Commands

Use `sbt --client` (sbt with native thin client) to run commands. This keeps an sbt server running for faster subsequent invocations. Run `sbt --client shutdown` when done, and before starting a server with different `-D` options or environment.

Builds and test runs take tens of seconds to minutes; run them in the background and check the output rather than blocking. Measured timings, and the reasoning behind each recipe below, are in [Getting Started](docs/_docs/contributing/getting-started.md) and [Testing](docs/_docs/contributing/testing.md).

### Compilation
- `SCALA3_OPTIMIZE=false sbt --client buildQuick` - Build the nonbootstrapped compiler and REPL, skipping the optimizer (faster cold builds), and write `bin/.cp`
- `bin/scalacQ -d local/out <file>` / `bin/scalaQ -cp local/out <main>` - Compile and run one file against that build (the `-d` directory must exist)
- `sbt --client "scalac -d local/out <file>"` - The same through sbt (the sbt `scala` task's program output does not reach `--client`; use `bin/scalaQ`)
- `sbt --client scala3-compiler-bootstrapped/compile` - Build the bootstrapped compiler

Use the `local/` directory for temporary test files (gitignored).

### Testing
- `sbt --client "testCompilation <filter>"` - Run compilation tests (`tests/pos`, `tests/neg`, `tests/run`, ...) whose path contains `<filter>`; several filters are comma-separated, not space-separated
- `sbt --client "testCompilation --failed"` / `"testCompilation --update-checkfiles <filter>"` - Re-run the failures of the last run / overwrite `.check` files with actual output
- `sbt --client "scala3-bootstrapped/testCompilation <filter>"` - Bootstrapped-only groups, including `tests/plugins`, `*-with-compiler`, `run-macros`
- `sbt --client "scala3-compiler-nonbootstrapped/testOnly dotty.tools.dotc.CompilationTests -- --tests=runAll -Ddotty.tests.filter=<filter>"` - One test group plus a file filter
- `sbt --client scala3-bootstrapped/test` - Run all tests (slow, CI-level)

A filter that matches nothing still succeeds. Check the `== Vulpix Test Report: all N suites passed (M skipped) ==` line: `N` must be non-zero.

### Git
Use a full clone, not a shallow one, for PR work: amending or rebasing in a shallow clone can create a parentless commit, and force-pushing it to a PR branch makes GitHub close the PR for good.


## Test Directory Structure

Tests are in `tests/` with categories:
- `tests/pos` - Should compile successfully
- `tests/neg` - Should fail to compile (requires `// error` annotations on expected error lines)
- `tests/run` - Compile and execute (requires `@main def Test` or `object Test extends App`)
- `tests/pos-custom-args`, `tests/neg-custom-args` - Tests requiring special compiler options

Custom compiler options can be specified:
1. Per-directory in `compiler/test/dotty/tools/dotc/CompilationTests.scala`
2. Per-file with `//> using options <flags>` directive at the top of the file

Capture checking tests are in `tests/{pos,neg}-custom-args/captures`. Use `-Ycc-verbose` for verbose capture checking output.

Multi-file tests: Use `_1.scala`, `_2.scala` suffixes for ordered separate compilation, or no suffix for joint compilation.

Checkfiles: `<test>.check` files contain expected compiler output or program output.

Test annotations (magic comments):
- `// error` - Expect an error on this line (use multiple times for multiple errors)
- `// warn` - Expect a warning on this line
- `// nopos-error` - Expect an error with no position (can be anywhere in file)
- `// anypos-error` - Expect an error with position that can't be annotated
- `// scalajs: --skip` - Skip this test for Scala.js

## Bootstrapping

The compiler has two configurations:
- **nonbootstrapped** - Built with a released Scala 3 compiler (faster iteration)
- **bootstrapped** - Built with itself (required for some tests, final validation)

Use `scala3-bootstrapped/testCompilation` for tests requiring the bootstrapped compiler.

## Debugging

Compiler flags for inspecting a compilation (`-Vprint:<phase>`, `-Ycheck:all`, `-Ylog:<phase>`, `-Ydebug-error`), and how to try a phase as a research plugin with `-Xplugin`, are in `docs/_docs/contributing/debugging/` and `docs/_docs/contributing/issues/`.

## Documentation

Documentation lives in `docs/_docs/`:
- `docs/_docs/reference/` - Scala 3 language reference
- `docs/_docs/reference/experimental/capture-checking/` - Capture checking specification
- `docs/_docs/internals/` - Compiler internals documentation

For available AI-assisted documentation workflows or skills start refer to [./docs/.ai/INDEX.md](./docs/.ai/INDEX.md)
