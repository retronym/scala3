//> using options -experimental
import scala.language.experimental.methodBlockEndMarkers
def test(name: String)(body: => Unit): Unit = body
def foo(x: Int)(body: => Int): Int = body
object Foo:
  def apply(block: => Unit): Unit = ()
def run =
  test("my test"):
    foo(42):
      val result = 42 * 2
      result
    end foo
  end test
  Foo.apply:
    ()
  end apply
