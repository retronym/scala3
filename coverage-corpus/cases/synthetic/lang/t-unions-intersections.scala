trait A { def a: Int = 1 }
trait B { def b: Int = 2 }
trait C extends A with B
class AB extends A with B
def both(x: A & B): Int = x.a + x.b
def either(x: A | B): Int = x match
  case a: A => a.a
  case b: B => b.b
def lub(c: Boolean) = if c then 1 else "s"
val l1: Int | String = lub(true)
def lst = List(1, "a", 2.0)
val l2: List[Int | String] = List(1, "a")
def nullable(s: String | Null): Int = if s == null then 0 else s.length
type Json = String | Int | Boolean | Null | List[Any] | Map[String, Any]
def render(j: Json): String = j match
  case s: String => s
  case i: Int => i.toString
  case b: Boolean => b.toString
  case null => "null"
  case l: List[Json @unchecked] => l.map(render).mkString("[", ",", "]")
  case m: Map[String @unchecked, Json @unchecked] => m.map((k, v) => s"$k:${render(v)}").mkString("{", ",", "}")
def comm[X, Y](x: X & Y): Y & X = x
def dist[X, Y, Z](x: (X | Y) & Z): (X & Z) | (Y & Z) = x.asInstanceOf
trait F[+T]
def covUnion(x: F[Int] | F[String]): F[Int | String] = x
def covInter(x: F[A] & F[B]): F[A & B] = x
case class P1(x: Int)
case class P2(y: String)
def ors(x: P1 | P2 | Int) = x match
  case P1(x) => x
  case P2(y) => y.length
  case i: Int => i
def widenSingletons = { val x = if true then 1 else 2; x }
val inf = Seq(Some(1), None)
def test = both(new AB) + either(new AB) + either(new C {}) + nullable(null) + render(List[Json](1, "a", Map[String, Json]("k" -> true)))
