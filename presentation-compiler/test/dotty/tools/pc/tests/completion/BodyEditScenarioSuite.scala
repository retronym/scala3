package dotty.tools.pc.tests.completion

import java.net.URI
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.language.unsafeNulls
import scala.util.Random

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.parsing.{Scanners, Tokens}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

import org.junit.{Assert, Test}

/** A seeded editor scenario of edits inside method bodies on `Types.scala`,
 *  in the event format of `QueueReplaySuite` (`trace_to_events.py`): open
 *  the file, hover, document highlight; then ten rounds of an edit inside the body of a method
 *  with a declared result type (an integer literal gains a digit, which is
 *  what typing a few characters into an expression looks like to the
 *  driver), a hover elsewhere, a hover inside the edited body, and a
 *  document highlight (the whole-file request VS Code sends on every cursor
 *  move); and two control rounds that must take a fresh run: an edit of a
 *  signature (a parameter renamed) and an edit inside a method whose result
 *  type is inferred. Docs §12.
 *
 *  Each `didOpen`/`didChange` carries the whole text, as the format has it,
 *  so the sequence is some megabytes: it is generated from the checkout's
 *  `Types.scala`, not committed. `body-edit-scenario` replays it on the
 *  driver, with and without `-Yinteractive-body-edits`, checks what each
 *  edit did, prints the timings, and writes the events to
 *  `local/body-edit-scenario.json` for `QueueReplaySuite` and the x-ray.
 */
class BodyEditScenarioSuite:

  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0",
      "-classpath", sys.props.getOrElse("java.class.path", ""))

  private val repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("compiler/src/dotty/tools/dotc"))).get

  private val typesPath = repoRoot.resolve("compiler/src/dotty/tools/dotc/core/Types.scala")

  /** One event of the replay format. */
  case class Event(t: Double, kind: String, id: String = null, text: String = null, offset: Int = -1,
      /** what the edit must do: "kept" or "fresh" (not part of the format) */
      expect: String = null, label: String = null):
    def json: String =
      def str(s: String) =
        val sb = new StringBuilder("\"")
        s.foreach {
          case '"' => sb ++= "\\\""
          case '\\' => sb ++= "\\\\"
          case '\n' => sb ++= "\\n"
          case '\r' => sb ++= "\\r"
          case '\t' => sb ++= "\\t"
          case c if c < ' ' => sb ++= f"\\u${c.toInt}%04x"
          case c => sb += c
        }
        sb += '"'
        sb.toString
      val fields = List(
        Some(s"\"t\": $t"), Some(s"\"kind\": ${str(kind)}"), Some(s"\"uri\": ${str(typesPath.toUri.toString)}"),
        Option(id).map(i => s"\"id\": ${str(i)}"), Option(text).map(x => s"\"text\": ${str(x)}"),
        Option.when(offset >= 0)(s"\"offset\": $offset"),
        Option(expect).map(x => s"\"expect\": ${str(x)}"), Option(label).map(x => s"\"label\": ${str(x)}"))
      fields.flatten.mkString("{", ", ", "}")

  /** A member definition of the file: its name, whether its result type is
   *  declared, the span of its parameters' names, its right-hand side.
   */
  private case class Member(name: String, declared: Boolean, inline: Boolean, params: List[Span], rhs: Span)

  private def members(driver: InteractiveDriver, uri: URI): List[Member] =
    given Context = driver.currentCtx
    val out = mutable.ListBuffer.empty[Member]
    def stats(ts: List[tpd.Tree]): Unit = ts.foreach {
      case p: tpd.PackageDef => stats(p.stats)
      case t: tpd.TypeDef => t.rhs match
        case impl: tpd.Template => stats(impl.body)
        case _ =>
      case d: tpd.DefDef if d.span.exists && !d.span.isSynthetic && d.rhs.span.exists && !d.rhs.span.isZeroExtent =>
        val declared = d.tpt.span.exists && !d.tpt.span.isSynthetic
        val params = d.termParamss.flatten.map(_.nameSpan).filter(sp => sp.exists && !sp.isSynthetic)
        out += Member(d.name.toString, declared, d.symbol.is(dotty.tools.dotc.core.Flags.Inline), params, d.rhs.span)
      case _ =>
    }
    driver.compilationUnits.get(uri).foreach(u => stats(u.tpdTree :: Nil))
    out.toList

  /** (offset, is an identifier, is a short decimal literal) for each token. */
  private def tokens(content: String, ctx: Context): Vector[(Int, Boolean, Boolean)] =
    val scanner = new Scanners.Scanner(SourceFile.virtual(typesPath.toUri, content))(using ctx)
    val out = mutable.ArrayBuffer.empty[(Int, Boolean, Boolean)]
    while scanner.token != Tokens.EOF do
      val o = scanner.offset
      val digits = content.substring(o).takeWhile(_.isLetterOrDigit)
      out += ((o, scanner.isIdent, scanner.token == Tokens.INTLIT && digits.nonEmpty && digits.forall(_.isDigit) && digits.length < 5))
      scanner.nextToken()
    out.toVector

  /** The scenario, generated from the checkout's `Types.scala` with `seed`. */
  def scenario(seed: Int = 1, rounds: Int = 10): List[Event] =
    val uri = typesPath.toUri
    var content = Files.readString(typesPath)
    val rng = new Random(seed)
    val events = mutable.ListBuffer.empty[Event]
    var t = 1.0
    var id = 0
    def next(kind: String, offset: Int, label: String = null) =
      id += 1
      events += Event(t, kind, id = id.toString, offset = offset, label = label)
      t += 0.5
    def change(now: String, expect: String, label: String) =
      content = now
      events += Event(t, "didChange", text = content, expect = expect, label = label)
      t += 0.5
    def driverOn(c: String) =
      val d = new InteractiveDriver(settings, CachedLogicalPackage.none)
      d.run(uri, SourceFile.virtual(uri, c))
      d
    def identIn(span: Span, ctx: Context): Int =
      val ids = tokens(content, ctx).collect { case (o, true, _) if span.contains(Span(o)) => o }
      ids(rng.nextInt(ids.size)) + 1
    /** An identifier in the body of one of `among`, chosen at random. */
    def identAmong(among: List[Member], ctx: Context): Int =
      val toks = tokens(content, ctx)
      val withIdents = among.filter(m => toks.exists((o, isId, _) => isId && m.rhs.contains(Span(o))))
      identIn(withIdents(rng.nextInt(withIdents.size)).rhs, ctx)
    def literalIn(m: Member, ctx: Context): Option[Int] =
      val ls = tokens(content, ctx).collect { case (o, _, true) if m.rhs.contains(Span(o)) => o }
      if ls.isEmpty then None else Some(ls(rng.nextInt(ls.size)))
    def bump(o: Int): String =
      val digits = content.substring(o).takeWhile(_.isDigit)
      content.substring(0, o) + (if digits == "0" then "10" else digits + "1") + content.substring(o + digits.length)

    events += Event(t, "didOpen", text = content); t += 0.5
    var driver = driverOn(content)
    var ms = members(driver, uri)
    next("hover", identAmong(ms, driver.currentCtx), "first hover")
    // what VS Code asks on opening a file and moving the cursor: the whole file
    next("documentHighlight", identAmong(ms, driver.currentCtx), "first document highlight")
    val used = mutable.Set.empty[String]
    var done = 0
    while done < rounds do
      val m = ms(rng.nextInt(ms.size))
      if m.declared && !m.inline && !used(m.name) then
        literalIn(m, driver.currentCtx).foreach { lit =>
          used += m.name
          done += 1
          change(bump(lit), "kept", s"edit inside ${m.name}")
          driver = driverOn(content)
          ms = members(driver, uri)
          val edited = ms.find(x => x.name == m.name && x.rhs.contains(Span(lit))).get
          val elsewhere = ms.filter(x => !x.rhs.contains(edited.rhs) && x.rhs.end - x.rhs.start > 20)
          next("hover", identAmong(elsewhere, driver.currentCtx), s"hover elsewhere after editing ${m.name}")
          next("hover", identIn(edited.rhs, driver.currentCtx), s"hover inside ${m.name}")
          next("documentHighlight", identIn(edited.rhs, driver.currentCtx), s"document highlight in ${m.name}")
        }
    // control 1: a signature edit, a parameter of a method with a declared type renamed
    locally {
      val m = ms.filter(x => x.declared && x.params.nonEmpty && !used(x.name))(rng.nextInt(10))
      val p = m.params.head
      change(content.substring(0, p.end) + "0" + content.substring(p.end), "fresh", s"signature edit: a parameter of ${m.name} renamed")
      driver = driverOn(content)
      ms = members(driver, uri)
      next("hover", identAmong(ms.filter(_.name != m.name), driver.currentCtx), s"hover after the signature edit of ${m.name}")
      next("documentHighlight", identAmong(ms, driver.currentCtx), "document highlight after the signature edit")
    }
    // control 2: an edit inside a method whose result type is inferred
    locally {
      val toks = tokens(content, driver.currentCtx)
      val candidates = ms.filter(x => !x.declared && !x.inline && literalIn(x, driver.currentCtx).isDefined
        && toks.exists((o, isId, _) => isId && x.rhs.contains(Span(o))))
      val m = candidates(rng.nextInt(candidates.size))
      change(bump(literalIn(m, driver.currentCtx).get), "fresh", s"edit inside ${m.name}, whose result type is inferred")
      driver = driverOn(content)
      ms = members(driver, uri)
      next("hover", identAmong(ms.filter(_.name == m.name), driver.currentCtx), s"hover inside ${m.name} (inferred)")
      next("documentHighlight", identAmong(ms, driver.currentCtx), "document highlight after the inferred-type edit")
    }
    events.toList

  private def ms(t0: Long) = (System.nanoTime() - t0) / 1e6

  /** Replays `events` on one driver: hover is `runUpTo`, document highlight
   *  `run`. Returns per event (label, kind, ms, what the edit did, whether the
   *  run was kept).
   */
  private def replay(events: List[Event], bodyEdits: Boolean): List[(Event, Double, String, Boolean)] =
    val uri = typesPath.toUri
    val driver = new InteractiveDriver(if bodyEdits then settings :+ "-Yinteractive-body-edits" else settings, CachedLogicalPackage.none)
    var text: String = null
    var pendingEdit = false
    var runId = -1
    events.flatMap { e =>
      e.kind match
        case "didOpen" | "didChange" =>
          text = e.text; pendingEdit = e.kind == "didChange"; Nil
        case kind =>
          val src = SourceFile.virtual(uri, text)
          val t0 = System.nanoTime()
          if kind == "documentHighlight" then driver.run(uri, src) else driver.runUpTo(uri, src, Span(e.offset))
          val took = ms(t0)
          val kept = driver.runIdOf(uri) == runId
          runId = driver.runIdOf(uri)
          val edit = if pendingEdit then driver.lastEdit(uri).replaceAll(", shifted.*", "") else ""
          pendingEdit = false
          List((e, took, edit, kept))
    }

  @Test def `body-edit-scenario` =
    val events = scenario()
    val local = repoRoot.resolve("local")
    if Files.isDirectory(local) then
      Files.writeString(local.resolve("body-edit-scenario.json"), events.map(_.json).mkString("[\n", ",\n", "\n]\n"))
    // warm up, then measure each configuration
    replay(events, bodyEdits = false); replay(events, bodyEdits = true)
    val off = replay(events, bodyEdits = false)
    val on = replay(events, bodyEdits = true)
    // what each edit must do, with the flag
    val expects = events.zipWithIndex.collect { case (e, i) if e.expect != null => (e, events.drop(i + 1).find(_.id != null).get) }
    for (change, firstRequest) <- expects do
      val (_, _, edit, kept) = on.find(_._1 eq firstRequest).get
      println(f"[scenario] ${change.label}%-70s -> $edit")
      if change.expect == "kept" then Assert.assertTrue(s"${change.label}: $edit", kept && edit.startsWith("body edit"))
      else Assert.assertTrue(s"${change.label}: $edit", !kept && edit.startsWith("edit refused"))
    def summary(rows: List[(Event, Double, String, Boolean)], kind: String, label: String => Boolean) =
      val xs = rows.collect { case (e, t, _, _) if e.kind == kind && e.label != null && label(e.label) => t }.sorted
      f"median ${xs(xs.size / 2)}%.1f max ${xs.last}%.1f total ${xs.sum}%.0f (${xs.size})"
    for (name, kind, label) <- List[(String, String, String => Boolean)](
        ("hover elsewhere after a body edit", "hover", _.startsWith("hover elsewhere")),
        ("hover inside the edited body", "hover", l => l.startsWith("hover inside") && !l.contains("inferred")),
        ("document highlight after a body edit", "documentHighlight", _.startsWith("document highlight in")),
        ("controls: hover", "hover", l => l.contains("signature edit") || l.contains("inferred")),
        ("controls: document highlight", "documentHighlight", _.contains(" after the ")))
    do println(s"[scenario] $name: off ${summary(off, kind, label)} | on ${summary(on, kind, label)}")
