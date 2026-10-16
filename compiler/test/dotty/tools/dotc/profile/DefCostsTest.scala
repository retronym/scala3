package dotty.tools.dotc.profile

import org.junit.Assert.*
import org.junit.*

import dotty.tools.DottyTest
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.core.Contexts.FreshContext
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Records the frames it is told about, as "enter <kind>:<label> in <kind>:<label>" and "exit <kind>:<label>" */
class RecordingCostListener extends CostListener:
  def enter(frame: CostFrame): Unit =
    val parent = frame.parent
    RecordingCostListener.events += s"enter ${name(frame)}" + (if parent == null then "" else s" in ${name(parent)}")
  def exit(frame: CostFrame): Unit =
    RecordingCostListener.events += s"exit ${name(frame)}"
  private def name(f: CostFrame) = s"${f.kind.label}:${f.label}"

object RecordingCostListener:
  val events = collection.mutable.ArrayBuffer[String]()

abstract class CostTestBase extends DottyTest {
  protected def compile(code: String, until: String = "typer"): Unit =
    RecordingCostListener.events.clear()
    val c = compilerWithChecker(until) { (_, _) => () }
    val run = c.newRun
    run.compileSources(List(SourceFile.virtual("test.scala", code)))
    assert(!run.runContext.reporter.hasErrors, "compilation failed")

  protected def events = RecordingCostListener.events

  /** Frames are left in the reverse order they were entered */
  protected def assertBalanced(): Unit =
    val stack = collection.mutable.Stack[String]()
    for e <- events do
      if e.startsWith("enter ") then stack.push(e.stripPrefix("enter ").split(" in ")(0))
      else assertEquals(stack.pop(), e.stripPrefix("exit "))
    assertTrue(stack.isEmpty)
}

class DefCostsTest extends CostTestBase {
  // a table per test (initializeCtx runs once per test instance, before its fields are set)
  override protected def initializeCtx(fc: FreshContext): Unit = {
    super.initializeCtx(fc)
    fc.setSetting(fc.settings.YprofileDefs, DefCostsTest.newTable().toAbsolutePath.nn.toString)
    fc.setSetting(fc.settings.YprofileDefsListener, classOf[RecordingCostListener].getName.nn)
  }

  private def rows = Files.readAllLines(DefCostsTest.current).nn.asScala.map(_.split('\t').toList)
  private def row(kind: String, sym: String) =
    rows.find(r => r(0) == kind && r(2) == sym).getOrElse(throw AssertionError(s"no $kind row for $sym in ${rows.mkString("\n")}"))

  @Test def inferredTypeIsChargedToItsDefinition(): Unit = {
    compile(
      """object T:
        |  def a: Int = b.size
        |  def b = List(1, 2, 3).map(_ + 1)
        |""".stripMargin)
    // b's result type is inferred while typing a: its completion is a frame of its own, nested in a's
    assertTrue(events.mkString("\n"), events.contains("enter complete:b in def:a"))
    // the unit is the parent of the definitions typed in it, and the phase of the unit
    assertTrue(events.mkString("\n"), events.exists(e => e.startsWith("enter def:T$ in unit:test.scala")))
    assertTrue(events.mkString("\n"), events.contains("enter unit:test.scala in phase:typer"))
    assertBalanced()

    assertEquals(List("kind", "phase", "symbol", "file", "line", "chars", "calls", "self_us", "total_us", "max_self_us", "self_alloc"), rows.head)
    assertEquals("1", row("complete", "T$.b")(6))
    assertEquals("1", row("def", "T$.a")(6))
    assertEquals("2", row("def", "T$.a")(4))   // line
    assertTrue(rows.exists(r => r(0) == "unit" && r(1) == "typer" && r(2) == "test.scala"))
  }

  @Test def megaPhasesHaveAFramePerDefinition(): Unit = {
    compile(
      """object M:
        |  def a(xs: List[Int]): Int = xs.map(_ + 1).sum
        |""".stripMargin, until = "erasure")
    val transforms = events.filter(_.startsWith("enter transform:"))
    assertTrue(events.mkString("\n"), transforms.contains("enter transform:a in transform:M$"))
    // each MegaPhase's work on the unit is a unit frame, inside the phase's frame
    assertTrue(events.mkString("\n"), transforms.contains("enter transform:M$ in unit:test.scala"))
    assertTrue(events.mkString("\n"), events.exists(e => e.startsWith("enter unit:test.scala in phase:MegaPhase{")))
    // erasure retypes the definitions
    assertTrue(events.mkString("\n"), events.contains("enter retype:a in retype:M$"))
    assertBalanced()
    // transform rows carry the MegaPhase's name, not the phase the context reports inside it
    assertTrue(rows.exists(r => r(0) == "transform" && r(1).startsWith("MegaPhase{") && r(2) == "M$.a"))
    assertTrue(rows.exists(r => r(0) == "retype" && r(1) == "erasure" && r(2) == "M$.a"))
  }

  @Test def inlineCallsAreChargedToTheirDefinition(): Unit = {
    compile(
      """object I:
        |  inline def twice(x: Int): Int = x + x
        |  def a: Int = twice(21)
        |""".stripMargin, until = "erasure")
    // expanded in the inlining phase, a MacroTransform, inside a's frame there
    assertTrue(events.mkString("\n"), events.contains("enter inline:inline in transform:a"))
    assertBalanced()
    assertTrue(rows.exists(r => r(0) == "inline" && r(1) == "inlining" && r(2) == "I$.a"))
  }

  @Test def localDefinitionsArePartOfTheirOwner(): Unit = {
    compile(
      """object L:
        |  def m: Int =
        |    val x = 1
        |    def y = x + 1
        |    y
        |""".stripMargin, until = "erasure")
    assertFalse(events.mkString("\n"), events.exists(e => e.split(" in ")(0).matches("(enter|exit) \\w+:(x|y)")))
    assertTrue(events.contains("enter def:m in def:L$"))
    assertTrue(events.contains("enter transform:m in transform:L$"))
  }
}

object DefCostsTest:
  // the table of the test set up last
  var current: Path = Path.of("").nn
  def newTable(): Path =
    val dir = Files.createTempDirectory("def_costs_test").nn
    dir.toFile.nn.deleteOnExit()
    current = dir.resolve("costs.tsv").nn
    current.toFile.nn.deleteOnExit()
    current

/** A listener alone: frames, no table */
class DefCostsListenerOnlyTest extends CostTestBase {
  override protected def initializeCtx(fc: FreshContext): Unit = {
    super.initializeCtx(fc)
    fc.setSetting(fc.settings.YprofileDefsListener, classOf[RecordingCostListener].getName.nn)
  }

  @Test def framesWithoutTable(): Unit = {
    compile(
      """object T:
        |  def a: Int = b.size
        |  def b = List(1, 2, 3)
        |""".stripMargin)
    assertTrue(events.mkString("\n"), events.contains("enter complete:b in def:a"))
    assertBalanced()
  }
}
