//> using options -experimental -language:strictEquality
import scala.language.experimental.strictEqualityPatternMatching
enum Color derives CanEqual:
  case Red, Green
case class P(x: Int) derives CanEqual
def test(c: Color, p: P, s: String) =
  val a = c match
    case Color.Red => 1
    case Color.Green => 2
  val b = p match
    case P(1) => 1
    case _ => 2
  val d = s match
    case "a" => 1
    case _ => 0
  a + b + d + (if c == Color.Red then 1 else 0)
