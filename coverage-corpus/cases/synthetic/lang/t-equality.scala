import scala.language.strictEquality
case class Id(v: Int) derives CanEqual
enum Col derives CanEqual:
  case R, G
class Box[T](val t: T)
given [T](using CanEqual[T, T]): CanEqual[Box[T], Box[T]] = CanEqual.derived
def test(a: Id, b: Id, c: Col, s: String, l: List[Int], o: Option[Int]) =
  (a == b, c == Col.R, s == "x", l == Nil, o == None, 1 == 1L, 'a' == 97, Box(1) == Box(2), a != b, 1.0 == 1, o.contains(1))
