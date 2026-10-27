package dotty.tools
package dotc

import core.Contexts.Context

// The givens of `dotty.tools.sharing`'s operations, from the context's base. Top-level in package `dotc`, so that code
// in a package nested in it (`package dotty.tools; package dotc; package core`) finds them with no import. Each is
// inlined: a call site's mode test reads `ctx.base.mode`, a final field, as it read `ctx.base.concurrent` before,
// and the registry is read only in the concurrent branch.

/** The base's mode (`ContextState.mode`). */
inline given sharingMode(using c: Context): _root_.dotty.tools.sharing.Mode = c.base.mode

/** The base's claim registry (`ContextState.claimRegistry`): the completion roots or the completion lock. */
inline given claimRegistry(using c: Context): _root_.dotty.tools.sharing.ClaimRegistry = c.base.claimRegistry

// The stamps of `dotty.tools.sharing.Stamped` caches: a cache valid for one run or for one period, its kind on the
// field's type (`Stamped[PerRun, T]`), and its current stamp from the context.

/** A cache valid for one run: stamped with the run id. */
sealed trait PerRun

/** A cache valid for one period: stamped with the period's `rawCode`. */
sealed trait PerPeriod

inline given runStamp(using c: Context): _root_.dotty.tools.sharing.Stamp[PerRun] =
  _root_.dotty.tools.sharing.Stamp(c.runId)

inline given periodStamp(using c: Context): _root_.dotty.tools.sharing.Stamp[PerPeriod] =
  _root_.dotty.tools.sharing.Stamp(c.period.rawCode)
