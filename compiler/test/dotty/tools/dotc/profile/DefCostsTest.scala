package dotty.tools.dotc.profile

import org.junit.Assert.*
import org.junit.*

import dotty.tools.DottyTest
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.core.Contexts.FreshContext
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Records the frames it is told about, for DefCostsTest */
class RecordingCostListener extends CostListener:
  def enter(frame: CostFrame): Unit =
    val parent = frame.parent
    RecordingCostListener.events += s"enter ${frame.label}" + (if parent == null then "" else s" in ${parent.label}")
  def exit(frame: CostFrame): Unit =
    RecordingCostListener.events += s"exit ${frame.label}"

object RecordingCostListener:
  val events = collection.mutable.ArrayBuffer[String]()

class DefCostsTest extends DottyTest {
  override protected def initializeCtx(fc: FreshContext): Unit = {
    super.initializeCtx(fc)
    fc.setSetting(fc.settings.YprofileDefs, DefCostsTest.table.toAbsolutePath.nn.toString)
    fc.setSetting(fc.settings.YprofileDefsListener, classOf[RecordingCostListener].getName.nn)
  }

  private def compile(code: String, until: String = "typer"): Unit =
    RecordingCostListener.events.clear()
    val c = compilerWithChecker(until) { (_, _) => () }
    val run = c.newRun
    run.compileSources(List(SourceFile.virtual("test.scala", code)))
    assert(!run.runContext.reporter.hasErrors, "compilation failed")

  @Test def inferredTypeIsChargedToItsDefinition(): Unit = {
    compile(
      """object T:
        |  def a: Int = b.size
        |  def b = List(1, 2, 3).map(_ + 1)
        |""".stripMargin)
    val events = RecordingCostListener.events
    // b's result type is inferred while typing a: its completion is a frame of its own, nested in a's
    assertTrue(events.mkString("\n"), events.contains("enter complete:T$.b in def:T$.a"))
    // frames are left in the reverse order they were entered
    val stack = collection.mutable.Stack[String]()
    for e <- events do
      if e.startsWith("enter ") then stack.push(e.stripPrefix("enter ").takeWhile(_ != ' '))
      else assertEquals(stack.pop(), e.stripPrefix("exit "))
    assertTrue(stack.isEmpty)

    val rows = Files.readAllLines(DefCostsTest.table).nn.asScala.map(_.split('\t').toList)
    assertEquals(List("kind", "phase", "symbol", "file", "line", "chars", "calls", "self_us", "total_us", "max_self_us", "self_alloc"), rows.head)
    def row(kind: String, sym: String) = rows.find(r => r(0) == kind && r(2) == sym).getOrElse(throw AssertionError(s"no $kind row for $sym in $rows"))
    assertEquals("1", row("complete", "T$.b")(6))
    assertEquals("1", row("def", "T$.a")(6))
    assertEquals("2", row("def", "T$.a")(4))   // line
    assertTrue(rows.exists(r => r(0) == "unit" && r(1) == "typer" && r(2) == "test.scala"))
    // the unit is the parent of the definitions typed in it
    assertTrue(events.mkString("\n"), events.exists(e => e.startsWith("enter def:T$ in unit@typer:test.scala")))
  }

  @Test def megaPhasesHaveAFramePerDefinition(): Unit = {
    compile(
      """object M:
        |  def a(xs: List[Int]): Int = xs.map(_ + 1).sum
        |""".stripMargin, until = "erasure")
    val events = RecordingCostListener.events
    val transforms = events.filter(_.startsWith("enter transform@"))
    assertTrue(events.mkString("\n"), transforms.exists(_.contains(":M$.a in transform@")))
    // each MegaPhase's work on the unit is a unit frame of that phase
    assertTrue(events.mkString("\n"), transforms.exists(e => e.matches("enter transform@(.*):M\\$ in unit@\\1:test.scala")))
  }

  @Test def localDefinitionsArePartOfTheirOwner(): Unit = {
    compile(
      """object L:
        |  def m: Int =
        |    val x = 1
        |    def y = x + 1
        |    y
        |""".stripMargin)
    assertFalse(RecordingCostListener.events.exists(e => e.contains(".x") || e.contains(".y")))
    assertTrue(RecordingCostListener.events.contains("enter def:L$.m in def:L$"))
  }
}

object DefCostsTest:
  val table: Path = Files.createTempDirectory("def_costs_test").nn.resolve("costs.tsv").nn
