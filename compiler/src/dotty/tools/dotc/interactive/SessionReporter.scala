package dotty.tools
package dotc
package interactive

import scala.collection.mutable

import core.Contexts.*
import reporting.*
import util.SourceFile
import util.Spans.Span

/** The reporter of an interactive run: every reader reports to it, a
 *  completer through its creation context. It keeps each diagnostic under
 *  the provenance it was issued with (`Reader.provenance`; the run's when there
 *  is none, which is every diagnostic without `-Yinteractive-body-edits`),
 *  and publishes the union of the stores in arrival order. An edit drops
 *  the stores of what it invalidates and maps the positions of the rest
 *  (docs/_docs/internals/interactive-session.md §12, "Diagnostics").
 *  Nothing is buffered: a session reads the union.
 */
private[interactive] final class SessionReporter extends StoreReporter(null) with UniqueMessagePositions with HideNonSensicalMessages:

  private final class Entry(val seq: Long, val dia: Diagnostic)

  private var seq = 0L
  private val stores = mutable.LinkedHashMap.empty[Provenance, mutable.ArrayBuffer[Entry]]

  /** The dropped stores of statements not yet typed again: published, with
   *  their positions mapped through the edits since, until the statement is.
   */
  private val stale = mutable.LinkedHashMap.empty[Provenance, mutable.ArrayBuffer[Entry]]

  /** The run's summaries, not stored: for the run's own result only. */
  private var summaries: List[Diagnostic] = Nil

  private var published: List[Diagnostic] | Null = null

  override def report(dia: Diagnostic)(using Context): Unit = synchronized(super.report(dia))

  // under the provenance it was first issued with (a typer state's buffer,
  // flushed when the state is committed, or a warning suspended until the
  // file's `@nowarn`s are known, keeps it)
  override def doReport(dia: Diagnostic)(using Context): Unit = synchronized {
    // the run's summary is reported here directly, untagged
    (if dia.provenance != null then dia.provenance else ctx.reader.provenance) match
      case Provenance.Summary => summaries = summaries :+ dia
      case null => add(Provenance.Run, dia)
      case p: Provenance => add(p, dia)
  }

  private def add(p: Provenance, dia: Diagnostic): Unit =
    seq += 1
    stores.getOrElseUpdate(p, mutable.ArrayBuffer.empty) += Entry(seq, dia)
    published = null

  override def removeBufferedMessages(using Context): List[Diagnostic] = Nil
  override def pendingMessages(using Context): List[Diagnostic] = Nil

  /** What a run reports: the union, and its summary. */
  def runResult: List[Diagnostic] = synchronized(all ++ summaries)

  /** The union of the stores, stale ones included, in arrival order. */
  def all: List[Diagnostic] = synchronized {
    if published == null then
      published = (stores.valuesIterator ++ stale.valuesIterator).flatten.toList.sortBy(_.seq).map(_.dia)
    published.nn
  }

  /** With several files on the run (`openAll`): the diagnostics of the
   *  session on `source`, those positioned in it and, for `takesNopos`,
   *  those without a position.
   */
  def of(source: SourceFile, takesNopos: Boolean): List[Diagnostic] =
    all.filter(dia => if dia.pos.exists then dia.pos.source.path == source.path else takesNopos)

  /** An edit of `source`: the stores of `dropped` go, those of `toStale`
   *  (some of them) become stale; the parse's store becomes `parse` (the
   *  new parse's syntax errors), and the old parse's, `oldParse`, leave the
   *  run's store, where the first run reported them; every position in
   *  `source` is mapped by `span`, and a stale entry the edit's region
   *  touches goes. The memory of the positions reported is rebuilt from
   *  what is published and not stale.
   */
  def edit(source: SourceFile, dropped: Set[Provenance], toStale: Set[Provenance], oldParse: List[Diagnostic],
      parse: List[Diagnostic], touched: Span => Boolean, span: Span => Span): Unit = synchronized {
    for p <- dropped; es <- stores.remove(p) do
      if toStale(p) then stale.getOrElseUpdate(p, mutable.ArrayBuffer.empty) ++= es
    stores.get(Provenance.Run).foreach { es =>
      def key(dia: Diagnostic) = (dia.pos.span, dia.msg.message)
      // the old parse's are of a source of their own, with the old content
      val old = oldParse.map(key).toSet
      if old.nonEmpty then es.filterInPlace(e => !((e.dia.pos.source eq source) && old(key(e.dia))))
    }
    stores.remove(Provenance.Parse)
    def inSource(e: Entry) = e.dia.pos.exists && (e.dia.pos.source eq source)
    def map(es: mutable.ArrayBuffer[Entry]) =
      es.mapInPlace(e => if inSource(e) then Entry(e.seq, SessionReporter.moved(e.dia, span(e.dia.pos.span))) else e)
    stale.valuesIterator.foreach { es =>
      es.filterInPlace(e => !(inSource(e) && touched(e.dia.pos.span)))
      map(es)
    }
    stores.valuesIterator.foreach(map)
    parse.foreach(add(Provenance.Parse, _))
    forgetPositions(source)
    for es <- stores.valuesIterator; e <- es if inSource(e) do rememberPositions(source, e.dia)
    published = null
  }

  /** The stores of `dropped` go (an aborted statement's), and their
   *  positions are forgotten.
   */
  def drop(source: SourceFile, dropped: Set[Provenance]): Unit = synchronized {
    if dropped.exists(stores.contains) then
      dropped.foreach(stores.remove)
      forgetPositions(source)
      for es <- stores.valuesIterator; e <- es if e.dia.pos.exists && (e.dia.pos.source eq source) do rememberPositions(source, e.dia)
      published = null
  }

  /** The statement of `p` is typed again: its stale diagnostics go. */
  def retyped(p: Provenance): Unit = synchronized {
    if stale.remove(p).isDefined then published = null
  }

  /** For the x-ray and the tests: each diagnostic with its provenance
   *  (`stale ` before a stale one's), in arrival order.
   */
  def byProvenance: List[(String, Diagnostic)] = synchronized {
    (stores.iterator.flatMap((p, es) => es.map(e => (e.seq, p.toString, e.dia)))
      ++ stale.iterator.flatMap((p, es) => es.map(e => (e.seq, s"stale $p", e.dia)))).toList.sortBy(_._1).map(t => (t._2, t._3))
  }

  /** The stale diagnostics. */
  def staleDiagnostics: List[Diagnostic] = synchronized(stale.valuesIterator.flatten.map(_.dia).toList)

object SessionReporter:
  /** `dia` at `span` of its source; a warning's subclass (a feature or a
   *  deprecation warning) is not kept.
   */
  def moved(dia: Diagnostic, span: Span): Diagnostic =
    val pos = dia.pos.withSpan(span)
    dia match
      case e: Diagnostic.Error => Diagnostic.Error(e.msg, pos)
      case w: Diagnostic.Warning => Diagnostic.Warning(w.msg, pos)
      case i: Diagnostic.Info => Diagnostic.Info(i.msg, pos)
      case _ => Diagnostic(dia.msg, pos, dia.level)
