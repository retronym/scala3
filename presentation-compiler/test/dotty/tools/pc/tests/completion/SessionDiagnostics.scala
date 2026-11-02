package dotty.tools.pc.tests.completion

import dotty.tools.dotc.reporting.Diagnostic

/** The session's diagnostic set against a fresh whole-file run's, the
 *  invariant of body edits (docs §12, "Diagnostics").
 */
object SessionDiagnostics:

  /** The diagnostics as a multiset: level, position and the whole message;
   *  without the run's summary ("1 error found"), a count of the run, not a
   *  fact of the file.
   */
  def of(diags: List[Diagnostic]): String =
    def isSummary(d: Diagnostic) =
      !d.pos.exists && d.msg.message.linesIterator.forall(_.matches("""\d+ (errors?|warnings?) found"""))
    diags.filterNot(isSummary).map { d =>
      val at = if d.pos.exists then s"[${d.pos.start},${d.pos.end})" else "<no position>"
      s"${d.level} $at ${d.msg.message}"
    }.sorted.mkString("\n")
