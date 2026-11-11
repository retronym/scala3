//> using options -experimental
import scala.language.experimental.specializedTraits
import scala.language.experimental.inlineTraits
import scala.math.Numeric
inline trait Vec[T: {Specialized, Numeric}](elems: Array[T]):
  private val num = summon[Numeric[T]]
  def length = elems.length
  def apply(i: Int): T = elems(i)
  def scalarProduct(other: Vec[T]): T =
    var result = num.fromInt(0)
    for i <- 0 until length do
      result = num.plus(result, num.times(this(i), other(i)))
    result
class IntVec(xs: Array[Int]) extends Vec[Int](xs)
def test = IntVec(Array(1, 2)).scalarProduct(IntVec(Array(3, 4)))
