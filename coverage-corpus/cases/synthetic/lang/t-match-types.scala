import scala.compiletime.ops.int.*
type Elem[X] = X match
  case String => Char
  case Array[t] => t
  case Iterable[t] => t
  case Option[t] => t
type LeafElem[X] = X match
  case String => Char
  case Array[t] => LeafElem[t]
  case Iterable[t] => LeafElem[t]
  case AnyVal => X
def leafElem[X](x: X): LeafElem[X] = x match
  case x: String => x.charAt(0)
  case x: Array[t] => leafElem(x(0))
  case x: Iterable[t] => leafElem(x.head)
  case x: AnyVal => x
type Len[T <: Tuple] <: Int = T match
  case EmptyTuple => 0
  case _ *: t => S[Len[t]]
type Rev[T <: Tuple, Acc <: Tuple] <: Tuple = T match
  case EmptyTuple => Acc
  case h *: t => Rev[t, h *: Acc]
type IsString[T] <: Boolean = T match
  case String => true
  case _ => false
type Fib[N <: Int] <: Int = N match
  case 0 => 0
  case 1 => 1
  case _ => Fib[N - 1] + Fib[N - 2]
type Unwrap[F] = F match
  case Option[Option[t]] => t
  case Option[t] => t
  case List[t] => t
  case Vector[t] => t
type Fn[F] = F match
  case (a => b) => (a, b)
  case ((a, b) => c) => (a, b, c)
type Deep[X] = X match
  case Map[k, List[v]] => (k, v)
  case Either[l, r] => l | r
sealed trait Nat
final class Z extends Nat
final class Succ[N <: Nat] extends Nat
type Plus[A <: Nat, B <: Nat] <: Nat = A match
  case Z => B
  case Succ[a] => Succ[Plus[a, B]]
type Bnd[X] = X match
  case List[t] => t
  case Seq[t] => Option[t]
def test =
  val a: Elem[List[Int]] = 1
  val b: Elem[String] = 'c'
  val c: LeafElem[List[Array[String]]] = 'x'
  val d: Len[(Int, String, Boolean)] = 3
  val e: Rev[(1, 2, 3), EmptyTuple] = (3, 2, 1)
  val f: IsString[String] = true
  val g: Fib[10] = 55
  val h: Unwrap[Option[Option[Int]]] = 1
  val i: Unwrap[Vector[Int]] = 2
  val j: Fn[Int => String] = (1, "s")
  val k: Deep[Map[Int, List[String]]] = (1, "s")
  val l: Deep[Either[Int, String]] = "x"
  val m: Plus[Succ[Z], Succ[Z]] = new Succ[Succ[Z]]
  val o: Bnd[Vector[Int]] = Some(1)
  summon[Len[(1, 2)] =:= 2]
  leafElem(List(Array("abc")))
