---
layout: doc-page
title: Getting Started
redirectFrom: /docs/contributing/workflow.html
---

## Scala CLA

Sometime before submitting your pull request you'll want to make sure you have
signed the [Scala CLA][scala-cla]. You can read more about why we require a CLA
and what exactly is included in it [here][scala-cla].

## Making sure the team is aware

Before digging into an issue or starting on a new feature it's a good idea to
make sure an [issue][dotty-issue] or a [discussion][dotty-discussion] has been
created outlining what you plan to work on. This is both for your and the team's
benefit. It ensures you get the help you need, and also gives the compiler team
a heads-up that someone is working on an issue.

For some small changes like documentation, this isn't always necessary, but it's
never a bad idea to check.

## Requirements

- [git] is essential for managing the Scala 3 code, and contributing to GitHub,
  where the code is hosted.
- A Java Virtual Machine (JDK 8 or higher), required for running the build tool. Do note, however, that the continuous integration test suite is run on JDK 17.
    - There are multiple ways to get and manage different Java versions. Some
      popular options are:
        - [SDKMAN!](https://sdkman.io/)
        - [Coursier](https://get-coursier.io/docs/cli-java)
        - Or just download Java directly from [Oracle Java 8][java8], [Oracle
          Java 11][java11], or [AdoptOpenJDK 8/11][adopt]. Refer to [JDK
          Compatibility][compat] for Scala/Java compatibility detail.
  - Verify that the JVM is installed by running the following command in a terminal: `java -version`.
- [sbt][sbt-download], the build tool required to build the Scala 3 compiler and libraries.

## Nice To Have

An IDE, such as [Metals] will help you develop in Scala 3 with features such as
autocompletion, code navigation, debugging, and interactive worksheets.

Another popular options is [IntelliJ IDEA for
Scala](https://www.jetbrains.com/help/idea/discover-intellij-idea-for-scala.html).

## Compiling and Running

Start by cloning the repository:

```bash
$ git clone https://github.com/scala/scala3.git
$ cd scala3
```

Dotty provides a standard sbt build: compiling, running and starting a repl can
all be done from within sbt:

```bash
$ sbt
> scalac tests/pos/HelloWorld.scala
> scala HelloWorld
hello world
```

There are also bash scripts that can be used in the same way. Assuming that you
have cloned the Dotty repo locally, append the following line on your
`.bash_profile`, or equivalent for your shell:

```shell
$ export PATH=<path to cloned dotty>/bin:$PATH
```

and you will be able to run the corresponding commands directly from your console:

```shell
# Compile code using Dotty
$ scalac tests/pos/HelloWorld.scala

# Run it with the proper classpath
$ scala HelloWorld
```

Note that the `scalac` and `scala` scripts have slow roundtrip times when working on the compiler codebase: whenever
any source file changes they invoke `sbt dist/pack` first.

As an alternative, run the `buildQuick` task in sbt. It builds the compiler and writes its classpath to the `bin/.cp`
file, which enables  the `scalacQ` and `scalaQ` scripts in the `bin/` folder.

```bash
$ sbt --client buildQuick
$ mkdir -p local/out
$ bin/scalacQ -d local/out local/Hello.scala
$ bin/scalaQ -cp local/out hello
```

`scalacQ` fails with `'local/out' does not exist` unless the output directory exists. The scripts read `bin/.cp` but do not rebuild, so rerun `buildQuick` after each change to the compiler. Prefer `scalaQ` to the sbt `scala` task with `sbt --client`: the task runs the program inside the sbt server, and its output does not reach the client.

## Build Configurations and Timings

The build defines each compiler and library project twice:

| Configuration | Aggregate project | Compiler project | Built with |
|---|---|---|---|
| nonbootstrapped | `scala3-nonbootstrapped` (the root project) | `scala3-compiler-nonbootstrapped` | the released reference compiler |
| bootstrapped | `scala3-bootstrapped` | `scala3-compiler-bootstrapped` | the nonbootstrapped compiler |

Iterate on the nonbootstrapped compiler; it is what `buildQuick`, `scalac`, `testCompilation` and `test` in the root project use. Build the bootstrapped compiler to check that the compiler still compiles itself, and to run the bootstrapped-only tests (see [Testing](testing.md#bootstrapped-only-tests)).

The standard library and the bootstrapped compiler are compiled with the optimizer (`-opt -opt-inline:...`), which makes cold builds noticeably slower. Turn it off for local work with the `scala3Optimize` setting, either when the sbt server starts or in a running session:

```bash
$ SCALA3_OPTIMIZE=false sbt --client buildQuick    # or: sbt -Dscala3.optimize=false
$ sbt --client "set ThisBuild / Build.scala3Optimize := false"
```

The environment variable and `-D` option are read when the sbt server starts, so `sbt --client shutdown` first if one is already running. Changing the setting changes `scalacOptions`, so the next build recompiles the affected projects from scratch. Keep the optimizer on for benchmarking and publishing.

Measured on an Apple M5 Pro (18 cores, 48 GB, JDK 21), with the sbt server already running and dependencies already downloaded:

| Step | Optimizer on | Optimizer off |
|---|---|---|
| Cold `scala3-compiler-nonbootstrapped/compile` (stdlib, tasty-core, compiler) | 61–77 s | 49 s |
| Cold `scala3-compiler-bootstrapped/compile`, nonbootstrapped already built | 78 s | 45 s |
| `buildQuick` after the above (REPL, directives parser) | | 3 s |
| `buildQuick` after a method-body edit in `Typer.scala` | | 4 s |
| `buildQuick` after adding a member to `object Types` (162 files recompiled) | | 18 s |
| Compile and run a one-line file with `bin/scalacQ` + `bin/scalaQ` | | 1 s |

### Interrupted builds

While compiling, Zinc moves the class files it is about to replace into a `classes.bak` directory next to `classes`. If sbt is killed mid-compile, that directory is left behind and `classes` is incomplete; the next compile restores from it and recompiles, so no manual cleanup is normally needed. If errors that mention missing classes persist, clean the one project (`sbt --client scala3-compiler-nonbootstrapped/clean`) rather than the whole build, and remove the Vulpix output with `rm -rf out/`.

## Starting a REPL

```bash
$ sbt
> repl
Welcome to Scala.next (pre-alpha)  (Java HotSpot(TM) 64-Bit Server VM, Java 1.8.0_101).
Type in expressions to have them evaluated.
Type :help for more information.
scala>
```

or via bash:

```bash
$ scala
```

## Publish to local repository

To test our cloned compiler on local projects:

```bash
$ sbt publishLocal
```
Then in the `build.sbt` file of a test project:

```bash
ThisBuild / scalaVersion := "<dotty-version>-bin-SNAPSHOT"
```
where `dotty-version` can be found in the file `project/Build.scala`, like `3.0.0-M2`


## Generating Documentation

To generate this page and other static page docs, run

```bash
$ sbt
> scaladoc/generateScalaDocumentation
```

For more information, see the [scaladoc section](./scaladoc.md).

## Community

The main development discussion channels are:
- [github.com/scala/scala3/discussions](https://github.com/scala/scala3/discussions)
- [contributors.scala-lang.org](https://contributors.scala-lang.org)

[git]: https://git-scm.com
[Metals]: https://scalameta.org/metals/
[vs-code]: https://code.visualstudio.com
[lampepfl/dotty]: https://github.com/lampepfl/dotty
[sbt-download]: https://www.scala-sbt.org/download/
[java8]: https://www.oracle.com/java/technologies/javase-jdk8-downloads.html
[java11]: https://www.oracle.com/java/technologies/javase-jdk11-downloads.html
[adopt]: https://adoptopenjdk.net/
[compat]: https://docs.scala-lang.org/overviews/jdk-compatibility/overview.html
[scala-cla]: https://cla.scala-lang.org/scala/scala3
[dotty-issue]: https://github.com/scala/scala3/issues
[dotty-discussion]: https://github.com/scala/scala3/discussions
