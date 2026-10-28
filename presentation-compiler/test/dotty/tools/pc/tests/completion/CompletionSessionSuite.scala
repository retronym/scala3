package dotty.tools.pc.tests.completion

import java.net.URI

import scala.concurrent.duration.*
import scala.language.unsafeNulls
import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.metals.{CompilerOffsetParams, EmptyCancelToken, PcQueryContext}
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.CancelToken
import scala.meta.pc.reports.EmptyReportContext

import dotty.tools.dotc.CompilationUnit.CancelledCompilation
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.{CachingDriver, ScalaPresentationCompiler}
import dotty.tools.pc.base.BaseCompletionSuite

import org.junit.Test

/** Successive completions on the same content elaborate the typed tree
 *  incrementally within one run (`InteractiveDriver.runUpTo`): a request
 *  types, of the top-level statements and of the members of each template
 *  on the path to the offset, only those containing it; a later request
 *  within what is typed types nothing, and one in a skipped statement types
 *  that statement in the same run.
 */
class CompletionSessionSuite extends BaseCompletionSuite:

  private val timeout = 60.seconds

  override protected def config: PresentationCompilerConfigImpl =
    super.config.copy(timeoutDelay = 120)

  private val uri = URI.create("file:///A.scala")

  /** 400 top-level objects, each with a method and an incomplete reference
   *  to it; `at` is the index of the object with the completion marker, the
   *  content (without the marker) is the same for every `at`. `tag` makes
   *  each test's content distinct. With `inMethod`, the marker is in the
   *  method's body instead (`xs.map(_ + i).fil@@ter`). The objects in
   *  `heavy` get a method `giant` of 200 local vals each depending on the one before, well over
   *  the 1024 typed trees between two cancellation checks; with `inGiant`
   *  the marker is in its body.
   */
  private def bigSource(tag: String, at: Int, inMethod: Boolean = false, inGiant: Boolean = false, heavy: Set[Int] = Set.empty) =
    val objects = (0 until 400).map { i =>
      val marked = i == at
      val completion = if marked && !inMethod && !inGiant then s"this.meth@@" else s"this.meth"
      val body = if marked && inMethod then s"xs.map(_ + $i).fil@@ter(_ > $i).sum" else s"xs.map(_ + $i).filter(_ > $i).sum"
      val extra =
        if heavy(i) then
          // each statement depends on the one before, so that typing the last one
          // (where the marker is) types them all through their completers, even
          // though only the statement at the target is typed directly
          val stats = (0 until 200).map { j =>
            val prev = if j == 0 then "0" else s"a${j - 1}"
            if marked && inGiant && j == 199 then s"    val a$j = xs.map(_ + $j + $prev).fil@@ter(_ > $j).sum" else s"    val a$j = xs.map(_ + $j + $prev).filter(_ > $j).sum"
          }.mkString("\n")
          s"\n  def giant(xs: List[Int]): Int = {\n$stats\n    a0 + a199\n  }"
        else ""
      s"""|object M$i {
          |  def method$i(xs: List[Int]): Int = $body$extra
          |  val r = $completion
          |}""".stripMargin
    }.mkString("\n")
    s"""|// $tag
        |$objects
        |""".stripMargin

  private def expectedAt(at: Int) = s"method$at(xs: List[Int]): Int"

  private def pc = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]
  private given PcQueryContext = PcQueryContext(None, () => "")(using EmptyReportContext())

  private def withDriver[T](f: CachingDriver => T): T =
    pc.compilerAccess.withNonInterruptableCompiler(null.asInstanceOf[T], EmptyCancelToken) { access =>
      f(access.compiler())
    }.get(timeout.length, timeout.unit)

  /** (run id, typed top-level statements, skipped statements at any level) */
  private def state(): (Int, Int, Int) = withDriver(d => (d.runIdOf(uri), d.typedTopLevelCount(uri), d.untypedSpans(uri).size))

  private def complete(query: String, token: CancelToken = EmptyCancelToken): String =
    val (code, offset) = params(query)
    presentationCompiler
      .complete(CompilerOffsetParams(uri, code, offset, token))
      .get(timeout.length, timeout.unit)
      .getItems.asScala.map(_.getLabel).sorted.mkString("\n")

  private def timed[T](body: => T): (T, Long) =
    val start = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - start) / 1000000)

  private def inTyperCheckpoint(): Boolean =
    Thread.currentThread.getStackTrace.exists(_.getMethodName == "checkCancelledTyping")

  /** Runs `body` on the driver with a checker that cancels at the `n`th typer checkpoint. */
  private def cancelAtCheckpoint(n: Int)(body: CachingDriver => Unit): (Boolean, Int) =
    withDriver { driver =>
      var polls = 0
      val thrown =
        try
          driver.withCancelChecker(() => { if inTyperCheckpoint() then polls += 1; polls >= n }) {
            body(driver)
          }
          false
        catch case _: CancelledCompilation => true
      (thrown, polls)
    }

  @Test def `targeted-prefix-and-timing` =
    // Warm up the compiler on other content
    assertNoDiff(expectedAt(10), complete(bigSource("warmup", 10)))
    val (obtained, targetedMs) = timed(complete(bigSource("timing", 10)))
    assertNoDiff(expectedAt(10), obtained)
    val (runId, typed, skipped) = state()
    println(s"[targeted-prefix-and-timing] runId=$runId typedTopLevelCount=$typed skipped=$skipped (an object counts as its module val and class; skipped: the other 399 objects and method10)")
    assertEquals(2, typed, "only the object containing the offset is typed")
    assertEquals(400, skipped, "the other objects and the sibling method are skipped")
    // The same content typed in full
    val (code, _) = params(bigSource("timing", 10))
    val (_, fullMs) = timed(withDriver(_.run(uri, SourceFile.virtual(uri, code))))
    val (code2, _) = params(bigSource("timing-2", 10))
    val (_, fullMs2) = timed(withDriver(_.run(uri, SourceFile.virtual(uri, code2))))
    println(s"[targeted-prefix-and-timing] targeted completion at object 10: ${targetedMs}ms; full run of the same content: ${fullMs}ms; full run of other content: ${fullMs2}ms")
    // The driver alone, without the completion machinery
    val (code3, offset3) = params(bigSource("timing-3", 10))
    val source3 = SourceFile.virtual(uri, code3)
    val (_, targetedMs3) = timed(withDriver(_.runUpTo(uri, source3, Span(offset3))))
    val (_, withinMs3) = timed(withDriver(_.runUpTo(uri, source3, Span(offset3 - 2))))
    val (_, resumeMs3) = timed(withDriver(_.runUpTo(uri, source3, Span(code3.indexOf("object M200")))))
    val (_, typed3, _) = state()
    val (code4, _) = params(bigSource("timing-4", 10))
    val (_, fullMs4) = timed(withDriver(_.run(uri, SourceFile.virtual(uri, code4))))
    println(s"[targeted-prefix-and-timing] driver only: runUpTo(object 10)=${targetedMs3}ms, runUpTo within the typed statement=${withinMs3}ms, resume at object 200=${resumeMs3}ms (typedTopLevelCount=$typed3); full run=${fullMs4}ms")
    val (tiny, tinyMs) = timed(complete("object T { val x = this.toStr@@ }"))
    println(s"[targeted-prefix-and-timing] completion request on a one-object file: ${tinyMs}ms ($tiny)")

  @Test def `resume-in-the-same-run` =
    assertNoDiff(expectedAt(10), complete(bigSource("resume", 10)))
    val (runId1, typed1, skipped1) = state()
    println(s"[resume-in-the-same-run] after object 10:  runId=$runId1 typedTopLevelCount=$typed1 skipped=$skipped1")
    // Same content (the `@@` marker is not part of it), offset in a skipped object
    val (obtained2, ms2) = timed(complete(bigSource("resume", 200)))
    assertNoDiff(expectedAt(200), obtained2)
    val (runId2, typed2, skipped2) = state()
    println(s"[resume-in-the-same-run] after object 200: runId=$runId2 typedTopLevelCount=$typed2 skipped=$skipped2 (${ms2}ms)")
    // Within a typed statement: nothing is typed
    val (obtained3, ms3) = timed(complete(bigSource("resume", 10)))
    assertNoDiff(expectedAt(10), obtained3)
    val (runId3, typed3, skipped3) = state()
    println(s"[resume-in-the-same-run] after object 10 again: runId=$runId3 typedTopLevelCount=$typed3 skipped=$skipped3 (${ms3}ms)")
    // An earlier skipped object: typed in the same run, the typed set is not a prefix
    val (obtained4, ms4) = timed(complete(bigSource("resume", 5)))
    assertNoDiff(expectedAt(5), obtained4)
    val (runId4, typed4, skipped4) = state()
    println(s"[resume-in-the-same-run] after object 5:   runId=$runId4 typedTopLevelCount=$typed4 skipped=$skipped4 (${ms4}ms)")
    assertEquals(runId1, runId2, "the second request must resume the first run")
    assertEquals(runId2, runId3, "the third request must be served by the same run")
    assertEquals(runId3, runId4, "the fourth request must resume the same run")
    assertEquals(typed1 + 2, typed2, "object 200 is typed in addition to object 10")
    assertEquals(typed2, typed3, "a request within a typed statement must not type anything")
    assertEquals(skipped2, skipped3, "a request within a typed statement must not type anything")
    assertEquals(typed3 + 2, typed4, "object 5 is typed in addition")

  @Test def `members-are-skipped-too` =
    assertNoDiff(expectedAt(7), complete(bigSource("members", 7)))
    val (runId1, typed1, skipped1) = state()
    val untyped1 = withDriver(_.untypedSpans(uri))
    val (code, _) = params(bigSource("members", 7))
    val method7 = code.indexOf("def method7(")
    println(s"[members-are-skipped-too] after `val r` of object 7: runId=$runId1 typedTopLevelCount=$typed1 skipped=$skipped1; method7 skipped=${untyped1.exists(_.contains(Span(method7)))}")
    assert(untyped1.exists(_.contains(Span(method7))), "the sibling method of the completion is skipped")
    // Inside the skipped method: typed in the same run, in its template's context
    val obtained2 = complete(bigSource("members", 7, inMethod = true))
    val (runId2, typed2, skipped2) = state()
    println(s"[members-are-skipped-too] inside method7: runId=$runId2 typedTopLevelCount=$typed2 skipped=$skipped2 (${obtained2.linesIterator.size} items)")
    assert(obtained2.linesIterator.exists(_.startsWith("filter")), s"expected filter among the completions, got: $obtained2")
    assertEquals(runId1, runId2, "the request inside the skipped member must resume the run")
    assertEquals(typed1, typed2, "no further top-level statement is typed")
    assertEquals(skipped1 - 1, skipped2, "the member is no longer skipped")

  @Test def `another-file-sees-the-signatures` =
    // A targeted run on A types only `n`; B then refers to `m`, whose completer
    // belongs to A's run: A's symbols are completed before A is left.
    val uriA = URI.create("file:///SessA.scala")
    val uriB = URI.create("file:///SessB.scala")
    // SessA is typed (along `n`), SkippedB and SkippedC are skipped whole: leaving the
    // file walks both kinds (a skipped object's expansion is a thicket that contains
    // the object's own class: the walk must visit each tree once)
    val codeA =
      """|object SkippedB { def p(y: Int): Int = y; val q = p(1) }
         |object SessA {
         |  def m(x: Int): Int = x
         |  def n = 1
         |}
         |case class SkippedC(z: Int) { def w = z }
         |""".stripMargin
    val codeB = "object SessB { val r = SessA.m; val s = SkippedB.p; val t = SkippedC(1).w }\n"
    val (obtained, diags) = withDriver { d =>
      d.runUpTo(uriA, SourceFile.virtual(uriA, codeA), Span(codeA.indexOf("def n") + 5))
      val diags = d.runUpTo(uriB, SourceFile.virtual(uriB, codeB), Span(codeB.indexOf("SessA.m") + 7))
      val unit = d.compilationUnits(uriB)
      given dotty.tools.dotc.core.Contexts.Context = d.currentCtx.fresh.setCompilationUnit(unit)
      val path = dotty.tools.dotc.interactive.Interactive.pathTo(unit.tpdTree, Span(codeB.indexOf("SessA.m") + 6))
      (path.headOption.map(t => t.tpe.widenTermRefExpr.show).getOrElse("<none>"), diags.map(_.msg.message))
    }
    println(s"[another-file-sees-the-signatures] SessA.m in SessB: $obtained; diagnostics: $diags")
    org.junit.Assert.assertEquals("(x: Int): Int", obtained)

  @Test def `a-replaced-object-still-finds-givens` =
    // The soak's sequence (shapes, seed 2, step 265): a cancellation lands while
    // resuming `object Infer` at `def c`, Infer is re-parsed and re-indexed, the
    // next request types the fresh Infer, and a completion in its `val d`
    // must still find the given `Show[Int]` of the package.
    val code =
      """|object Wrapper:
         |  case class Plain(y: String)
         |trait Show[T]:
         |  def show(t: T): String
         |given Show[Int] with
         |  def show(t: Int) = t.toString
         |object Infer:
         |  val a = Wrapper.Plain("p")
         |  val b = a.y
         |  def c =
         |    val xs = (1 to 40).toList
         |    xs.map(_ + 1).filter(_ > 2).map(_ * 2).filter(_ < 100).sum + b.length
         |  val d = summon[Show[Int]].sh
         |""".stripMargin
    val uri = URI.create("file:///Replaced.scala")
    val src = SourceFile.virtual(uri, code)
    val atC = code.indexOf("xs.map(_ + 1)") + 3
    val atD = code.indexOf(".sh\n") + 3
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    try
      val (thrown, polls, labels1, labels2, oracle) = withDriver { d =>
        // Infer skipped: the first request is in Wrapper
        d.runUpTo(uri, src, Span(code.indexOf("case class Plain") + 12))
        var polls = 0
        val thrown =
          try { d.withCancelChecker(() => { if inTyperCheckpoint() then polls += 1; polls >= 2 }) { d.runUpTo(uri, src, Span(atC)) }; false }
          catch case _: CancelledCompilation => true
        def complete(at: Int): List[String] =
          d.runUpTo(uri, src, Span(at))
          val unit = d.compilationUnits(uri)
          given dotty.tools.dotc.core.Contexts.Context = d.currentCtx.fresh.setCompilationUnit(unit).withPhase(dotty.tools.dotc.core.Phases.typerPhase(using d.currentCtx))
          dotty.tools.dotc.interactive.Completion.completions(dotty.tools.dotc.util.SourcePosition(src, Span(at)))._2.map(_.label).sorted
        val labels1 = complete(atC)
        val labels2 = complete(atD)
        val fresh = new dotty.tools.dotc.interactive.InteractiveDriver(dotty.tools.pc.utils.TestingWorkspaceSearch(dotty.tools.pc.base.TestResources.classpath.map(_.toString)).settings, dotty.tools.dotc.interactive.CachedLogicalPackage.none)
        fresh.run(uri, src)
        val unit = fresh.compilationUnits(uri)
        given dotty.tools.dotc.core.Contexts.Context = fresh.currentCtx.fresh.setCompilationUnit(unit).withPhase(dotty.tools.dotc.core.Phases.typerPhase(using fresh.currentCtx))
        val oracle = dotty.tools.dotc.interactive.Completion.completions(dotty.tools.dotc.util.SourcePosition(src, Span(atD)))._2.map(_.label).sorted
        (thrown, polls, labels1, labels2, oracle)
      }
      println(s"[a-replaced-object-still-finds-givens] thrown=$thrown polls=$polls; at def c: $labels1; at val d: $labels2; whole-file: $oracle")
      assert(thrown, "the cancellation must land inside def c")
      org.junit.Assert.assertEquals(oracle.mkString("\n"), labels2.mkString("\n"))
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  @Test def `top-level-definitions-are-skipped-too` =
    // A file's top-level definitions are wrapped in a synthetic `<file>$package`
    // object, which is skipped like any other statement (synthetic definitions
    // are kept only inside blocks, where the next statement is built from
    // them), and a target in one of its members resumes into it. A case class
    // is one skipped statement with the synthetic companion its expansion
    // makes, typed together when a target lands in the class.
    val code =
      """|package p
         |def one(xs: List[Int]): Int = xs.map(_ + 1).sum
         |def two(xs: List[Int]): Int = xs.map(_ + 2).filter(_ > 0).sum
         |case class Pair(a: Int, b: Int)
         |object Other { def o = one(Nil) }
         |""".stripMargin
    val uri = URI.create("file:///Top.scala")
    val src = SourceFile.virtual(uri, code)
    def spans(d: CachingDriver) = d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 12)).trim).sorted
    val (runIds, fresh, afterTwo, afterOne, afterPair) = withDriver { d =>
      val ds1 = d.runUpTo(uri, src, Span(code.indexOf("def o =") + 5))
      val fresh = spans(d)
      val ds2 = d.runUpTo(uri, src, Span(code.indexOf(".filter(_ > 0)") + 4))
      val afterTwo = spans(d)
      val ds3 = d.runUpTo(uri, src, Span(code.indexOf("xs.map(_ + 1)") + 3))
      val afterOne = spans(d)
      val ds4 = d.runUpTo(uri, src, Span(code.indexOf("b: Int") + 1))
      val afterPair = spans(d)
      assert(List(ds1, ds2, ds3, ds4).forall(_.forall(_.level < 2)), s"no errors: ${List(ds1, ds2, ds3, ds4).flatten.map(_.msg.message)}")
      (List.fill(4)(d.runIdOf(uri)), fresh, afterTwo, afterOne, afterPair)
    }
    println(s"[top-level-definitions-are-skipped-too] fresh: $fresh; after two: $afterTwo; after one: $afterOne; after Pair: $afterPair")
    // the package object (its span begins at `one`) and the case class are skipped; `Other` is typed
    assertEquals(List("case class P", "def one(xs:"), fresh, "the package object and the case class are skipped")
    // the request in `two` resumed into the package object, where `one` is skipped
    assertEquals(List("case class P", "def one(xs:"), afterTwo, "resumed into the package object, where `one` stays skipped")
    assertEquals(List("case class P"), afterOne, "`one` resumed")
    // the case class resumed (its parameter accessor `a`, a member not touched, is skipped as members are)
    assert(!afterPair.exists(_.startsWith("case class")), s"the case class resumed: $afterPair")

  @Test def `block-statements-are-skipped-too` =
    // In a method body only the statement at the target is typed; a statement
    // before it is typed ahead only if the target's statement refers to it.
    val code =
      """|object Blk {
         |  def big(xs: List[Int]): Int = {
         |    val a = xs.map(_ + 1).sum
         |    val b = xs.map(_ + 2).sum
         |    val c = xs.map(_ + 3).filter(_ > 0).sum + a
         |    val d = xs.map(_ + 4).sum
         |    c + d
         |  }
         |}
         |object Other { def o = 1 }
         |""".stripMargin
    val uri = URI.create("file:///Blk.scala")
    val src = SourceFile.virtual(uri, code)
    val atC = code.indexOf(".filter(_ > 0)") + 4
    val (fresh, resumed) = withDriver { d =>
      // a fresh run with the target in c
      d.runUpTo(uri, src, Span(atC))
      val fresh = d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 12)).trim)
      // the same through a resume: first a request in Other, then one in c
      val src2 = SourceFile.virtual(uri, code + "\n// v2")
      d.runUpTo(uri, src2, Span(code.indexOf("def o") + 5))
      d.runUpTo(uri, src2, Span(atC))
      val resumed = d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 12)).trim)
      (fresh, resumed)
    }
    println(s"[block-statements-are-skipped-too] fresh: $fresh; resumed: $resumed")
    assert(fresh.exists(_.startsWith("val b")) && fresh.exists(_.startsWith("val d")), s"b and d are skipped in a fresh run: $fresh")
    assert(resumed.exists(_.startsWith("val b")) && resumed.exists(_.startsWith("val d")), s"b and d are skipped in a resume: $resumed")

  @Test def `a-target-in-a-skipped-block-statement-is-resumed` =
    // A request in a block statement that an earlier request skipped (a stub
    // in the typed `Block`) resumes the run: the statement is typed in the
    // block's recorded context and replaces its stub in the typed tree.
    val code =
      """|object Blk2 {
         |  def enc(src: Array[Byte]): Array[Byte] = {
         |    val n = src.length
         |    val dst = new Array[Byte](n)
         |    var i = 0
         |    while (i < n) {
         |      dst(i) = (src(i) & 0x7f).toByte
         |      i += 1
         |    }
         |    dst
         |  }
         |}
         |""".stripMargin
    val uri = URI.create("file:///Blk2.scala")
    val src = SourceFile.virtual(uri, code)
    val atN = code.indexOf("val n =") + 5
    val atToByte = code.indexOf(".toByte") + 7
    val (before, after, resumed, sameRun) = withDriver { d =>
      d.runUpTo(uri, src, Span(atN))
      val run0 = d.runIdOf(uri)
      val before = d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 9)).trim)
      d.runUpTo(uri, src, Span(atToByte))
      val sameRun = d.runIdOf(uri) == run0
      val after = d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 9)).trim)
      val unit = d.compilationUnits(uri)
      given Context = d.currentCtx.fresh.setCompilationUnit(unit)
      val path = Interactive.pathTo(unit.tpdTree, Span(atToByte))
      (before, after, path.headOption.map(t => s"${t.getClass.getSimpleName} ${t.symbol.name}"), sameRun)
    }
    println(s"[a-target-in-a-skipped-block-statement-is-resumed] before: $before; after: $after; at toByte: $resumed; same run: $sameRun")
    assert(sameRun, "the second request resumes the first request's run")
    assert(before.exists(_.startsWith("while")), s"the while is skipped by the first request: $before")
    assert(!after.exists(_.startsWith("while")), s"the while is typed by the second: $after")
    assert(resumed.contains("Select toByte"), s"the tree at the target is the typed select: $resumed")

  @Test def `a-whole-file-run-completes-the-session` =
    // A whole-file run on the session's content (document highlight asks for
    // one on every cursor move) types what is still skipped and keeps the run:
    // the request after it is served, not a fresh run.
    val code = (0 until 20).map(i => s"object W$i {\n  def m$i(xs: List[Int]): Int = xs.map(_ + $i).filter(_ > $i).sum\n  val r = this.m$i\n}").mkString("\n") + "\n"
    val uri = URI.create("file:///W.scala")
    val src = SourceFile.virtual(uri, code)
    val at3 = code.indexOf(".filter(_ > 3)") + 4
    val at15 = code.indexOf(".filter(_ > 15)") + 4
    val (run0, skipped0, run1, fully, run2, skipped2) = withDriver { d =>
      d.runUpTo(uri, src, Span(at3))
      val run0 = d.runIdOf(uri); val skipped0 = d.untypedSpans(uri).size
      d.run(uri, src)
      val run1 = d.runIdOf(uri); val fully = d.isFullyTyped(uri)
      d.runUpTo(uri, src, Span(at15))
      (run0, skipped0, run1, fully, d.runIdOf(uri), d.untypedSpans(uri).size)
    }
    println(s"[a-whole-file-run-completes-the-session] targeted run $run0 skipped $skipped0; after run: run $run1 fully typed $fully; after the next request: run $run2 skipped $skipped2")
    assert(skipped0 > 0, "the targeted run skipped something")
    assert(run1 == run0 && fully, "the whole-file run completed the session in the same run")
    assert(run2 == run0 && skipped2 == 0, "the request after it is served from the same run")

  @Test def `an-inline-method-body-is-never-stubbed` =
    // The body of an inline method is registered whole for its callers and
    // mapped by PrepareInlineable: a stub for a skipped local inside it
    // broke that map (UnAssignedTypeException on the stub's type tree).
    val code =
      """|object Inl {
         |  inline def twice(x: Int): Int = {
         |    val a = x + 1
         |    val b = a * 2
         |    val c = b - 1
         |    c + a
         |  }
         |  def use(y: Int): Int = twice(y) + 1
         |}
         |""".stripMargin
    val uri = URI.create("file:///Inl.scala")
    val src = SourceFile.virtual(uri, code)
    val (skippedInInline, usedOk) = withDriver { d =>
      d.runUpTo(uri, src, Span(code.indexOf("b - 1") + 2))
      val inInline = d.untypedSpans(uri).filter(sp => sp.start > code.indexOf("inline def") && sp.end < code.indexOf("def use"))
      d.runUpTo(uri, src, Span(code.indexOf("twice(y)") + 3))
      val unit = d.compilationUnits(uri)
      given Context = d.currentCtx.fresh.setCompilationUnit(unit)
      (inInline.size, Interactive.pathTo(unit.tpdTree, Span(code.indexOf("twice(y)") + 3)).headOption.exists(_.symbol.name.toString == "twice"))
    }
    println(s"[an-inline-method-body-is-never-stubbed] skipped inside the inline body: $skippedInInline; the call site typed: $usedOk")
    assert(skippedInInline == 0, "no statement of an inline method's body is skipped")
    assert(usedOk, "the request in the caller of the inline method is answered")

  /** Completions at `at` from the session, and from a fresh whole-file run of a new driver. */
  private def completionsVsOracle(d: CachingDriver, uri: URI, src: SourceFile, at: Int): (List[String], List[String]) =
    def labelsOf(driver: dotty.tools.dotc.interactive.InteractiveDriver): List[String] =
      val unit = driver.compilationUnits(uri)
      given dotty.tools.dotc.core.Contexts.Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(dotty.tools.dotc.core.Phases.typerPhase(using driver.currentCtx))
      dotty.tools.dotc.interactive.Completion.completions(dotty.tools.dotc.util.SourcePosition(src, Span(at)))._2.map(_.label).sorted.distinct
    d.runUpTo(uri, src, Span(at))
    val session = labelsOf(d)
    val fresh = new dotty.tools.dotc.interactive.InteractiveDriver(dotty.tools.pc.utils.TestingWorkspaceSearch(dotty.tools.pc.base.TestResources.classpath.map(_.toString)).settings, dotty.tools.dotc.interactive.CachedLogicalPackage.none)
    fresh.run(uri, src)
    (session, labelsOf(fresh))

  @Test def `cancel-inside-a-sibling-completer` =
    // The oracle's sequence on Contexts.scala (seed 1, step 14): a resume into
    // `def set` forces the completer of its skipped sibling `update[T]`, and
    // the cancellation lands inside that completer (its long signature). The
    // resumed statement is replaced; the sibling must be too: its completer,
    // retried, had entered `T$1` next to the `T` it entered before the abort
    // ("T is already defined as type T"), and `update(...)` then failed to
    // type against the mixed signature.
    val params = (1 to 40).map(i => s"p$i: List[Map[T, T]]").mkString(", ")
    val args = (1 to 40).map(_ => "Nil").mkString(", ")
    val code =
      s"""|object Store:
          |  class Loc[T]
          |  def set(x: Int): Int = update(new Loc[Int], x, $args).to
          |  def update[T](loc: Loc[T], value: T, $params): Int = 1
          |""".stripMargin
    val uri = URI.create("file:///Sibling.scala")
    val src = SourceFile.virtual(uri, code)
    val atSet = code.indexOf("update(new") + 3
    val atTo = code.indexOf(".to\n") + 3
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    try
      val (thrown, polls, run1, run2, skippedBefore, skippedAfter, session, oracle) = withDriver { d =>
        d.runUpTo(uri, src, Span(code.indexOf("class Loc") + 8)) // set and update skipped
        val run1 = d.runIdOf(uri)
        val skippedBefore = d.untypedSpans(uri).size
        var polls = 0
        val thrown =
          try { d.withCancelChecker(() => { if inTyperCheckpoint() then polls += 1; polls >= 2 }) { d.runUpTo(uri, src, Span(atSet)) }; false }
          catch case _: CancelledCompilation => true
        val skippedAfter = d.untypedSpans(uri).size
        val (session, oracle) = completionsVsOracle(d, uri, src, atTo)
        (thrown, polls, run1, d.runIdOf(uri), skippedBefore, skippedAfter, session, oracle)
      }
      println(s"[cancel-inside-a-sibling-completer] thrown=$thrown polls=$polls run=$run1->$run2 skipped=$skippedBefore->$skippedAfter; session: ${session.take(6)}; whole-file: ${oracle.take(6)}")
      assert(thrown, "the cancellation must land")
      assertEquals(run1, run2, "the session is kept: both statements are replaced")
      assertEquals(skippedBefore, skippedAfter, "the aborted statement and the sibling are skipped again")
      org.junit.Assert.assertEquals(oracle.mkString("\n"), session.mkString("\n"))
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  @Test def `a-claim-is-released-when-its-typing-throws` =
    // Finding I61: a resume claims its statement (the session's `typing`),
    // and gave it back only on a cancellation. Any other exception escaping
    // the statement's typing (here one thrown by the checker at a typer
    // checkpoint, in a body that forces no completer, so that the run is not
    // tainted) left the claim behind, and the next request on the statement
    // waited for ever for a reader that was gone. The claim is now released
    // and the session poisoned: the next request starts a fresh run and
    // answers as a whole-file run does.
    val terms = (1 to 400).map(i => s"$i + $i").mkString("List(", ", ", ").sum")
    val code =
      s"""|object Heavy:
          |  def light: Int = 1
          |  def heavy: Int = $terms
          |  def after: Int = heavy.abs
          |""".stripMargin
    val uri = URI.create("file:///Heavy.scala")
    val src = SourceFile.virtual(uri, code)
    val atHeavy = code.indexOf(", 200 + 200,") + 2
    val atAbs = code.indexOf(".abs") + 3
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    try
      complete("object Warm { val x = 1.to@@ }") // the compiler access is started by a first request
      val (thrown, poisonedRun, run2, session, oracle) = withDriver { d =>
        d.runUpTo(uri, src, Span(code.indexOf("light") + 2)) // heavy and after skipped
        val run1 = d.runIdOf(uri)
        val thrown =
          try { d.withCancelChecker(() => { if inTyperCheckpoint() then throw new IllegalStateException("injected") else false }) { d.runUpTo(uri, src, Span(atHeavy)) }; None }
          catch case ex: IllegalStateException => Some(ex.getMessage)
        // the same statement again: with the claim left behind, this waited for ever
        d.runUpTo(uri, src, Span(atHeavy))
        val run2 = d.runIdOf(uri)
        val (session, oracle) = completionsVsOracle(d, uri, src, atAbs)
        (thrown, run1, run2, session, oracle)
      }
      println(s"[a-claim-is-released-when-its-typing-throws] thrown=$thrown run=$poisonedRun->$run2; session: ${session.take(6)}; whole-file: ${oracle.take(6)}")
      assertEquals(Option("injected"), thrown, "the injected exception escapes the request")
      assert(run2 != poisonedRun, "the session is poisoned: the next request starts a fresh run")
      org.junit.Assert.assertEquals(oracle.mkString("\n"), session.mkString("\n"))
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  @Test def `cancel-inside-a-given-class-completer` =
    // The shape of finding I18 (a given not found after a cancellation, in
    // the same run): a cancellation lands inside the class completer of a
    // given (its parents being typed) that an implicit search forced. The
    // completer had set the class's temporary info, so the class is
    // `isCompleted` without parents and is never retried, and
    // `summon[Show[Int]]` finds no given for the rest of the run. A given is
    // not replaced (the contexts' implicit caches hold it by symbol): the
    // session is poisoned and the next request starts fresh. (A top-level
    // given is a member of the synthetic package object, which is typed
    // whole by the first request; one in an object is completed lazily.)
    val markers = (1 to 40).map(i => s"trait M$i").mkString("\n")
    val withs = (1 to 40).map(i => s"M$i").mkString(" with ")
    val code =
      s"""|trait Show[T]:
          |  def show(t: T): String
          |$markers
          |object Givens:
          |  given Show[Int] with $withs with
          |    def show(t: Int) = t.toString
          |object Infer:
          |  import Givens.given
          |  val d = summon[Show[Int]].sh
          |  val e = 1
          |""".stripMargin
    val uri = URI.create("file:///GivenCompleter.scala")
    val src = SourceFile.virtual(uri, code)
    val atD = code.indexOf(".sh\n") + 3
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    try
      val (thrown, polls, run1, run2, session, oracle) = withDriver { d =>
        d.runUpTo(uri, src, Span(code.indexOf("val e = 1") + 4)) // val d and Givens skipped
        val run1 = d.runIdOf(uri)
        var polls = 0
        val thrown =
          try { d.withCancelChecker(() => { if inTyperCheckpoint() then polls += 1; polls >= 2 }) { d.runUpTo(uri, src, Span(atD)) }; false }
          catch case _: CancelledCompilation => true
        val (session, oracle) = completionsVsOracle(d, uri, src, atD)
        (thrown, polls, run1, d.runIdOf(uri), session, oracle)
      }
      println(s"[cancel-inside-a-given-class-completer] thrown=$thrown polls=$polls run=$run1->$run2; session: $session; whole-file: $oracle")
      assert(thrown, "the cancellation must land")
      org.junit.Assert.assertEquals(oracle.mkString("\n"), session.mkString("\n"))
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  private val closureBody =
    """|object Com {
       |  def run(xs: List[Int]): Int = {
       |    var acc = 0
       |    xs.foreach { x =>
       |      if (x > 0) { List(x, x).foreach(y => acc += y); Option(x).map(_ + 1).foreach(acc += _) }
       |      acc += x
       |    }
       |    acc
       |  }
       |}
       |""".stripMargin

  @Test def `a-closure-argument-is-typed-whole` =
    // A closure argument is typed in the typer state that tryEither makes for
    // the application, which may be retried or abandoned: its body is not
    // targeted, so nothing in it is recorded in a state that does not last
    // (I24 resumed under such a state once it had been committed).
    val code = closureBody
    val uri = URI.create("file:///Com.scala")
    val src = SourceFile.virtual(uri, code)
    val atLast = code.indexOf("acc += x") + 4
    val atInner = code.indexOf("List(x, x)") + 2
    val (sameRun, before, after, at) = withDriver { d =>
      d.runUpTo(uri, src, Span(atLast))
      val run0 = d.runIdOf(uri)
      val before = d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 10)).trim)
      d.runUpTo(uri, src, Span(atInner))
      val after = d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 10)).trim)
      val unit = d.compilationUnits(uri)
      given Context = d.currentCtx.fresh.setCompilationUnit(unit)
      val path = Interactive.pathTo(unit.tpdTree, Span(atInner))
      (d.runIdOf(uri) == run0, before, after, path.headOption.map(t => if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>"))
    }
    println(s"[a-closure-argument-is-typed-whole] same run: $sameRun; before: $before; after: $after; at List(x, x): $at")
    assert(!before.exists(_.startsWith("if (x > 0)")), s"the closure body is typed whole by the first request: $before")
    assert(sameRun, "the second request is served from the first request's run")
    assert(at.exists(_.contains("List")), s"the statement in the closure body is typed: $at")

  @Test def `the-completing-run-types-a-closure-body-under-a-live-typer-state` =
    // The completing run over a file whose closure body is typed whole (I24).
    val code = closureBody
    val uri = URI.create("file:///Com2.scala")
    val src = SourceFile.virtual(uri, code)
    val (fully, at) = withDriver { d =>
      d.runUpTo(uri, src, Span(code.indexOf("acc += x") + 4))
      d.run(uri, src)
      val unit = d.compilationUnits(uri)
      given Context = d.currentCtx.fresh.setCompilationUnit(unit)
      val path = Interactive.pathTo(unit.tpdTree, Span(code.indexOf("List(x, x)") + 2))
      (d.isFullyTyped(uri), path.headOption.map(t => if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>"))
    }
    println(s"[the-completing-run-types-a-closure-body] fully typed: $fully; at List(x, x): $at")
    assert(fully, "the whole-file run completes the session")
    assert(at.exists(_.contains("List")), s"the statement in the closure body is typed: $at")

  @Test def `leaving-a-file-completes-its-extension-methods` =
    // Before a request leaves a file, the symbols of its skipped statements are
    // completed in their own run (I17). An `extension` block is not a DefTree:
    // its methods kept the left run's completers, the next run gave them an
    // error type silently, and an inferred union over it tripped
    // TypeOps.orDominator's assertion (I27).
    val uriA = URI.create("file:///ExtA.scala")
    val uriB = URI.create("file:///ExtB.scala")
    val codeA =
      """|object ExtA {
         |  extension (s: String) def twice = s.length * 2
         |  def n = 1
         |}
         |""".stripMargin
    val codeB =
      """|object ExtB {
         |  import ExtA.*
         |  def run(b: Boolean): Int = {
         |    def pick = if b then "x".twice else 0
         |    pick
         |  }
         |}
         |""".stripMargin
    val (diags, tpe) = withDriver { d =>
      d.runUpTo(uriA, SourceFile.virtual(uriA, codeA), Span(codeA.indexOf("def n") + 5))
      val diags = d.run(uriB, SourceFile.virtual(uriB, codeB))
      val unit = d.compilationUnits(uriB)
      given Context = d.currentCtx.fresh.setCompilationUnit(unit)
      val path = Interactive.pathTo(unit.tpdTree, Span(codeB.indexOf("\"x\".twice") + 5))
      (diags.map(_.msg.message), path.headOption.map(t => if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>"))
    }
    println(s"[leaving-a-file-completes-its-extension-methods] diagnostics: $diags; twice: $tpe")
    org.junit.Assert.assertEquals(Nil, diags)
    org.junit.Assert.assertEquals(Some("(s: String): Int"), tpe)

  @Test def `a-pattern-definition-in-a-block-is-spliced` =
    // `val (loc, store1) = ...` in a block desugars to a synthetic temporary,
    // never skipped, and one val per name, skipped with stubs; the temporary's
    // span covers the vals. The completing run's splice took the temporary for
    // a statement nesting them and found no block to put them in (I31).
    val code =
      """|object Pat {
         |  def pair(n: Int): (Int, String) = (n, n.toString)
         |  def use(n: Int): Int = {
         |    val (a, b) = pair(n)
         |    val c = n + 1
         |    a + b.length + c
         |  }
         |}
         |""".stripMargin
    val uri = URI.create("file:///Pat.scala")
    val src = SourceFile.virtual(uri, code)
    val (fully, atA) = withDriver { d =>
      d.runUpTo(uri, src, Span(code.indexOf("n + 1") + 2))
      d.run(uri, src)
      val unit = d.compilationUnits(uri)
      given Context = d.currentCtx.fresh.setCompilationUnit(unit)
      val path = Interactive.pathTo(unit.tpdTree, Span(code.indexOf("val (a, b)") + 6))
      (d.isFullyTyped(uri), path.headOption.map(t => s"${t.getClass.getSimpleName} ${t.symbol.name}"))
    }
    println(s"[a-pattern-definition-in-a-block-is-spliced] fully typed: $fully; at a: $atA")
    assert(fully, "the whole-file run completes the session")
    assert(atA.exists(_.endsWith(" a")), s"the pattern's val is in the tree: $atA")

  @Test def `a-block-in-a-method-body-is-still-targeted` =
    // The rule types closure arguments whole; the blocks of a method body are
    // typed in the class body's typer state and keep being targeted.
    val code =
      """|object Tgt {
         |  def big(xs: List[Int]): Int = {
         |    val a = xs.map(_ + 1).sum
         |    if (a > 0) {
         |      val b = xs.map(_ + 2).sum
         |      val c = xs.map(_ + 3).filter(_ > 0).sum
         |      b + c
         |    } else 0
         |  }
         |}
         |""".stripMargin
    val uri = URI.create("file:///Tgt.scala")
    val src = SourceFile.virtual(uri, code)
    val skipped = withDriver { d =>
      d.runUpTo(uri, src, Span(code.indexOf(".filter(_ > 0)") + 4))
      d.untypedSpans(uri).map(sp => code.substring(sp.start, math.min(sp.end, sp.start + 9)).trim)
    }
    println(s"[a-block-in-a-method-body-is-still-targeted] skipped: $skipped")
    assert(skipped.exists(_.startsWith("val b")), s"a statement of the if's block is skipped: $skipped")

  @Test def `a-cancelled-resume-leaves-no-constraint-behind` =
    // A resumed statement is typed in the session's long-lived typer state
    // (the class body's). A cancellation drops the statement's tree, and must
    // drop what its partial typing added to the constraint with it: type
    // variables created for trees that no longer exist, possibly instantiated.
    // The statement is long and every line of it a polymorphic chain, so that
    // a cancellation at an early checkpoint (one every 16 typed trees) lands
    // with type variables already created.
    val lines = (0 until 40).map(j => s"    val v$j = xs.map(_ + $j).foldLeft(0)(_ + _) + List(v${if j == 0 then 0 else j - 1}, $j).max").mkString("\n")
      .replace("List(v0, 0)", "List(0, 0)")
    val code =
      s"""|object Leak {
          |  def heavy(xs: List[Int]): Int = {
          |$lines
          |    v39
          |  }
          |  def light = 1
          |}
          |""".stripMargin
    val uri = URI.create("file:///Leak.scala")
    val src = SourceFile.virtual(uri, code)
    val atLight = code.indexOf("def light") + 6
    val atInHeavy = code.indexOf("val v30") + 6
    def snapshot(d: dotty.tools.dotc.interactive.InteractiveDriver): (Int, Int, Int) =
      val ts = d.currentCtx.typerState
      (ts.ownedVars.size, ts.constraint.domainParams.size, ts.uninstVars.size)
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    try
      val (before, after, thrown, polls, sameRun, again) = withDriver { d =>
        d.runUpTo(uri, src, Span(atLight)) // heavy is skipped
        val run0 = d.runIdOf(uri)
        val before = snapshot(d)
        var polls = 0
        val thrown =
          try { d.withCancelChecker(() => { if inTyperCheckpoint() then polls += 1; polls >= 3 }) { d.runUpTo(uri, src, Span(atInHeavy)) }; false }
          catch case _: CancelledCompilation => true
        val after = snapshot(d)
        val sameRun = d.runIdOf(uri) == run0
        // and the request after it still answers
        d.runUpTo(uri, src, Span(atInHeavy))
        val unit = d.compilationUnits(uri)
        given Context = d.currentCtx.fresh.setCompilationUnit(unit)
        val again = dotty.tools.dotc.interactive.Interactive.pathTo(unit.tpdTree, Span(atInHeavy)).headOption
          .map(t => if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>")
        (before, after, thrown, polls, sameRun, again)
      }
      println(s"[a-cancelled-resume-leaves-no-constraint-behind] thrown=$thrown polls=$polls same run=$sameRun; (ownedVars, domainParams, uninstVars) before=$before after=$after; then at v30: $again")
      assert(thrown, "the cancellation must land inside the resume")
      assert(sameRun, "a cancelled resume keeps the run")
      org.junit.Assert.assertEquals("the session's typer state is as it was before the cancelled resume", before, after)
      assert(again.exists(_.contains("Int")), s"the request after the cancellation answers: $again")
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  @Test def `a-cancelled-completing-run-leaves-no-constraint-behind` =
    // A resumed statement is typed in the session's long-lived typer state
    // (the class body's). A cancellation drops the statement's tree, and must
    // drop what its partial typing added to the constraint with it: type
    // variables created for trees that no longer exist, possibly instantiated.
    // The statement is long and every line of it a polymorphic chain, so that
    // a cancellation at an early checkpoint (one every 16 typed trees) lands
    // with type variables already created.
    val lines = (0 until 40).map(j => s"    val v$j = xs.map(_ + $j).foldLeft(0)(_ + _) + List(v${if j == 0 then 0 else j - 1}, $j).max").mkString("\n")
      .replace("List(v0, 0)", "List(0, 0)")
    val code =
      s"""|object Leak {
          |  def heavy(xs: List[Int]): Int = {
          |$lines
          |    v39
          |  }
          |  def light = 1
          |}
          |""".stripMargin
    val uri = URI.create("file:///Leak2.scala")
    val src = SourceFile.virtual(uri, code)
    val atLight = code.indexOf("def light") + 6
    val atInHeavy = code.indexOf("val v30") + 6
    def snapshot(d: dotty.tools.dotc.interactive.InteractiveDriver): (Int, Int, Int) =
      val ts = d.currentCtx.typerState
      (ts.ownedVars.size, ts.constraint.domainParams.size, ts.uninstVars.size)
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    try
      val (before, after, thrown, polls, sameRun, again) = withDriver { d =>
        d.runUpTo(uri, src, Span(atLight)) // heavy is skipped
        val run0 = d.runIdOf(uri)
        val before = snapshot(d)
        var polls = 0
        val thrown =
          try { d.withCancelChecker(() => { if inTyperCheckpoint() then polls += 1; polls >= 3 }) { d.run(uri, src) }; false }
          catch case _: CancelledCompilation => true
        val after = snapshot(d)
        val sameRun = d.runIdOf(uri) == run0
        // and the request after it still answers
        d.runUpTo(uri, src, Span(atInHeavy))
        val unit = d.compilationUnits(uri)
        given Context = d.currentCtx.fresh.setCompilationUnit(unit)
        val again = dotty.tools.dotc.interactive.Interactive.pathTo(unit.tpdTree, Span(atInHeavy)).headOption
          .map(t => if t.hasType then t.tpe.widenTermRefExpr.show else "<untyped>")
        (before, after, thrown, polls, sameRun, again)
      }
      println(s"[a-cancelled-completing-run-leaves-no-constraint-behind] thrown=$thrown polls=$polls same run=$sameRun; (ownedVars, domainParams, uninstVars) before=$before after=$after; then at v30: $again")
      assert(thrown, "the cancellation must land inside the completing run")
      assert(sameRun, "a cancelled completing run keeps the run")
      org.junit.Assert.assertEquals("the session's typer state is as it was before the cancelled completing run", before, after)
      assert(again.exists(_.contains("Int")), s"the request after the cancellation answers: $again")
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  @Test def `cancel-inside-a-member-driver-only` =
    // the same sequence as `cancel-inside-a-member`, on the driver, with any exception printed
    val heavy = Set(300)
    val (code, _) = params(bigSource("cancel-member-d", 300, heavy = heavy))
    val (code2, offset2) = params(bigSource("cancel-member-d", 300, inGiant = true, heavy = heavy))
    val uri2 = URI.create("file:///CancelD.scala")
    val src = SourceFile.virtual(uri2, code)
    withDriver { d =>
      try
        d.runUpTo(uri2, src, Span(code.indexOf("this.meth", code.indexOf("object M300")) + 9))
        println(s"[driver-only] after val r: run=${d.runIdOf(uri2)} skipped=${d.untypedSpans(uri2).size}")
        var polls = 0
        val thrown =
          try { d.withCancelChecker(() => { if inTyperCheckpoint() then polls += 1; polls >= 1 }) { d.runUpTo(uri2, src, Span(offset2)) }; false }
          catch case _: CancelledCompilation => true
        println(s"[driver-only] cancel: thrown=$thrown polls=$polls run=${d.runIdOf(uri2)} skipped=${d.untypedSpans(uri2).size}")
        d.runUpTo(uri2, src, Span(offset2))
        println(s"[driver-only] again: run=${d.runIdOf(uri2)} skipped=${d.untypedSpans(uri2).size}")
      catch case scala.util.control.NonFatal(ex) =>
        println(s"[driver-only] EXCEPTION ${ex}\n" + ex.getStackTrace.take(14).mkString("\n"))
        throw ex
    }

  @Test def `a-macro-in-a-long-targeted-run` =
    // I19: the trees of an inlined macro body carry the macro's source, and a
    // skip rule comparing their spans with the target offset skipped the splice
    // body's `$anonfun` whenever the offset fell outside it, which depended on the
    // file's length (a chain of 30 failed, 3 worked). Targeting applies to the
    // request's source only. The checkpoint mask is varied to show it is not that.
    def source(tag: String, chain: Int) =
      val stats = (0 until chain).map(j => s"    val a$j = xs.map(_ + $j).filter(_ > $j).sum + ${if j == 0 then "latched" else s"a${j - 1}"}").mkString("\n")
      s"// $tag\nobject G {\n  def giant(xs: List[Int]): Int = {\n    val latched = tests.macros.Latch.await\n$stats\n    a0 + a${chain - 1}\n  }\n}\n"
    def macroErrors(diags: List[dotty.tools.dotc.reporting.Diagnostic]) = diags.map(_.msg.message).filter(_.contains("interpreted"))
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    try
      withDriver { d =>
        for (label, m, chain) <- List(("mask 1023, chain 30", 1023, 30), ("mask 1023, chain 3", 1023, 3), ("no checkpoint, chain 30", (1 << 30) - 1, 30), ("mask 15, chain 3", 15, 3)) do
          dotty.tools.dotc.Run.typerCheckpointMask = m
          val code = source(label, chain)
          val uri = URI.create(s"file:///Macro${m}_$chain.scala")
          val src = SourceFile.virtual(uri, code)
          val diags = d.runUpTo(uri, src, Span(code.indexOf(s"val a${chain - 1} =") + 8))
          println(s"[a-macro-in-a-long-targeted-run] $label: ${macroErrors(diags).map(_.take(70))}")
          org.junit.Assert.assertEquals(s"$label: the macro must expand (targeting applies to the request's source only, not to the inlined body's trees)", Nil, macroErrors(diags))
      }
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  @Test def `edit-starts-a-new-run` =
    assertNoDiff(expectedAt(10), complete(bigSource("edit", 10)))
    val (runId1, typed1, _) = state()
    println(s"[edit-starts-a-new-run] runId=$runId1 typedTopLevelCount=$typed1")
    assertNoDiff(expectedAt(10), complete(bigSource("edit-changed", 10)))
    val (runId2, typed2, _) = state()
    println(s"[edit-starts-a-new-run] after edit: runId=$runId2 typedTopLevelCount=$typed2")
    assert(runId2 > runId1, "changed content must start a new run")

  @Test def `cancel-inside-a-statement` =
    val heavy = Set(300)
    assertNoDiff(expectedAt(10), complete(bigSource("cancel", 10, heavy = heavy)))
    val (runId1, typed1, skipped1) = state()
    println(s"[cancel-inside-a-statement] after object 10: runId=$runId1 typedTopLevelCount=$typed1 skipped=$skipped1")
    val (code, offset) = params(bigSource("cancel", 300, inGiant = true, heavy = heavy))
    // Cancel at the second typer checkpoint reached while typing object 300
    // (its member `giant` contains the target, so it is typed with the object)
    val (thrown, polls) = cancelAtCheckpoint(2)(_.runUpTo(uri, SourceFile.virtual(uri, code), Span(offset)))
    val (runId2, typed2, skipped2) = state()
    println(s"[cancel-inside-a-statement] thrown=$thrown polls=$polls; after the abort: runId=$runId2 typedTopLevelCount=$typed2 skipped=$skipped2")
    assert(thrown, "the resume must be aborted with CancelledCompilation")
    assertEquals(runId1, runId2, "the aborted resume must keep the run")
    assertEquals(typed1, typed2, "the aborted statement is not typed")
    assertEquals(skipped1, skipped2, "the aborted statement is replaced and skipped again")
    // Within a typed statement, still served by the same run
    assertNoDiff(expectedAt(10), complete(bigSource("cancel", 10, heavy = heavy)))
    val (runId3, typed3, _) = state()
    println(s"[cancel-inside-a-statement] after object 10 (typed): runId=$runId3 typedTopLevelCount=$typed3")
    assertEquals(runId1, runId3, "a request within a typed statement is served by the same run")
    // The replaced statement, typed this time
    val obtained4 = complete(bigSource("cancel", 300, inGiant = true, heavy = heavy))
    val (runId4, typed4, skipped4) = state()
    println(s"[cancel-inside-a-statement] inside giant of object 300 (replaced, resumed): runId=$runId4 typedTopLevelCount=$typed4 skipped=$skipped4 (${obtained4.linesIterator.size} items)")
    assert(obtained4.linesIterator.exists(_.startsWith("filter")), s"expected filter among the completions, got: $obtained4")
    assertEquals(runId1, runId4, "the resume after the abort must continue the same run")
    assertEquals(typed1 + 2, typed4, "object 300 is typed")
    assertNoDiff(expectedAt(300), complete(bigSource("cancel", 300, heavy = heavy)))

  @Test def `cancel-inside-a-member` =
    val heavy = Set(300)
    // `val r` of the heavy object: its methods, `giant` included, are skipped
    assertNoDiff(expectedAt(300), complete(bigSource("cancel-member", 300, heavy = heavy)))
    val (runId1, typed1, skipped1) = state()
    println(s"[cancel-inside-a-member] after `val r` of object 300: runId=$runId1 typedTopLevelCount=$typed1 skipped=$skipped1")
    val (code, offset) = params(bigSource("cancel-member", 300, inGiant = true, heavy = heavy))
    // Cancel at the first typer checkpoint reached while typing `giant` alone
    val (thrown, polls) = cancelAtCheckpoint(1)(_.runUpTo(uri, SourceFile.virtual(uri, code), Span(offset)))
    val (runId2, typed2, skipped2) = state()
    println(s"[cancel-inside-a-member] thrown=$thrown polls=$polls; after the abort: runId=$runId2 typedTopLevelCount=$typed2 skipped=$skipped2")
    assert(thrown, "the resume must be aborted with CancelledCompilation")
    assertEquals(runId1, runId2, "the aborted resume must keep the run")
    assertEquals(skipped1, skipped2, "the aborted member is replaced and skipped again")
    val obtained3 = complete(bigSource("cancel-member", 300, inGiant = true, heavy = heavy))
    val (runId3, typed3, skipped3) = state()
    println(s"[cancel-inside-a-member] inside giant again: runId=$runId3 typedTopLevelCount=$typed3 skipped=$skipped3 (${obtained3.linesIterator.size} items)")
    assert(obtained3.linesIterator.exists(_.startsWith("filter")), s"expected filter among the completions, got: $obtained3")
    assertEquals(runId1, runId3, "after an abort inside a member, the same run serves the next request")
    assertEquals(skipped2 - 1, skipped3, "the member is typed")
