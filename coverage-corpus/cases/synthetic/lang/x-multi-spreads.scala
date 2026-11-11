//> using options -experimental
import scala.language.experimental.multiSpreads
def sum(xs: Int*): Int = xs.sum
def test(a: List[Int], b: Vector[Int], c: Array[Int]) =
  sum(1, a*, 2, b*, c*) + sum(a*, b*)
