package dotty.tools
package dotc
package reporting

import core.Symbols.Symbol

/** In interactive mode, under `-Yinteractive-body-edits`: the fact whose
 *  computation reported a diagnostic or registered a `@nowarn`, so that an
 *  edit drops what the edited definition reported, wherever it is positioned,
 *  and keeps the rest (docs/_docs/internals/interactive-session.md §12,
 *  "Diagnostics"). The current one is `Reader.provenance`.
 */
enum Provenance:
  /** The typing of a member definition statement (owned by a class or a package). */
  case Stat(sym: Symbol)
  /** A Namer completion of a member, a class or an import. */
  case Completion(sym: Symbol)
  /** The syntax errors of the current parse of the file. */
  case Parse
  /** What no statement or completion reported. */
  case Run
  /** The run's summary ("1 error found"): a count of the run, not a fact of the file. */
  case Summary
