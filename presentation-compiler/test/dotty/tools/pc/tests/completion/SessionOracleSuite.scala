package dotty.tools.pc.tests.completion

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls
import scala.util.Random

import dotty.tools.dotc.CompilationUnit.CancelledCompilation
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Interactive, InteractiveDriver}
import dotty.tools.dotc.parsing.{Scanners, Tokens}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.base.BasePCSuite

import org.junit.Test

/** The session against an oracle, on real code: for a few files of the
 *  compiler's own sources, a hover at the end of *every* identifier (what the
 *  x-ray page sends), in a shuffled order on one driver, with cancellations
 *  thrown in, must describe the same tree (its class, span, type and
 *  symbol) as a fresh whole-file run does, and so must the hovers of
 *  earlier offsets after each later request. The soak suite's points are
 *  hand-placed completions in generated files; this covers the shapes real
 *  code has (match cases, while bodies, local defs, nested blocks) without
 *  anyone placing a point.
 *
 *  A targeted run's answer at an offset is the typed tree at that offset
 *  after `runUpTo`: served, resumed or fresh. A stub left in a block, a
 *  statement typed but not spliced, a resumed statement typed in the wrong
 *  context all show up here as a different tree or type than the oracle's.
 */
class SessionOracleSuite extends BasePCSuite:

  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0",
      "-classpath", sys.props.getOrElse("java.class.path", ""))

  private def newDriver(bodyEdits: Boolean = false) =
    new InteractiveDriver(if bodyEdits then settings :+ "-Yinteractive-body-edits" else settings, CachedLogicalPackage.none)

  /** The right-hand sides of the member definitions (of packages and
   *  templates) of the unit of `uri` on `driver`.
   */
  private def memberBodies(driver: InteractiveDriver, uri: URI): List[Span] =
    val out = mutable.ListBuffer.empty[Span]
    given Context = driver.currentCtx
    def stats(ts: List[tpd.Tree]): Unit = ts.foreach {
      case p: tpd.PackageDef => stats(p.stats)
      case t: tpd.TypeDef => t.rhs match
        case impl: tpd.Template => stats(impl.body)
        case _ =>
      case d: tpd.ValOrDefDef if d.span.exists && !d.span.isSynthetic =>
        if d.rhs.span.exists && !d.rhs.span.isZeroExtent then out += d.rhs.span
      case _ =>
    }
    driver.compilationUnits.get(uri).foreach(u => stats(u.tpdTree :: Nil))
    out.toList

  /** An edit inside a member's body: a decimal integer literal there gets
   *  one more digit (`0` becomes `10`), or None if there is none.
   */
  private def bodyEdit(driver: InteractiveDriver, uri: URI, content: String, rng: Random): Option[String] =
    val bodies = memberBodies(driver, uri)
    val scanner = new Scanners.Scanner(SourceFile.virtual(uri, content))(using driver.currentCtx)
    val lits = mutable.ListBuffer.empty[(Int, Int)]
    while scanner.token != Tokens.EOF do
      if scanner.token == Tokens.INTLIT && bodies.exists(_.contains(Span(scanner.offset))) then
        val end = scanner.offset + scanner.strVal.length
        if content.substring(scanner.offset, end).forall(_.isDigit) && end - scanner.offset < 6 then lits += ((scanner.offset, end))
      scanner.nextToken()
    if lits.isEmpty then None
    else
      val (start, end) = lits(rng.nextInt(lits.size))
      val lit = content.substring(start, end)
      Some(content.substring(0, start) + (if lit == "0" then "10" else lit + "1") + content.substring(end))

  /** `compiler/src/dotty/tools/dotc`, from the working directory upwards. */
  private val sourcesRoot: Option[Path] =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .map(_.resolve("compiler/src/dotty/tools/dotc")).find(Files.isDirectory(_))

  /** The end offsets of the identifiers of `source`, from the scanner. */
  private def identifierEnds(source: SourceFile)(using Context): List[(Int, String)] =
    val scanner = new Scanners.Scanner(source)
    val out = mutable.ListBuffer.empty[(Int, String)]
    while scanner.token != Tokens.EOF do
      if scanner.isIdent then
        val name = scanner.name.toString
        out += ((scanner.offset + name.length, name))
      scanner.nextToken()
    out.toList

  /** The innermost typed tree at `offset` of the unit of `uri`, described. */
  private def describe(driver: InteractiveDriver, uri: URI, offset: Int): String =
    driver.compilationUnits.get(uri) match
      case None => "<no unit>"
      case Some(unit) => describe(driver.currentCtx, unit, offset)

  /** The innermost typed tree at `offset` of `unit`, under `ctx0`: the read action of a hover. */
  private def describe(ctx0: Context, unit: dotty.tools.dotc.CompilationUnit, offset: Int): String =
    given Context = ctx0.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using ctx0))
    Interactive.pathTo(unit.tpdTree, Span(offset)).headOption match
      case None => "<no tree>"
      case Some(t) =>
        val sym = t.symbol
        val tpe = if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>"
        s"${t.getClass.getSimpleName}[${t.span.start},${t.span.end}) : $tpe${if sym.exists then s" @ ${sym.showFullName}" else ""}"

  private def inTyperCheckpoint(): Boolean =
    Thread.currentThread.getStackTrace.exists(_.getMethodName == "checkCancelledTyping")

  /** `requests` hovers at random identifiers of `rel`, in one session; after
   *  each, `recheck` earlier offsets again. Every answer is the oracle's.
   */
  /** With `editEvery`, the driver keeps its run across edits inside bodies
   *  (`-Yinteractive-body-edits`), and every `editEvery` requests an integer
   *  literal in a member's body gets one more digit; the oracle is then a
   *  fresh whole-file run of the new content.
   */
  private def oracle(rel: String, seed: Int, requests: Int, recheck: Int = 3, cancelEvery: Int = 7, editEvery: Int = 0): Unit =
    val root = sourcesRoot.getOrElse(throw new AssertionError(s"compiler sources not found from ${Paths.get("").toAbsolutePath}"))
    val path = root.resolve(rel)
    var content = Files.readString(path)
    val uri = path.toUri
    var source = SourceFile.virtual(uri, content)
    val t0 = System.nanoTime()
    var whole = newDriver()
    whole.run(uri, source)
    var idents = identifierEnds(source)(using whole.currentCtx)
    var expected = idents.map((o, _) => o -> describe(whole, uri, o)).toMap
    var tWhole = (System.nanoTime() - t0) / 1e6
    val edits = mutable.Map.empty[String, Int].withDefaultValue(0)
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    val driver = newDriver(bodyEdits = editEvery > 0)
    val rng = new Random(seed)
    val log = mutable.ListBuffer.empty[String]
    val asked = mutable.ArrayBuffer.empty[Int]
    var fresh = 0; var runId = -1; var cancelled = 0
    def line(o: Int) = content.substring(content.lastIndexOf('\n', o - 1) + 1, content.indexOf('\n', o) max o).trim.take(100)
    def check(step: Int, what: String, o: Int, name: String): Unit =
      driver.runUpTo(uri, source, Span(o))
      val id = driver.runIdOf(uri)
      val how = if id != runId then { fresh += 1; runId = id; "fresh" } else "same run"
      if what == "edit+hover" then edits(driver.lastEdit(uri).replaceAll(", shifted.*", "").replaceAll(" at \\[.*", "")) += 1
      val obtained = describe(driver, uri, o)
      val still = driver.untypedSpans(uri).filter(_.contains(Span(o)))
      log += s"$step $what `$name`@$o $how run=$id${if what == "edit+hover" then s" [${driver.lastEdit(uri)}]" else ""} skipped=${driver.untypedSpans(uri).size}${if still.nonEmpty then s" STILL SKIPPED ${still.mkString(",")}" else ""}"
      if expected(o) != obtained then
        val skipped = driver.untypedSpans(uri).map(sp => content.substring(sp.start, math.min(sp.end, sp.start + 24)).replace('\n', ' ')).mkString("[", " | ", "]")
        org.junit.Assert.fail(s"""[oracle $rel seed=$seed step $step] hover `$name` at $o ($what, $how) differs from the whole-file run
          |  line:     ${line(o)}
          |  expected: ${expected(o)}
          |  obtained: $obtained
          |  skipped now: $skipped
          |  last requests:
          |${log.takeRight(8).map("    " + _).mkString("\n")}""".stripMargin)
    try
      for step <- 1 to requests do
        if editEvery > 0 && step % editEvery == 0 then
          bodyEdit(whole, uri, content, rng).foreach { now =>
            val t1 = System.nanoTime()
            content = now
            source = SourceFile.virtual(uri, content)
            whole = newDriver()
            whole.run(uri, source)
            idents = identifierEnds(source)(using whole.currentCtx)
            expected = idents.map((o, _) => o -> describe(whole, uri, o)).toMap
            tWhole += (System.nanoTime() - t1) / 1e6
            asked.clear()
            val (o, name) = idents(rng.nextInt(idents.size))
            check(step, "edit+hover", o, name)
          }
        val (o, name) = idents(rng.nextInt(idents.size))
        if step % 40 == 0 then
          // what document highlight does between hovers: a whole-file run,
          // which completes the session; the answers after it must still match
          driver.runUpTo(uri, source, Span(o)); driver.run(uri, source)
          log += s"$step run (whole file) run=${driver.runIdOf(uri)} fully=${driver.isFullyTyped(uri)}"
          // the completed tree must hold every statement: identifiers never asked before
          for _ <- 1 to 5 do
            val (p, pn) = idents(rng.nextInt(idents.size))
            check(step, "after-run", p, pn)
        if step % cancelEvery == 0 then
          val k = 1 + rng.nextInt(3)
          var p = 0
          val thrown =
            try
              driver.withCancelChecker(() => { if inTyperCheckpoint() then p += 1; p >= k }) { driver.runUpTo(uri, source, Span(o)) }
              false
            catch case _: CancelledCompilation => true
          if thrown then cancelled += 1
          log += s"$step cancel(k=$k) `$name`@$o thrown=$thrown polls=$p run=${driver.runIdOf(uri)}"
        check(step, "hover", o, name)
        asked += o
        for _ <- 1 to recheck if asked.size > 1 do
          val p = asked(rng.nextInt(asked.size))
          check(step, "recheck", p, idents.find(_._1 == p).map(_._2).getOrElse("?"))
      println(f"[oracle $rel seed=$seed] ${idents.size} identifiers, $requests requests (+$recheck rechecks each), $fresh fresh runs, $cancelled cancellations landed; whole-file oracle $tWhole%.0f ms, session ${(System.nanoTime() - t0) / 1e6 - tWhole}%.0f ms${if editEvery > 0 then edits.toList.sorted.map((k, n) => s"$n× $k").mkString("; edits: ", ", ", "") else ""}")
      if editEvery > 0 then
        org.junit.Assert.assertTrue(s"[oracle $rel seed=$seed] some body edits keep the run: $edits", edits.keys.exists(_.startsWith("body edit")))
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  /** Two readers (step 3 of the multi-reader notes): the hovers of `oracle`
   *  issued from two threads at once on one driver, each hover a read action
   *  (`readUpTo`) whose description is computed under the read lock, every
   *  answer the whole-file oracle's. The whole-file runs and the cancellations
   *  of the single-reader walk are kept, from either thread.
   */
  private def oracle2(rel: String, seed: Int, requests: Int, cancelEvery: Int = 7, independent: Boolean = true): Unit =
    val root = sourcesRoot.getOrElse(throw new AssertionError(s"compiler sources not found from ${Paths.get("").toAbsolutePath}"))
    val path = root.resolve(rel)
    val content = Files.readString(path)
    val uri = path.toUri
    val source = SourceFile.virtual(uri, content)
    val t0 = System.nanoTime()
    val whole = newDriver()
    whole.run(uri, source)
    val idents = identifierEnds(source)(using whole.currentCtx)
    val expected = idents.map((o, _) => o -> describe(whole, uri, o)).toMap
    val tWhole = (System.nanoTime() - t0) / 1e6
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    // `independent`: `-Yinteractive-readers` (step 4); without, serialised (step 3)
    val driver = new InteractiveDriver(if independent then settings :+ "-Yinteractive-readers" else settings, CachedLogicalPackage.none)
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
    val counts = new java.util.concurrent.ConcurrentHashMap[String, Int]()
    def count(k: String) = counts.merge(k, 1, _ + _)
    def line(o: Int) = content.substring(content.lastIndexOf('\n', o - 1) + 1, content.indexOf('\n', o) max o).trim.take(100)
    def walk(thread: Int): Unit =
      val rng = new Random(seed * 31 + thread)
      val log = mutable.ListBuffer.empty[String]
      def check(step: Int, what: String, o: Int, name: String): Unit =
        val obtained = driver.readUpTo(uri, source, Span(o))((ctx, unit, _) => describe(ctx, unit, o))
        log += s"$step $what `$name`@$o run=${driver.runIdOf(uri)} skipped=${driver.untypedSpans(uri).size}"
        count(what)
        if expected(o) != obtained then
          org.junit.Assert.fail(s"""[oracle2 $rel seed=$seed reader $thread step $step] hover `$name` at $o ($what) differs from the whole-file run
            |  line:     ${line(o)}
            |  expected: ${expected(o)}
            |  obtained: $obtained
            |  last requests:
            |${log.takeRight(8).map("    " + _).mkString("\n")}""".stripMargin)
      for step <- 1 to requests if failure.get == null do
        val (o, name) = idents(rng.nextInt(idents.size))
        if step % 40 == 0 then
          driver.readAll(uri, source)((_, _, _) => ())
          count("whole-file")
          for _ <- 1 to 3 do
            val (p, pn) = idents(rng.nextInt(idents.size))
            check(step, "after-run", p, pn)
        if step % cancelEvery == 0 then
          val k = 1 + rng.nextInt(3)
          var p = 0
          val thrown =
            try
              driver.withCancelChecker(() => { if inTyperCheckpoint() then p += 1; p >= k }) { driver.runUpTo(uri, source, Span(o)) }
              false
            catch case _: CancelledCompilation => true
          if thrown then count("cancelled")
        check(step, "hover", o, name)
    try
      val threads = (0 until 2).map { i =>
        val t = new Thread(() =>
          try walk(i)
          catch
          case ex: AssertionError => failure.compareAndSet(null, ex): Unit
          case ex: CancelledCompilation => failure.compareAndSet(null, new AssertionError(s"reader $i: a cancellation reached the caller of a request without a checker", ex)): Unit
          case scala.util.control.NonFatal(ex) => failure.compareAndSet(null, ex): Unit, s"oracle-reader-$i")
        // an `Error` (a stack overflow) must fail the test too, not just end the thread
        t.setUncaughtExceptionHandler((_, ex) => failure.compareAndSet(null, ex): Unit)
        t.start()
        t
      }
      threads.foreach(_.join())
      if failure.get != null then throw failure.get
      println(f"[oracle2 $rel seed=$seed] ${idents.size} identifiers, 2 readers x $requests requests: ${counts.asScala.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString(", ")}; whole-file oracle $tWhole%.0f ms, session ${(System.nanoTime() - t0) / 1e6 - tWhole}%.0f ms")
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  /** The gate: with `-Yno-targeted-requests` a `runUpTo` types the whole
   *  file, as `run` does, and leaves no skipped statement.
   */
  @Test def `the-knob-turns-targeting-off` =
    val rel = "core/classfile/ByteCodecs.scala"
    val root = sourcesRoot.getOrElse(throw new AssertionError("compiler sources not found"))
    val path = root.resolve(rel); val content = Files.readString(path); val uri = path.toUri
    val source = SourceFile.virtual(uri, content)
    val target = Span(content.indexOf("val dstlen") + 4)
    val on = newDriver(); on.runUpTo(uri, source, target)
    val off = new InteractiveDriver(settings :+ "-Yno-targeted-requests", CachedLogicalPackage.none); off.runUpTo(uri, source, target)
    println(s"[knob] targeted: ${on.targetedRequests}, skipped ${on.untypedSpans(uri).size}; -Yno-targeted-requests: ${off.targetedRequests}, skipped ${off.untypedSpans(uri).size}, fully typed ${off.isFullyTyped(uri)}")
    org.junit.Assert.assertTrue("targeting is on by default and skips something", on.targetedRequests && on.untypedSpans(uri).nonEmpty)
    org.junit.Assert.assertTrue("-Yno-targeted-requests types the whole file", !off.targetedRequests && off.untypedSpans(uri).isEmpty && off.compilationUnits.contains(uri))
    org.junit.Assert.assertEquals(describe(on, uri, target.start), describe(off, uri, target.start))

  @Test def `ByteCodecs-seed-1` = oracle("core/classfile/ByteCodecs.scala", 1, 300)
  @Test def `ByteCodecs-seed-2` = oracle("core/classfile/ByteCodecs.scala", 2, 300)
  @Test def `SourceTree-seed-1` = oracle("interactive/SourceTree.scala", 1, 300)
  @Test def `SourceTree-seed-2` = oracle("interactive/SourceTree.scala", 2, 300)
  @Test def `Chars-seed-1` = oracle("util/Chars.scala", 1, 300)
  /** inline methods with local definitions, givens, a large class: what VS Code
   *  hovered on. Finding I29: at step 169, after a cancellation at step 168
   *  landed in a resume (k=3) and the statement was replaced, a recheck of
   *  `commit(result)(using nestedCtx)` answers `<error unspecified error>`
   *  where the whole-file run has `Boolean`. The sequence of landings is not
   *  reproducible across JVMs (the checkpoint counts typed trees, and the
   *  classpath loading order shifts the count); the family of the failure
   *  (a sibling's Namer completer unwound by the cancellation and retried)
   *  is `CompletionSessionSuite.cancel-inside-a-sibling-completer`.
   */
  @Test def `Contexts-seed-1` = oracle("core/Contexts.scala", 1, 300)
  @Test def `Contexts-seed-2` = oracle("core/Contexts.scala", 2, 160)
  @Test def `SimpleIdentityMap-seed-1` = oracle("util/SimpleIdentityMap.scala", 1, 300)

  @Test def `two-readers-ByteCodecs-seed-1` = oracle2("core/classfile/ByteCodecs.scala", 1, 300)
  @Test def `two-readers-SourceTree-seed-1` = oracle2("interactive/SourceTree.scala", 1, 300)
  @Test def `two-readers-Chars-seed-1` = oracle2("util/Chars.scala", 1, 300)
  @Test def `two-readers-Contexts-seed-1` = oracle2("core/Contexts.scala", 1, 200)
  @Test def `two-readers-serialised-Chars-seed-1` = oracle2("util/Chars.scala", 1, 300, independent = false)
  // edits inside bodies keep the run (docs §12)
  /** Every identifier of `rel` hovered on a driver with `flags`, after a
   *  first request, and again after an edit inside the body of `editIn` (a
   *  space added), against a whole-file run without the flags; and the
   *  whole-file diagnostics of the session against the whole-file run's.
   *  Typed with the build's options (`-Yexplicit-nulls` and the rest), as
   *  the build compiles the file.
   */
  private def everyIdentifier(rel: String, editIn: String, flags: List[String]): Unit =
    val root = sourcesRoot.getOrElse(throw new AssertionError(s"compiler sources not found from ${Paths.get("").toAbsolutePath}"))
    val real = dotty.tools.pc.xray.XrayServer.buildOptions ++ settings
    val path = root.resolve(rel)
    val uri = path.toUri
    val before = Files.readString(path)
    val at = before.indexOf(editIn)
    assert(at >= 0, s"$editIn not in $rel")
    // a space after the first `=` inside the body (the layout is unchanged)
    val eq = before.indexOf(" = ", before.indexOf('\n', at)) + 3
    val after = before.substring(0, eq) + " " + before.substring(eq)
    val driver = new InteractiveDriver(real ++ flags, CachedLogicalPackage.none)
    def errorsOf(ds: List[dotty.tools.dotc.reporting.Diagnostic]) =
      ds.filter(_.level >= 2).map(d => s"${d.pos.line + 1}: ${d.msg.message.linesIterator.next()}").sorted
    for (content, when) <- List(before -> "before the edit", after -> s"after an edit in $editIn") do
      val source = SourceFile.virtual(uri, content)
      val whole = new InteractiveDriver(real, CachedLogicalPackage.none)
      val wholeErrors = errorsOf(whole.run(uri, source))
      val idents = identifierEnds(source)(using whole.currentCtx)
      var differ = List.empty[String]
      var edit = ""
      for (o, name) <- idents do
        driver.runUpTo(uri, source, Span(o))
        if edit.isEmpty then edit = driver.lastEdit(uri)
        val obtained = describe(driver, uri, o)
        val expected = describe(whole, uri, o)
        if obtained != expected then differ ::= s"`$name` at $o: $obtained, whole-file $expected"
      val sessionErrors = errorsOf(driver.readAll(uri, source)((_, _, ds) => ds))
      if content eq after then assert(wholeErrors.isEmpty, s"the edit broke the file: $wholeErrors")
      println(s"[every identifier $rel ${flags.mkString(" ")}] $when: ${idents.size} identifiers, ${differ.size} differ; ${sessionErrors.size} errors, whole-file ${wholeErrors.size}${if edit.nonEmpty then s" [$edit]" else ""}")
      org.junit.Assert.assertEquals(s"$when: hovers that differ from the whole-file run", Nil, differ.reverse.take(5))
      org.junit.Assert.assertEquals(s"$when: errors", wholeErrors.mkString("\n"), sessionErrors.mkString("\n"))

  // I62: `val retractedVars` in `whileContext`, an inferred type completed in a
  // typer state of its own (-Yinteractive-readers), published `Set[A | TermRef]`
  @Test def `Nullables-every-identifier-readers` =
    everyIdentifier("typer/Nullables.scala", "def whileContext", List("-Yinteractive-readers"))
  @Test def `Nullables-every-identifier-readers-body-edits` =
    everyIdentifier("typer/Nullables.scala", "def whileContext", List("-Yinteractive-readers", "-Yinteractive-body-edits"))
  @Test def `Nullables-every-identifier-body-edits` =
    everyIdentifier("typer/Nullables.scala", "def whileContext", List("-Yinteractive-body-edits"))

  @Test def `ByteCodecs-body-edits` = oracle("core/classfile/ByteCodecs.scala", 1, 300, editEvery = 10)
  @Test def `Chars-body-edits` = oracle("util/Chars.scala", 1, 300, editEvery = 10)
  @Test def `Contexts-body-edits` = oracle("core/Contexts.scala", 1, 200, editEvery = 10)
  @Test def `SimpleIdentityMap-body-edits` = oracle("util/SimpleIdentityMap.scala", 1, 300, editEvery = 10)
