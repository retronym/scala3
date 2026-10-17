// scalajs: --skip
// Serializable lambdas whose body is a private method of a nestmate: $deserializeLambda$ in the
// capturing class must be able to link the private implementation method in another class.
import java.io.*
object O:
  private def secret(x: Int) = x * 10
  trait T:
    def f: Int => Int = x => x + 1
    def g: Int => Int = x => secret(x)
  class Inner:
    def h: Int => Int = x => secret(x) + 2
object Test:
  def roundTrip[A](a: A): A =
    val bos = ByteArrayOutputStream(); ObjectOutputStream(bos).writeObject(a)
    ObjectInputStream(ByteArrayInputStream(bos.toByteArray)).readObject().asInstanceOf[A]
  def main(args: Array[String]): Unit =
    val t = new O.T {}
    println(List(roundTrip(t.f)(1), roundTrip(t.g)(2), roundTrip(new O.Inner().h)(3)))
