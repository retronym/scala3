package toy

import scala.collection.mutable.ArrayBuffer
import Expr.*, Ty.*, Rules.*

/** Typer written once in step style, with a recursive driver below `threshold`
 *  and an explicit-stack driver above it.
 *
 *  Every child typing becomes `Need(child, pt, env, k)`; `k` is the rest of the
 *  node. Any child position can be deep, not just a designated spine child.
 */
object WorklistTyper:
  var threshold = 200
  var maxHeapDepth = 10_000_000
  private var depth = 0

  sealed trait Step
  final case class Done(t: T) extends Step
  final case class Need(child: Expr, pt: Option[Ty], env: Env, k: T => Step) extends Step

  private def step(e: Expr, pt: Option[Ty])(using env: Env): Step = e match
    case Num(_) | Str(_) | Bool(_) => Done(adapt(lit(e), pt))
    case Var(n) => Done(adapt(variable(n), pt))
    case BinOp(l, op, r) =>
      Need(l, None, env, l1 =>
      Need(r, rhsPt(l1), env, r1 =>
      Done(adapt(binop(op, l1, r1), pt))))
    case If(c, t, el) =>
      Need(c, Some(BoolT), env, c1 =>
      Need(t, pt, env, t1 =>
      Need(el, branchPt(pt, t1), env, e1 =>
      Done(adapt(mkIf(c1, t1, e1), pt)))))
    case Let(n, rhs, body) =>
      Need(rhs, None, env, rhs1 =>
      Need(body, pt, env.updated(n, rhs1.ty), body1 =>
      Done(adapt(let(n, rhs1, body1), pt))))
    case Call(f, args) =>
      def loop(as: List[Expr], ps: List[Ty], acc: List[T]): Step = as match
        case a :: as1 => Need(a, Some(ps.head), env, a1 => loop(as1, ps.tail, a1 :: acc))
        case Nil      => Done(adapt(call(f, acc.reverse), pt))
      loop(args, params(f, args.length), Nil)

  // A TypeError raised by a node's own code turns that node into an error tree
  private inline def guarded(inline s: Step): Step =
    try s catch case ex: TypeError => Done(T.err(ex.getMessage))

  def typed(e: Expr, pt: Option[Ty])(using env: Env): T =
    if depth >= threshold then typedWorklist(e, pt)
    else
      depth += 1
      try typedRec(guarded(step(e, pt)))
      finally depth -= 1

  // Recursive driver: run one node's steps, recursing for each child
  private def typedRec(s0: Step): T =
    var s = s0
    while true do
      s match
        case Done(t) => return t
        case Need(c, pt, env, k) =>
          val c1 = typed(c, pt)(using env)
          s = guarded(k(c1))
    throw AssertionError()

  // Worklist driver: the continuation stack lives on the heap; native depth is constant
  private def typedWorklist(e: Expr, pt: Option[Ty])(using env: Env): T =
    val stack = ArrayBuffer[T => Step]()
    var s = guarded(step(e, pt))
    while true do
      s match
        case Need(c, cpt, cenv, k) =>
          if stack.length >= maxHeapDepth then throw RecursionOverflow()
          stack += k
          s = guarded(step(c, cpt)(using cenv))
        case Done(t) =>
          if stack.isEmpty then return t
          val k = stack.remove(stack.length - 1)
          s = guarded(k(t))
    throw AssertionError()
