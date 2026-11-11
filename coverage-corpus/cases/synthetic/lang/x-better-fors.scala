//> using options -experimental
import scala.language.experimental.betterFors
def test(xs: List[Int], o: Option[Int]) =
  val a = for
    a = 1
    x <- xs
    y = x + a
    if y > 1
    z <- List(y, y)
    w = z * 2
  yield w
  val b = for x <- o; y = x + 1 yield y
  val c = for
    x <- xs
    (p, q) = (x, x)
  yield p + q
  for x <- xs do println(x)
  (a, b, c)
