package dotty.tools.dotc.core

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import org.junit.Assert.*
import org.junit.Test

/** A lint over `compiler/src/dotty/tools/dotc/core` and `cc`: no `private var` stamps a cache beside its value (a
 *  `Period`, `RunId` or `Validity`, or named `…Period`, `…RunId`, `valid…`, `checked…`) but the exceptions below, each
 *  with its reason. With several readers a plain read of the stamp, then the value, can pair a current stamp with an
 *  old value or the default (docs/_docs/internals/interactive-multi-reader.md §4.4.1): a stamped cache keeps both in
 *  one field, a `dotty.tools.sharing.Stamped`.
 */
class StampedCachesTest:
  import StampedCachesTest.*

  @Test def noStampBesideItsValue(): Unit =
    val violations =
      for
        file <- sources
        name <- stampFields(Files.readString(file))
        if !exceptions.contains((file.getFileName.toString, name))
      yield s"${file.getFileName}: `$name` stamps a cache in a field of its own: keep the stamp and the value in a `Stamped`"
    assertTrue(violations.mkString("\n", "\n", ""), violations.isEmpty)

  @Test def exceptionsAreCurrent(): Unit =
    val present = sources.flatMap(f => stampFields(Files.readString(f)).map((f.getFileName.toString, _))).toSet
    val stale = exceptions.keySet -- present
    assertTrue(s"exceptions for fields that are gone: $stale", stale.isEmpty)

  @Test def lintSeesAStampField(): Unit =
    val text =
      """class C:
        |  private var myFooPeriod: Period = Nowhere
        |  private var myFoo: Int = 0
        |  private var myBar: Stamped[PerPeriod, Int] = Stamped.Empty
        |""".stripMargin
    assertEquals(List("myFooPeriod"), stampFields(text))

object StampedCachesTest:
  // `cc` for the capture checker's `Validity` stamps on capabilities
  private val dirs = List("core", "cc").map(d => Paths.get(s"compiler/src/dotty/tools/dotc/$d"))

  private def sources: List[Path] =
    dirs.flatMap(d => Files.list(d).iterator.asScala.filter(_.toString.endsWith(".scala"))).sortBy(_.toString)

  /** Stamp fields that are not caches, or are caches whose stamp does not pair with one value. */
  private val exceptions: Map[(String, String), String] = Map(
    ("Contexts.scala", "_period") -> "the context's period itself",
    ("Phases.scala", "myPeriod") -> "a phase's period, set once when the phases are made",
    ("Denotations.scala", "myValidFor") -> "a denotation's validity, not a cache stamp",
    ("Symbols.scala", "checkedPeriod") ->
      "a `Published` field, publishing `lastDenot` (an acquire load also on batch, measured in step 3); a hit moves the stamp without recomputing",
    ("Types.scala", "checkedPeriod") ->
      "a `Published` field, publishing `NamedType`'s `lastDenotation` and `lastSymbol`; three fields, and a hit rechecks the denotation's validity",
    ("SymDenotations.scala", "checkedPeriod") ->
      "`InheritedCacheImpl`'s memo of a validity check: a stamp without a value, a stale read only checks again",
    ("Denotations.scala", "validAsSeenFrom") ->
      "batch only: keyed by the prefix as well, so packed into one volatile value on a concurrent base",
    ("SymDenotations.scala", "packageObjsRunId") ->
      "batch only: a cycle-breaking sentinel, so packed and computed under a completion-root claim with parallel readers",
    ("Capability.scala", "captureSetValid") ->
      "the capture checker's: stored only while capture checking, a phase after typer, which the interactive compiler does not run",
    ("Capability.scala", "classifiersValid") ->
      "the capture checker's, as `captureSetValid`"
  )

  private val StampField = raw"private var (\w+)\s*:\s*(\w+)".r

  private def isStampName(name: String) =
    name.endsWith("Period") || name.endsWith("RunId") || raw"(valid|checked)[A-Z]\w*".r.matches(name)

  def stampFields(text: String): List[String] =
    StampField.findAllMatchIn(text).collect {
      case m if m.group(2) == "Period" || m.group(2) == "RunId" || m.group(2) == "Validity" || isStampName(m.group(1).nn) => m.group(1).nn
    }.toList.distinct
