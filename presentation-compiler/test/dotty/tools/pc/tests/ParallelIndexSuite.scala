package dotty.tools.pc.tests

import java.io.File
import java.net.URI

import scala.language.unsafeNulls

import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** The files of an open (`InteractiveDriver.openAll`) are indexed in parallel under `-Yinteractive-parallel`, each
 *  into a slice of its own for what it defines in a package, and the slices are merged afterwards
 *  (`Namer.IndexSlice`).
 */
class ParallelIndexSuite:

  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))

  private def file(name: String, text: String) =
    val uri = URI.create(s"file:///$name.scala")
    uri -> SourceFile.virtual(uri, text)

  private def errors(driver: InteractiveDriver, files: List[(URI, SourceFile)]): List[String] =
    for
      (uri, source) <- files
      d <- driver.withCancelChecker(() => false)(driver.readAll(uri, source)((_, _, ds) => ds))
      if d.level >= 2
    yield s"${uri.getPath}:${d.pos.line + 1}: ${d.message}"

  /** A companion and a reference across files, opened twice: the second open unlinks the first's symbols of each
   *  file (`PackageClassDenotation.unlinkFromFile`) as the files are indexed.
   */
  @Test def companionsAndReferencesAcrossFiles(): Unit =
    val files = List(
      file("A", "package p\nclass C(val i: Int)\nobject C:\n  val x = new C(1)\n"),
      file("B", "package p\nobject Use:\n  val y: Int = C.x.i\n"),
      file("D", "package p.q\nobject Deeper:\n  val z: Int = p.Use.y\n"))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    for round <- 1 to 2 do
      driver.openAll(files)
      assertEquals(s"round $round", Nil, errors(driver, files))

  /** A top-level name defined in two files of one open is reported in the second file, at the merge; the second
   *  definition is left out, so the first is the one other files see. Indexing serially, the second is reported
   *  too, and entered under a fresh name.
   */
  @Test def topLevelNameDefinedInTwoFiles(): Unit =
    val files = List(
      file("A", "package p\nclass Dup:\n  def a = 1\nobject Obj\n"),
      file("B", "package p\nclass Dup:\n  def b = 2\nobject Obj\n"),
      file("C", "package p\nobject Use:\n  val n: Int = new Dup().a\n"))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    val opened = driver.openAll(files)
    val reported = files.map((uri, _) => opened(uri).filter(_.level >= 2).map(d => s"${uri.getPath}:${d.pos.line + 1}: ${d.message}"))
    assertEquals(List(Nil,
      List("/B.scala:2: Dup is already defined as class Dup in /A.scala", "/B.scala:4: Obj is already defined as object Obj in /A.scala"),
      Nil), reported)
    assertEquals(Nil, errors(driver, files.filter(_._1 != files(1)._1)))
