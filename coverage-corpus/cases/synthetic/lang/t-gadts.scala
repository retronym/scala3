enum Expr[A]:
  case Lit(n: Int) extends Expr[Int]
  case BLit(b: Boolean) extends Expr[Boolean]
  case Plus(a: Expr[Int], b: Expr[Int]) extends Expr[Int]
  case If[T](c: Expr[Boolean], t: Expr[T], e: Expr[T]) extends Expr[T]
  case Pair[X, Y](x: Expr[X], y: Expr[Y]) extends Expr[(X, Y)]
  case Fst[X, Y](p: Expr[(X, Y)]) extends Expr[X]
  case Eq[T](a: Expr[T], b: Expr[T]) extends Expr[Boolean]
import Expr.*
def eval[A](e: Expr[A]): A = e match
  case Lit(n) => n
  case BLit(b) => b
  case Plus(a, b) => eval(a) + eval(b)
  case If(c, t, e) => if eval(c) then eval(t) else eval(e)
  case Pair(x, y) => (eval(x), eval(y))
  case Fst(p) => eval(p)._1
  case Eq(a, b) => eval(a) == eval(b)
sealed trait Eqv[A, B]
case class Refl[A]() extends Eqv[A, A]
def cast[A, B](eq: Eqv[A, B], a: A): B = eq match
  case Refl() => a
sealed trait Typ[T]
case object IntT extends Typ[Int]
case object StrT extends Typ[String]
case class ListT[T](t: Typ[T]) extends Typ[List[T]]
def zero[T](t: Typ[T]): T = t match
  case IntT => 0
  case StrT => ""
  case ListT(_) => Nil
def sizeOf[T](t: Typ[T], v: T): Int = t match
  case IntT => v
  case StrT => v.length
  case ListT(e) => v.map(sizeOf(e, _)).sum
trait Animal { type Food }
sealed trait Cmd[R]
case class Get(k: String) extends Cmd[Option[String]]
case class Put(k: String, v: String) extends Cmd[Unit]
case class Batch[R](cs: List[Cmd[R]]) extends Cmd[List[R]]
def run[R](c: Cmd[R]): R = c match
  case Get(k) => Some(k)
  case Put(_, _) => ()
  case Batch(cs) => cs.map(run)
def bounded[T <: AnyVal](x: Typ[T]) = x match
  case IntT => 1
