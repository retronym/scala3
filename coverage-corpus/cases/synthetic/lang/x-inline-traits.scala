//> using options -experimental
import scala.language.experimental.inlineTraits
inline trait A[T](val x: T):
  def foo: T = x
  def bar(y: T): List[T] = List(x, y)
class B extends A[Int](1)
object O extends A[String]("s")
def test = new B().foo + new B().bar(2).sum + O.foo
