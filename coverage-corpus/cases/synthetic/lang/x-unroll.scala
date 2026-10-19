//> using options -experimental
import scala.annotation.unroll
final class Lib:
  final def foo(s: String, i: Int, @unroll b: Boolean = true, l: Long = 0L): String = s + i + b + l
  def bar[T](t: T)(x: Int, @unroll y: Int = 1)(using DummyImplicit): String = t.toString + x + y
object Lib:
  def apply(s: String, @unroll n: Int = 0): Lib = new Lib
case class Unrolled(a: Int, @unroll b: String = "b", @unroll c: Long = 1L)
def test = Lib("x").foo("s", 1) + Lib("x").bar("t")(1) + Unrolled(1).copy(c = 2)
