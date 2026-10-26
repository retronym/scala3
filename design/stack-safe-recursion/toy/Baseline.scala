package toy

import Expr.*, Ty.*, Rules.*

/** Direct-style recursive typer: the shape of dotc's Typer today. */
object Baseline:
  def typed(e: Expr, pt: Option[Ty])(using env: Env): T =
    try adapt(typedUnadapted(e, pt), pt)
    catch case ex: TypeError => T.err(ex.getMessage)

  private def typedUnadapted(e: Expr, pt: Option[Ty])(using env: Env): T = e match
    case Num(_) | Str(_) | Bool(_) => lit(e)
    case Var(n) => variable(n)
    case BinOp(l, op, r) =>
      val l1 = typed(l, None)
      val r1 = typed(r, rhsPt(l1))
      binop(op, l1, r1)
    case If(c, t, el) =>
      val c1 = typed(c, Some(BoolT))
      val t1 = typed(t, pt)
      val e1 = typed(el, branchPt(pt, t1))
      mkIf(c1, t1, e1)
    case Let(n, rhs, body) =>
      val rhs1 = typed(rhs, None)
      val body1 = typed(body, pt)(using env.updated(n, rhs1.ty))
      let(n, rhs1, body1)
    case Call(f, args) =>
      val ps = params(f, args.length)
      call(f, args.lazyZip(ps).map((a, p) => typed(a, Some(p))))
