package dotty.tools.pc.tests.completion

import java.io.File
import java.net.URI

import scala.collection.mutable
import scala.language.unsafeNulls
import scala.util.Random

import dotty.tools.dotc.CompilationUnit.CancelledCompilation
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, Completion, InteractiveDriver}
import dotty.tools.dotc.util.{SourceFile, SourcePosition}
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.base.BasePCSuite
import dotty.tools.pc.base.TestResources

import org.junit.Test

/** A soak test of the session: a random sequence of targeted requests,
 *  cancellations and edits on one driver must give, at every completion
 *  point, the completions a fresh whole-file run gives (`InteractiveDriver.run`,
 *  everything typed, the batch answer), and must leave no symbol of the file
 *  marked `Touched`. The cancellations are at a random typer checkpoint, with
 *  a checkpoint every 16 trees instead of 1024, so they land in arbitrary
 *  statements, including ones being re-typed after a replacement.
 */
class CompletionSoakSuite extends BasePCSuite:

  private val uri = URI.create("file:///Soak.scala")

  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))

  private def newDriver(bodyEdits: Boolean = false) =
    new InteractiveDriver(if bodyEdits then settings :+ "-Yinteractive-body-edits" else settings, CachedLogicalPackage.none)

  /** Edits of the objects file's marked text: inside bodies of methods with
   *  a declared type (one leaving a type error, one in the 120-statement
   *  method), and one of a signature.
   */
  private val objectsEdits: List[String => String] = List(
    _.replace("xs.map(_ + 5).", "xs.map(_ + 5 * 2)."),
    _.replace("= xs.map(_ + 12).", "= { val k = 12; xs.map(_ + k) }."),
    _.replace("    a0 + a119\n", "    a0 + a119 + a7\n"),
    _.replace("fil@@ter(_ > 20).sum", "fil@@ter(_ > 20).sum + xs.hea"),
    _.replace("def method30(xs: List[Int]): Int", "def method30(xs: List[Int]): Long"))

  /** Edits of the shapes file: a companion's explicit `apply` (in an object
   *  no point is in, so skipped whole: refused), a match's scrutinee, an
   *  extension method (refused), a method without a result type (refused).
   */
  private val shapesEdits: List[String => String] = List(
    _.replace("def apply(x: Int): TestClass = ???", "def apply(x: Int): TestClass = new TestClass(x + 1)"),
    _.replace("def energy(act: Hobby): Int = act match", "def energy(act: Hobby): Int = (act: Activity | Hobby) match"),
    _.replace("str.toUpperCase * times", "str.toUpperCase * (times + 1)"),
    _.replace("def giv = summon[Show[String]].sho@@", "def giv = summon[Show[String]].sho@@ + \"\""))

  /** `@@` marks the completion points; the content is the text without them. */
  private def points(marked: String): (String, List[Int]) =
    val sb = new StringBuilder
    val offsets = mutable.ListBuffer.empty[Int]
    var i = 0
    while i < marked.length do
      if marked.startsWith("@@", i) then { offsets += sb.length; i += 2 }
      else { sb.append(marked.charAt(i)); i += 1 }
    (sb.toString, offsets.toList)

  /** Objects in the shape of `CompletionSessionSuite`, two points each, and
   *  one with a method big enough for a checkpoint; `tag` is an edit.
   */
  private def objectsFile(tag: String, n: Int = 40): String =
    val objects = (0 until n).map { i =>
      val giant =
        if i == n - 1 then "\n  def giant(xs: List[Int]): Int = {\n" + (0 until 120).map(j => s"    val a$j = xs.map(_ + $j + ${if j == 0 then "0" else s"a${j - 1}"}).fil${if j == 119 then "@@" else ""}ter(_ > $j).sum").mkString("\n") + "\n    a0 + a119\n  }"
        else ""
      s"""|object M$i {
          |  def method$i(xs: List[Int]): Int = xs.map(_ + $i).fil@@ter(_ > $i).sum$giant
          |  val r = this.meth@@
          |  val s = M${(i + 7) % n}.meth@@
          |}""".stripMargin
    }.mkString("\n")
    s"// $tag\n$objects\n"

  /** The shapes the wider completion suites tripped on: a sealed hierarchy
   *  whose children are enum cases elsewhere, a case class with an explicit
   *  companion `apply`, an extension block, givens, a nested package clause,
   *  blocks, inferred types across statements.
   */
  private def shapesFile(tag: String): String =
    s"""|// $tag
        |package shapes {
        |  object Tags:
        |    sealed trait Hobby
        |    sealed trait Chore
        |  import Tags.*
        |  enum Activity:
        |    case Reading(book: String) extends Activity, Hobby
        |    case Cleaning(time: Int) extends Activity, Chore
        |    case Singing(song: String) extends Activity, Hobby
        |  object Wrapper:
        |    case class TestClass(x: Int)
        |    object TestClass:
        |      def apply(x: Int): TestClass = ???
        |    case class Plain(y: String)
        |  extension (str: String)
        |    def shout(times: Int): String = str.toUpperCase * times
        |    def whisper: String = str.toLowerCase
        |  trait Show[T]:
        |    def show(t: T): String
        |  given Show[Int] with
        |    def show(t: Int) = t.toString
        |  given showStr: Show[String] with
        |    def show(t: String) = t
        |  object Infer:
        |    val a = Wrapper.Plain("p")
        |    val b = a.y@@
        |    def c = Wrapper.TestClass(1).x@@ + b.len@@
        |    val d = summon[Show[Int]].sh@@
        |}
        |package shapes.use {
        |  import shapes.*
        |  import shapes.Tags.*
        |  object Main {
        |    def energy(act: Hobby): Int = act match
        |      case Activity.Rea@@
        |    val w = Wrapper.TestCl@@
        |    val n = Wrapper.Test@@
        |    val sh = "x".sho@@
        |    val wh = "x".whi@@
        |    locally {
        |      val local = Infer.a
        |      local.y@@
        |      val t = Infer.d.leng@@
        |    }
        |    def giv = summon[Show[String]].sho@@
        |  }
        |}
        |""".stripMargin

  private def labels(driver: InteractiveDriver, source: SourceFile, offset: Int): List[String] =
    driver.compilationUnits.get(uri) match
      case Some(unit) =>
        given Context = driver.currentCtx.fresh.setCompilationUnit(unit).withPhase(Phases.typerPhase(using driver.currentCtx))
        Completion.completions(SourcePosition(source, Span(offset)))._2.map(c => c.label + " " + c.description).sorted.distinct
      case None => List("<no unit>")

  /** Symbols of the file still completing, over the packages the file declares. */
  private def touched(driver: InteractiveDriver): List[String] =
    given Context = driver.currentCtx
    val file = driver.openedFiles.get(uri).map(_.file)
    def ofFile(s: Symbol) = file.exists(f => s.associatedFile != null && s.associatedFile.path == f.path)
    def walk(pkg: Symbol): List[String] =
      pkg.info.decls.toList.flatMap { s =>
        if s.is(dotty.tools.dotc.core.Flags.Package) then walk(s.moduleClass)
        else if ofFile(s) then
          val nested = if s.isClass then s.asClass.unforcedDecls.toList else Nil
          (s :: nested).filter(_.isCompleting).map(_.show)
        else Nil
      }
    walk(defn.RootClass).distinct // the root scope lists a package once per entry (val and class): dedupe

  private def inTyperCheckpoint(): Boolean =
    Thread.currentThread.getStackTrace.exists(_.getMethodName == "checkCancelledTyping")

  /** With `edits`, the driver keeps its run across edits inside bodies
   *  (`-Yinteractive-body-edits`), and an edit step moves to any variant:
   *  the two tags (a layout edit) or one of `edits` applied to tag `a`.
   */
  private def soak(name: String, file: String => String, seed: Int, steps: Int, edits: List[String => String] = Nil): Unit =
    // A checkpoint every 16 typed trees, so that cancellations land inside
    // the small statements a targeted run types (1024 by default)
    val mask = dotty.tools.dotc.Run.typerCheckpointMask
    dotty.tools.dotc.Run.typerCheckpointMask = 15
    try soak0(name, file, seed, steps, edits)
    finally dotty.tools.dotc.Run.typerCheckpointMask = mask

  private def soak0(name: String, file: String => String, seed: Int, steps: Int, edits: List[String => String]): Unit =
    val variants = List("a", "b").map(tag => points(file(tag))) ++ edits.map(e => points(e(file("a"))))
    for (v, i) <- variants.zipWithIndex.drop(2) do
      org.junit.Assert.assertTrue(s"edit $i changes the file", v._1 != variants.head._1)
    // The oracle: a fresh driver, the whole file typed, the completions at every point
    val oracle = variants.map { (content, offsets) =>
      val d = newDriver()
      val source = SourceFile.virtual(uri, content)
      d.run(uri, source)
      offsets.map(o => o -> labels(d, source, o)).toMap
    }
    val driver = newDriver(bodyEdits = edits.nonEmpty)
    val adopted = mutable.Map.empty[String, Int].withDefaultValue(0)
    val rng = new Random(seed)
    var variant = 0
    var runId = -1
    var cancelled = 0
    var fresh = 0
    var polls = 0
    val log = mutable.ListBuffer.empty[String]
    def check(step: Int, what: String, offset: Int): Unit =
      val (content, _) = variants(variant)
      val source = SourceFile.virtual(uri, content)
      val diags = driver.runUpTo(uri, source, Span(offset))
      if what.startsWith("edit") then
        adopted(driver.lastEdit(uri).replaceAll(", shifted.*", "").replaceAll(" at \\[.*", "").replaceAll("\\d+", "N")) += 1
        log += s"$step edit to variant $variant: ${driver.lastEdit(uri)}"
      val obtained = labels(driver, source, offset)
      def treeAt: String =
        driver.compilationUnits.get(uri).map { unit =>
          given Context = driver.currentCtx.fresh.setCompilationUnit(unit)
          val path = dotty.tools.dotc.interactive.Interactive.pathTo(unit.tpdTree, Span(offset))
          path.take(3).map(t => s"${t.getClass.getSimpleName}[${t.span.start},${t.span.end}) ${t.show.take(60).replace('\n', ' ')} : ${if t.hasType then t.tpe.show.take(80) else "<untyped>"}").mkString(" <- ")
        }.getOrElse("<no unit>")
      val expected = oracle(variant)(offset)
      val id = driver.runIdOf(uri)
      if id != runId then { fresh += 1; runId = id }
      val skippedText = driver.untypedSpans(uri).map(sp => content.substring(sp.start, math.min(sp.end, sp.start + 18)).replace('\n', ' ')).mkString("[", " | ", "]")
      log += s"$step $what@$offset run=$id typed=${driver.typedTopLevelCount(uri)} skipped=${driver.untypedSpans(uri).size} $skippedText tainted=${driver.isTainted(uri)}"
      if expected != obtained then
        println(s"[soak $name seed=$seed step $step] MISMATCH at $offset: tree path: $treeAt; diagnostics of the request: ${diags.map(d => s"${d.pos.line + 1}: ${d.msg.message.take(100)}")}")
        // the givens of the file's packages, as the driver's context sees them
        locally {
          given Context = driver.currentCtx
          val file = driver.openedFiles.get(uri).map(_.file)
          def walk(pkg: Symbol): List[Symbol] = pkg.info.decls.toList.flatMap { s =>
            if s.is(dotty.tools.dotc.core.Flags.Package) then walk(s.moduleClass)
            else if file.exists(f => s.associatedFile != null && s.associatedFile.path == f.path) then List(s) else Nil }
          val syms = walk(defn.RootClass).distinct
          println(s"[soak] symbols of the file: " + syms.map(sy => s"${sy.name}(${sy.flagsString.take(30)}; completed=${sy.isCompleted} completing=${sy.isCompleting}${if sy.isCompleted then " info=" + sy.info.show.take(40) else ""})").mkString(", "))
          for pkgObj <- syms.filter(sy => sy.isClass && sy.name.toString.endsWith("$package$")) do
            val members = pkgObj.asClass.unforcedDecls.toList
            println(s"[soak] members of ${pkgObj.name}: " + members.map(m => s"${m.name}(${m.flagsString.take(40)}; completed=${m.isCompleted} completing=${m.isCompleting} info=${if m.isCompleted then m.info.show.take(60) else m.infoOrCompleter.getClass.getSimpleName})").mkString(", "))
          val dupes = syms.groupBy(_.name).filter(_._2.size > 1).keys
          println(s"[soak] duplicated names in the packages: ${dupes.mkString(", ")}")
        }
        // the same request in a fresh run of the same driver: per-run state, or the symbol table?
        val again = SourceFile.virtual(uri, content + "\n// again")
        driver.runUpTo(uri, again, Span(offset))
        println(s"[soak] the same completion in a fresh run of this driver: ${labels(driver, again, offset)}")
      org.junit.Assert.assertEquals(s"[$name seed=$seed step $step] completions at $offset after $what (variant $variant)\n${log.takeRight(if edits.nonEmpty then 40 else 8).mkString("\n")}\ncontent:\n${content.substring(math.max(0, offset - 80), math.min(content.length, offset + 20))}", expected.mkString("\n"), obtained.mkString("\n"))
      val t = touched(driver)
      org.junit.Assert.assertTrue(s"[$name seed=$seed step $step] symbols still Touched after $what: ${t.mkString(", ")}", t.isEmpty)
    for step <- 1 to steps do
      val (content, offsets) = variants(variant)
      val offset = offsets(rng.nextInt(offsets.size))
      rng.nextInt(10) match
        case 0 =>
          // one edit at a time: from `a` to any variant, from any other back to `a`
          variant = if variants.size == 2 then 1 - variant else if variant == 0 then 1 + rng.nextInt(variants.size - 1) else 0
          val now = variants(variant)._2
          check(step, "edit+complete", now(rng.nextInt(now.size)))
        case 1 | 2 =>
          val k = 1 + rng.nextInt(3)
          var p = 0
          val thrown =
            try
              driver.withCancelChecker(() => { if inTyperCheckpoint() then p += 1; p >= k }) {
                driver.runUpTo(uri, SourceFile.virtual(uri, content), Span(offset))
              }
              false
            catch case _: CancelledCompilation => true
          polls += p
          if thrown then cancelled += 1
          log += s"$step cancel(k=$k)@$offset thrown=$thrown polls=$p run=${driver.runIdOf(uri)} typed=${driver.typedTopLevelCount(uri)}"
          val t = touched(driver)
          org.junit.Assert.assertTrue(s"[$name seed=$seed step $step] symbols still Touched after a cancellation: ${t.mkString(", ")}", t.isEmpty)
          check(step, s"cancel(thrown=$thrown)+complete", if rng.nextBoolean() then offset else offsets(rng.nextInt(offsets.size)))
        case _ =>
          check(step, "complete", offset)
    println(s"[soak $name seed=$seed] $steps steps, $fresh runs, $cancelled cancellations landed (${polls} polls), ${variants.head._2.size} points${if edits.nonEmpty then adopted.toList.sorted.map((k, n) => s"$n× $k").mkString("; edits: ", ", ", "") else ""}")
    if edits.nonEmpty then
      org.junit.Assert.assertTrue(s"[$name seed=$seed] some body edits keep the run: $adopted", adopted.keys.exists(_.startsWith("body edit")))
    // A small file is mostly served from what is typed, so few attempts find typing to land in
    org.junit.Assert.assertTrue(s"[$name seed=$seed] the cancellations must land: $cancelled of ${steps / 5} attempts", cancelled >= 1)

  @Test def `objects-seed-1` = soak("objects", objectsFile(_), 1, 300)
  @Test def `objects-seed-2` = soak("objects", objectsFile(_), 2, 300)
  @Test def `shapes-seed-1` = soak("shapes", shapesFile, 1, 300)
  /** Finding I18: at step 266 the completion in `val d = summon[Show[Int]].sh` of
   *  a re-indexed `object Infer` answers nothing ("no implicit values were found
   *  that match type shapes.Show[Int]") in the session's run, while the same
   *  request in a fresh run of the same driver finds `show`. Per-run state left
   *  by the earlier cancellations (steps 262 and 265); the givens' symbols are
   *  complete and whole. Not reproduced since (the landings shift between JVMs);
   *  the completers the cancellations unwind in this file (`class Plain`'s, the
   *  `apply`s', `val d`'s) are replaced or poison the session now
   *  (`InteractiveDriver.replaceUnwound`), see
   *  `CompletionSessionSuite.cancel-inside-a-given-class-completer`.
   */
  @Test def `shapes-seed-2` = soak("shapes", shapesFile, 2, 300)
  @Test def `shapes-seed-3` = soak("shapes", shapesFile, 3, 300)

  // edits inside bodies keep the run (docs §12)
  @Test def `objects-body-edits-seed-1` = soak("objects", objectsFile(_), 1, 300, objectsEdits)
  @Test def `objects-body-edits-seed-2` = soak("objects", objectsFile(_), 2, 300, objectsEdits)
  @Test def `shapes-body-edits-seed-1` = soak("shapes", shapesFile, 1, 300, shapesEdits)
  @Test def `shapes-body-edits-seed-2` = soak("shapes", shapesFile, 2, 300, shapesEdits)
