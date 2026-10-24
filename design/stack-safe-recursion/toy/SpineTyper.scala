package toy

import scala.collection.mutable.ArrayBuffer
import Expr.*, Ty.*, Rules.*

/** Direct-style typer plus a spine loop past `threshold`.
 *
 *  A spine node has one designated deep child (BinOp.l, If.e, Let.body) whose
 *  prototype and environment are known before it is typed. The node splits into
 *  a pre half (`spineStep`: type shallow siblings, compute the child's pt/env)
 *  and a post half (`finish`). Both drivers use the same split.
 */
object SpineTyper:
  var threshold = 200
  private var depth = 0

  abstract class SpineStep:
    def child: Expr
    def childPt: Option[Ty]
    def childEnv: Env
    def finish(child1: T): T

  def typed(e: Expr, pt: Option[Ty])(using env: Env): T =
    if depth >= threshold then typedSpine(e, pt)
    else
      depth += 1
      try typedOne(e, pt)
      finally depth -= 1

  // Recursive driver: pre; recurse into the spine child; post
  private def typedOne(e: Expr, pt: Option[Ty])(using env: Env): T = guard:
    spineStep(e, pt) match
      case null => adapt(typedOther(e), pt)
      case s    => s.finish(typed(s.child, s.childPt)(using s.childEnv))

  // Loop driver: descend running pre halves, type the innermost node, ascend running post halves
  private def typedSpine(e0: Expr, pt0: Option[Ty])(using env0: Env): T =
    val stack = ArrayBuffer[SpineStep]()
    var e = e0; var pt = pt0; var env = env0
    var innermost: T | Null = null
    while innermost == null do
      try
        spineStep(e, pt)(using env) match
          case null => innermost = guard(adapt(typedOther(e)(using env), pt))
          case s    => stack += s; e = s.child; pt = s.childPt; env = s.childEnv
      catch case ex: TypeError => innermost = T.err(ex.getMessage)
    var res = innermost.nn
    while stack.nonEmpty do
      val s = stack.remove(stack.length - 1)
      res = guard(s.finish(res))
    res

  private def guard(body: => T): T =
    try body catch case ex: TypeError => T.err(ex.getMessage)

  private def spineStep(e: Expr, pt: Option[Ty])(using env: Env): SpineStep | Null = e match
    case BinOp(l, op, r) =>
      new SpineStep:
        def child = l
        def childPt = None
        def childEnv = env
        def finish(l1: T) = adapt(binop(op, l1, typed(r, rhsPt(l1))), pt)
    case If(c, t, el) =>
      val c1 = typed(c, Some(BoolT))
      val t1 = typed(t, pt)
      new SpineStep:
        def child = el
        def childPt = branchPt(pt, t1)
        def childEnv = env
        def finish(e1: T) = adapt(mkIf(c1, t1, e1), pt)
    case Let(n, rhs, body) =>
      val rhs1 = typed(rhs, None)
      new SpineStep:
        def child = body
        def childPt = pt
        def childEnv = env.updated(n, rhs1.ty)
        def finish(body1: T) = adapt(let(n, rhs1, body1), pt)
    case _ => null

  // Non-spine kinds: plain recursion for their children
  private def typedOther(e: Expr)(using env: Env): T = e match
    case Num(_) | Str(_) | Bool(_) => lit(e)
    case Var(n) => variable(n)
    case Call(f, args) =>
      val ps = params(f, args.length)
      call(f, args.lazyZip(ps).map((a, p) => typed(a, Some(p))))
    case _ => throw IllegalStateException(e.productPrefix)
