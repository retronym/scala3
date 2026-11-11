//> using options -experimental
import scala.language.experimental.namedTypeArguments
def f[A, B](a: A, b: B): (A, B) = (a, b)
class C[K, V](k: K, v: V)
def test =
  val x = f[B = String](1, "b")
  val y = f[A = Int, B = Any](1, 2)
  def g[T, U](t: T)(using u: U): (T, U) = (t, u)
  given Int = 1
  g[U = Int]("t")
