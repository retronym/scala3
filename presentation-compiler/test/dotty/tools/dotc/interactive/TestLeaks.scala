package dotty.tools.dotc.interactive

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.SymDenotations.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

/** Test access to compiler-private state (`info_=` is `protected[dotc]`). */
object TestLeaks:
  /** Leave `sym` as an exception unwinding through its completer would have
   *  before `unwindsInteractive`: `Touched` set, info a completer again.
   */
  def leaveTouched(sym: Symbol)(using Context): Unit =
    sym.denot.info = new LazyType:
      def complete(denot: SymDenotation)(using Context, dotty.tools.dotc.core.Sharing.Holds[denot.type]): Unit = denot.info = defn.IntType
    sym.setFlag(Flags.Touched)
