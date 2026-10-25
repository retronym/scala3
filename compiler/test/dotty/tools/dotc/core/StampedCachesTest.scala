package dotty.tools.dotc.core

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import org.junit.Assert.*
import org.junit.Test

/** A lint over `compiler/src/dotty/tools/dotc/core`: a `private var` that stamps a cache (a `Period` or `RunId`, or
 *  named `…Period`, `…RunId`, `valid…`, `checked…`) is read and written only in the stamp and setter arguments of a
 *  `Caching` helper, or reset (`= Nowhere`, `= NoRunId`). With several readers a plain read of the stamp, then the
 *  value, can pair a current stamp with an old value or the default (docs/_docs/internals/interactive-multi-reader.md
 *  §4.4.1). The exceptions below say why each is one.
 */
class StampedCachesTest:
  import StampedCachesTest.*

  @Test def stampsOnlyThroughCaching(): Unit =
    val violations =
      for
        file <- sources
        text = Files.readString(file)
        name <- stampFields(text)
        if !exceptions.contains((file.getFileName.toString, name))
        line <- strayUses(text, name)
      yield s"${file.getFileName}:$line: `$name` used outside a `Caching` helper"
    assertTrue(violations.mkString("\n", "\n", ""), violations.isEmpty)

  @Test def exceptionsAreCurrent(): Unit =
    val present = sources.flatMap(f => stampFields(Files.readString(f)).map((f.getFileName.toString, _))).toSet
    val stale = exceptions.keySet -- present
    assertTrue(s"exceptions for fields that are gone: $stale", stale.isEmpty)

  @Test def lintSeesAStrayRead(): Unit =
    val text =
      """class C:
        |  private var myFooPeriod: Period = Nowhere
        |  private var myFoo: Int = 0
        |  def foo(using Context) =
        |    Caching.stamped(myFooPeriod, myFoo)(myFooPeriod = _, myFoo = _)(ctx.period):
        |      42
        |  def reset() = myFooPeriod = Nowhere
        |  def peek(using Context) = if myFooPeriod == ctx.period then myFoo else 0
        |""".stripMargin
    assertEquals(List("myFooPeriod"), stampFields(text))
    assertEquals(List(8), strayUses(text, "myFooPeriod"))

object StampedCachesTest:
  private val coreDir = Paths.get("compiler/src/dotty/tools/dotc/core")

  private def sources: List[Path] =
    Files.list(coreDir).iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sortBy(_.toString)

  /** Stamp fields that are not caches, or are caches that are not two plain fields kept through `Caching`. */
  private val exceptions: Map[(String, String), String] = Map(
    ("Contexts.scala", "_period") -> "the context's period itself",
    ("Phases.scala", "myPeriod") -> "a phase's period, set once when the phases are made",
    ("Denotations.scala", "myValidFor") -> "a denotation's validity, not a cache stamp",
    ("Symbols.scala", "checkedPeriod") ->
      "publishes `lastDenot` through `util.Publish` (an acquire load also on batch, measured in step 3); its reentrant plain store first does not fit the helper",
    ("Types.scala", "checkedPeriod") ->
      "publishes `NamedType`'s `lastDenotation` and `lastSymbol` through `util.Publish`; three fields, and a hit rechecks the denotation's validity",
    ("SymDenotations.scala", "checkedPeriod") ->
      "`InheritedCacheImpl`'s memo of a validity check: a stamp without a value, a stale read only checks again",
    ("Denotations.scala", "validAsSeenFrom") ->
      "batch only: keyed by the prefix as well, so packed into one volatile value on a concurrent base",
    ("SymDenotations.scala", "packageObjsRunId") ->
      "batch only: a cycle-breaking sentinel, so packed and computed under a completion-root claim with parallel readers",
  )

  private val StampField = raw"private var (\w+)\s*:\s*(\w+)".r

  private def isStampName(name: String) =
    name.endsWith("Period") || name.endsWith("RunId") || raw"(valid|checked)[A-Z]\w*".r.matches(name)

  def stampFields(text: String): List[String] =
    StampField.findAllMatchIn(text).collect {
      case m if m.group(2) == "Period" || m.group(2) == "RunId" || isStampName(m.group(1).nn) => m.group(1).nn
    }.toList.distinct

  /** The extents of the argument lists of each `Caching.<helper>` call before its block: the stamp, the value, their
   *  setters and the validity test.
   */
  private def cachingArguments(text: String): List[(Int, Int)] =
    raw"Caching\.\w+\(".r.findAllMatchIn(text).map { m =>
      var i = m.end - 1
      var continue = true
      while continue do
        var depth = 0
        var j = i
        while
          text(j) match
            case '(' => depth += 1
            case ')' => depth -= 1
            case _ =>
          j += 1
          depth > 0
        do ()
        i = j
        continue = i < text.length && text(i) == '('
      (m.start, i)
    }.toList

  /** The lines with a use of `name` that is not its declaration, a reset, or in a `Caching` call's arguments. */
  def strayUses(text: String, name: String): List[Int] =
    val inCaching = cachingArguments(text)
    val Reset = raw"\s*=\s*(Nowhere|NoRunId)\b".r
    raw"\b${java.util.regex.Pattern.quote(name)}\b".r.findAllMatchIn(text).filterNot { m =>
      val before = text.substring(text.lastIndexOf('\n', m.start) + 1, m.start)
      before.trim.endsWith("private var")
        || Reset.findPrefixOf(text.substring(m.end)).isDefined
        || inCaching.exists((from, to) => from <= m.start && m.end <= to)
        || before.contains("//") || before.trim.startsWith("*") || before.trim.startsWith("/*")
    }.map(m => text.substring(0, m.start).count(_ == '\n') + 1).toList.distinct
