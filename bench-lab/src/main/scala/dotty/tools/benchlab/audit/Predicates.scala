package dotty.tools.benchlab.audit

import dotty.tools.dotc.ast.{Positioned, Trees}

/** Predicates for audit specs (`when=dotty.tools.benchlab.audit.Predicates#<name>`): `static boolean (Object)` on the
 *  probe's receiver. Compiled against the compiler, so they can use its types (e.g. the opaque `Span`).
 */
object Predicates:
  /** A tree created without a span: one whose span an enclosing node's `envelope` may fill in later. */
  def spanless(o: Object): Boolean = o match
    case t: Trees.Tree[?] => !t.span.exists
    case _ => false

  /** A positioned item, tree or not, without a span. */
  def positionedSpanless(o: Object): Boolean = o match
    case p: Positioned => !p.span.exists
    case _ => false
