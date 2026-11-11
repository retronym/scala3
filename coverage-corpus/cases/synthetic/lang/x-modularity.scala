//> using options -experimental
import scala.language.experimental.modularity
trait Ord:
  type Self
  extension (x: Self) def compareTo(y: Self): Int
trait Monoid extends SemiGroup:
  def unit: Self
trait SemiGroup:
  type Self
  extension (x: Self) def combine(y: Self): Self
given Int is Monoid:
  def unit = 0
  extension (x: Int) def combine(y: Int) = x + y
given Int is Ord:
  extension (x: Int) def compareTo(y: Int) = x - y
def reduce[T: Monoid](xs: List[T]): T = xs.foldLeft(T.unit)(_.combine(_))
def max[T: Ord as ord](x: T, y: T): T = if x.compareTo(y) >= 0 then x else y
def both[T: {Monoid, Ord}](x: T) = (x.combine(x), x.compareTo(x))
class Wrap(tracked val x: Int)
trait F:
  type T
  def t: T
class Box(tracked val f: F):
  def get: f.T = f.t
def test =
  val w = Wrap(3)
  val n: 3 = w.x
  val b = Box(new F { type T = String; def t = "s" })
  val s: String = b.get
  (reduce(List(1, 2, 3)), max(1, 2), both(4))
