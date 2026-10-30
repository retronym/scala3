package dotty.tools.pc.tests.completion

import java.io.File
import java.net.URI
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.language.unsafeNulls
import scala.jdk.CollectionConverters.*

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Interactive, InteractiveDriver}
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.base.BasePCSuite
import dotty.tools.pc.base.TestResources

import org.junit.Test

/** Two readers on one driver with `-Yinteractive-parallel` (step 4 of
 *  docs/_docs/internals/interactive-multi-reader.md): the shapes the design
 *  notes single out, each run many times with both threads released at once
 *  so that their completions interleave differently.
 *
 *  - A cross-thread cycle (§4.2): two objects whose inferred signatures need
 *    each other, asked for from two threads. Neither thread may hang, and
 *    each answer must be one a single reader gives in some order: the cycle
 *    is reported where an evaluation order closes it.
 *  - Peeks (§4.1): an import, and a package object, completed by one thread
 *    while the other resolves a name through them. A peek that took the
 *    other thread's completion for this one's would skip the import ("cyclic
 *    import, ignored") or the package object, and answer differently from
 *    the whole-file run.
 */
class CompletionConcurrentSuite extends BasePCSuite:

  // `-Ddotc.test.completionLock=global`: the suite under `-Yinteractive-completion-lock:global` (one completion at a time)
  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator)) ++
      sys.props.get("dotc.test.completionLock").map("-Yinteractive-completion-lock:" + _)

  private def newDriver(extra: String*) = new InteractiveDriver(settings ++ extra, CachedLogicalPackage.none)

  /** The type of the innermost typed tree at `offset` of `unit`, described. */
  private def describe(ctx0: Context, unit: dotty.tools.dotc.CompilationUnit, offset: Int): String =
    given Context = ctx0.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using ctx0))
    Interactive.pathTo(unit.tpdTree, Span(offset)).headOption match
      case Some(t) if t.hasType => t.tpe.widenTermRefExpr.show
      case Some(_) => "<untyped>"
      case None => "<no tree>"

  /** What a request at `offset` answers on `driver`: the type there. */
  private def ask(driver: InteractiveDriver, uri: URI, source: SourceFile, offset: Int): String =
    driver.readUpTo(uri, source, Span(offset))((ctx, unit, _) => describe(ctx, unit, offset))

  /** The most times a request may be aborted on a wait-for cycle and be
   *  retried (§4.2's deviation): an abort that recurs without bound is a
   *  livelock, which this makes a failing number instead of a hang.
   */
  private val maxCycleAborts = 20

  /** Cycle aborts per round, for the summary line. */
  private val cycleAborts = new java.util.concurrent.ConcurrentLinkedQueue[Int]()

  /** Run `a` and `b` on two threads released together, each in a request
   *  scope of its own reader on `driver`; their results, or the first
   *  failure. A hang fails the test with the readers' abort counts, and so
   *  does a request that was aborted more than `maxCycleAborts` times.
   */
  private def together[A, B](driver: InteractiveDriver)(a: => A, b: => B): (A, B) =
    val start = new CountDownLatch(1)
    val ra = new AtomicReference[Either[Throwable, A]]()
    val rb = new AtomicReference[Either[Throwable, B]]()
    val readers = new java.util.concurrent.ConcurrentHashMap[String, dotty.tools.dotc.Reader]()
    def thread[T](name: String, body: => T, into: AtomicReference[Either[Throwable, T]]) =
      val t = new Thread((() => {
        start.await()
        into.set(
          try Right(driver.withCancelChecker(() => false) { readers.put(name, driver.readerOfThisThread); body })
          catch
            case ex: AssertionError => Left(ex)
            case ex: StackOverflowError => Left(ex)
            case scala.util.control.NonFatal(ex) => Left(ex)
            case ex: dotty.tools.dotc.CompilationUnit.CancelledCompilation => Left(ex))
      }): Runnable, name)
      t.setDaemon(true)
      t.start()
      t
    val ta = thread("reader-a", a, ra)
    val tb = thread("reader-b", b, rb)
    start.countDown()
    ta.join(TimeUnit.SECONDS.toMillis(60)); tb.join(TimeUnit.SECONDS.toMillis(60))
    def counts = readers.asScala.map((n, r) => s"$n aborted ${r.waitCyclesAborted}x").mkString(", ")
    assert(!ta.isAlive && !tb.isAlive, s"a reader hung: a wait-for cycle that was not broken ($counts)")
    val most = readers.values.asScala.map(_.waitCyclesAborted).maxOption.getOrElse(0)
    cycleAborts.add(readers.values.asScala.map(_.waitCyclesAborted).sum)
    assert(most <= maxCycleAborts, s"a request was aborted more than $maxCycleAborts times, a livelock in the making ($counts)")
    (ra.get, rb.get) match
      case (Right(x), Right(y)) => (x, y)
      case (Left(ex), _) => throw ex
      case (_, Left(ex)) => throw ex

  // ---- §4.2: a cross-thread cycle ----

  private val cycleCode =
    """|object Pad { val p = 0 }
       |object A {
       |  def a = B.b + 1
       |  val useA = a
       |}
       |object B {
       |  def b = A.a + 1
       |  val useB = b
       |}
       |""".stripMargin

  @Test def `a-cross-thread-cycle-is-reported-not-hung` =
    val uri = URI.create("file:///Cycle.scala")
    val source = SourceFile.virtual(uri, cycleCode)
    val atA = cycleCode.indexOf("useA = a") + "useA = a".length
    val atB = cycleCode.indexOf("useB = b") + "useB = b".length
    // a single reader, in either order: where the cycle is reported depends
    // on which of `a` and `b` is completed first
    def single(first: Int, second: Int): (String, String) =
      val d = newDriver()
      d.runUpTo(uri, source, Span(cycleCode.indexOf("p = 0")))
      val x = ask(d, uri, source, first)
      val y = ask(d, uri, source, second)
      if first == atA then (x, y) else (y, x)
    val orders = Set(single(atA, atB), single(atB, atA))
    println(s"[concurrent cycle] single-reader answers (useA, useB): ${orders.mkString(" | ")}")
    var seen = Map.empty[(String, String), Int]
    for i <- 1 to 30 do
      val d = newDriver()
      // a session in which both objects are skipped, so that the two
      // requests resume them concurrently
      d.runUpTo(uri, source, Span(cycleCode.indexOf("p = 0")))
      val answer = together(d)(ask(d, uri, source, atA), ask(d, uri, source, atB))
      seen = seen.updated(answer, seen.getOrElse(answer, 0) + 1)
      assert(orders.contains(answer) || answer._1 == answer._2 && orders.exists(o => o._1 == answer._1 || o._2 == answer._2),
        s"round $i: two readers answered $answer, which no single-reader order gives (${orders.mkString(" | ")})")
    println(s"[concurrent cycle] 30 rounds, answers: ${seen.map((k, v) => s"$k x$v").mkString(", ")}; ${cycleAbortSummary()}")

  // ---- I63: a self type, one reader ----

  @Test def `a-self-type-is-not-a-cycle` =
    // A Namer completion with parallel readers ran in a child of its
    // creation context (`fresh`), which for a class owner reads as the
    // class's inner scope, where the self type is visible: typing the self
    // type looked `Base` up through `this`, back into the self symbol
    // ("Cyclic reference involving val self"), with one reader.
    val code =
      """|package p
         |trait Base[T] { def x: T }
         |trait Info[T] { self: Base[T] =>
         |  def y: T = x
         |  def z = List(y)
         |}
         |""".stripMargin
    val uri = URI.create("file:///SelfType.scala")
    val errors = newDriver().run(uri, SourceFile.virtual(uri, code)).filter(_.level >= 2).map(_.msg.message)
    assert(errors.isEmpty, s"a self type with -Yinteractive-parallel: ${errors.mkString("; ")}")

  // ---- I66: a local's type variable, constrained after its completion ----

  @Test def `a-local-type-variable-is-constrained-by-the-statement` =
    // With parallel readers a local val was completed in a typer state of
    // its own; `T` of `readLater`, which the closure's body constrains after
    // the completion, was dropped with that state and later instantiated to
    // `Nothing` ("Found: (strict : Nothing)"). Locals now complete in the
    // statement's state, as in the serialised mode.
    val code =
      """|object L:
         |  class Lazy[T](f: Int => T) { def complete: T = f(0) }
         |  def readLater[T <: AnyRef](op: Int => String ?=> T): Lazy[T] = Lazy(i => op(i)(using ""))
         |  def g: Int =
         |    val lazyStats = readLater(rdr => {
         |      val stats = List(rdr.toString)
         |      stats ++ stats
         |    })
         |    val strict = lazyStats.complete
         |    strict.length
         |""".stripMargin
    val uri = URI.create("file:///LocalTypeVar.scala")
    val errors = newDriver().run(uri, SourceFile.virtual(uri, code)).filter(_.level >= 2).map(_.msg.message)
    assert(errors.isEmpty, s"a local's type variable with -Yinteractive-parallel: ${errors.mkString("; ")}")

  // ---- §4.1: peeks ----

  private def peekCode(n: Int) =
    val members = (0 until n).map(i => s"  def m$i = List(${(0 until 20).map(j => s"$i + $j").mkString(", ")}).map(_ * 2).sum").mkString("\n")
    s"""|object Defs {
        |$members
        |  val answer = m0 + m${n - 1}
        |}
        |object U1 {
        |  import Defs.*
        |  val y = answer + m1
        |}
        |object U2 {
        |  import Defs.*
        |  val z = answer + m2
        |}
        |""".stripMargin

  @Test def `a-peek-at-an-import-another-reader-completes` =
    val code = peekCode(40)
    val uri = URI.create("file:///Peek.scala")
    val source = SourceFile.virtual(uri, code)
    val atY = code.indexOf("y = answer") + "y = answer".length
    val atZ = code.indexOf("z = answer") + "z = answer".length
    val whole = newDriver()
    val wholeDiags = whole.run(uri, source)
    val expected = (describeWhole(whole, uri, atY), describeWhole(whole, uri, atZ))
    for i <- 1 to 30 do
      val d = newDriver()
      d.runUpTo(uri, source, Span(code.indexOf("object Defs")))
      val answer = together(d)(ask(d, uri, source, atY), ask(d, uri, source, atZ))
      assert(answer == expected, s"round $i: two readers answered $answer, the whole-file run $expected")
      val diags = d.readAll(uri, source)((_, _, ds) => ds)
      assert(!diags.exists(_.msg.message.contains("cyclic")),
        s"round $i: a peek took the other reader's completion for a cycle: ${diags.map(_.msg.message).mkString("; ")}")
      assert(diags.size == wholeDiags.size, s"round $i: ${diags.size} diagnostics, the whole-file run ${wholeDiags.size}")
    println(s"[concurrent peek import] 30 rounds, answers $expected; ${cycleAbortSummary()}")

  private val packageObjectCode =
    """|package p {
       |  package object q {
       |    def helper = List(1, 2, 3).map(_ + 1).sum
       |    val twice = helper * 2
       |  }
       |  package q {
       |    object V1 { val v = helper + twice }
       |    object V2 { val w = twice + helper }
       |  }
       |}
       |""".stripMargin

  @Test def `a-peek-at-a-package-object-another-reader-completes` =
    val code = packageObjectCode
    val uri = URI.create("file:///PkgObj.scala")
    val source = SourceFile.virtual(uri, code)
    val atV = code.indexOf("v = helper") + "v = helper".length
    val atW = code.indexOf("w = twice") + "w = twice".length
    val whole = newDriver()
    whole.run(uri, source)
    val expected = (describeWhole(whole, uri, atV), describeWhole(whole, uri, atW))
    for i <- 1 to 30 do
      val d = newDriver()
      d.runUpTo(uri, source, Span(code.indexOf("package p")))
      val answer = together(d)(ask(d, uri, source, atV), ask(d, uri, source, atW))
      assert(answer == expected, s"round $i: two readers answered $answer, the whole-file run $expected")
    println(s"[concurrent peek package object] 30 rounds, answers $expected; ${cycleAbortSummary()}")

  /** The cycleAborts of the rounds since the last summary: total and most in a round. */
  private def cycleAbortSummary(): String =
    val xs = cycleAborts.asScala.toList
    cycleAborts.clear()
    s"cycleAborts ${xs.sum} in ${xs.size} rounds, at most ${xs.maxOption.getOrElse(0)} in one (bound $maxCycleAborts per request)"

  private def describeWhole(d: InteractiveDriver, uri: URI, offset: Int): String =
    describe(d.currentCtx, d.compilationUnits(uri), offset)
