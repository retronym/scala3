//> using scala 3.7.4
//> using jvm 25

package toy

import scala.collection.mutable

enum Ty:
  case IntT, StrT, BoolT, ErrT
  // ErrT conforms both ways to avoid cascading errors
  def conforms(pt: Option[Ty]): Boolean = this == ErrT || pt.forall(p => p == this || p == ErrT)

enum Expr:
  case Num(n: Int)
  case Str(s: String)
  case Bool(b: Boolean)
  case Var(name: String)
  case BinOp(l: Expr, op: String, r: Expr)
  case If(c: Expr, t: Expr, e: Expr)
  case Let(name: String, rhs: Expr, body: Expr)
  case Call(f: String, args: List[Expr])

/** Typed tree. A plain class: no recursive equals/hashCode/toString to blow the stack. */
final class T(val label: String, val ty: Ty, val kids: List[T])
object T:
  def err(msg: String): T = T(s"<error: $msg>", Ty.ErrT, Nil)

final class TypeError(msg: String) extends Exception(msg, null, false, false)
final class RecursionOverflow extends Exception("heap recursion limit", null, false, false)

type Env = Map[String, Ty]

/** The typing rules, shared by all three typers. Only the traversal differs. */
object Rules:
  import Ty.*

  def adapt(t: T, pt: Option[Ty]): T =
    if t.ty.conforms(pt) then t else throw TypeError(s"expected ${pt.get}, found ${t.ty}")

  def lit(e: Expr): T = e match
    case Expr.Num(n)  => T(n.toString, IntT, Nil)
    case Expr.Str(s)  => T(s"\"$s\"", StrT, Nil)
    case Expr.Bool(b) => T(b.toString, BoolT, Nil)
    case _            => throw IllegalArgumentException(e.productPrefix)

  def variable(name: String)(using env: Env): T = env.get(name) match
    case Some(ty) => T(name, ty, Nil)
    case None     => throw TypeError(s"not found: $name")

  def rhsPt(l1: T): Option[Ty] = Some(l1.ty)

  def binop(op: String, l1: T, r1: T): T = op match
    case "+" if l1.ty != BoolT   => T("+", l1.ty, List(l1, r1))
    case "<" if l1.ty == IntT || l1.ty == ErrT => T("<", BoolT, List(l1, r1))
    case _                       => throw TypeError(s"no $op on ${l1.ty}")

  def branchPt(pt: Option[Ty], t1: T): Option[Ty] = pt.orElse(Some(t1.ty))

  def mkIf(c1: T, t1: T, e1: T): T = T("if", if t1.ty == ErrT then e1.ty else t1.ty, List(c1, t1, e1))

  def let(name: String, rhs1: T, body1: T): T = T(s"let $name", body1.ty, List(rhs1, body1))

  def params(f: String, nargs: Int): List[Ty] =
    val ps = f match
      case "max" => List(IntT, IntT)
      case "str" => List(IntT)
      case "len" => List(StrT)
      case _     => throw TypeError(s"unknown function $f")
    if ps.length != nargs then throw TypeError(s"$f takes ${ps.length} args") else ps

  def call(f: String, args1: List[T]): T =
    T(f, f match { case "str" => StrT; case _ => IntT }, args1)

object Show:
  /** Iterative prefix rendering, used to compare typers' outputs. */
  def apply(t: T): String =
    val sb = StringBuilder()
    val stack = mutable.Stack[String | T](t)
    while stack.nonEmpty do
      stack.pop() match
        case s: String => sb ++= s
        case t: T =>
          sb ++= t.label ++= ":" ++= t.ty.toString
          if t.kids.nonEmpty then
            stack.push(")")
            t.kids.reverseIterator.foreach(k => stack.push(k).push(" "))
            stack.push("(")
    sb.toString

  def size(t: T): Int =
    var n = 0
    val stack = mutable.Stack[T](t)
    while stack.nonEmpty do { val t = stack.pop(); n += 1; t.kids.foreach(stack.push) }
    n
