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
