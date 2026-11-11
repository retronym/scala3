//> using options -Yjava-tasty -Ypickle-write {tmp}/early.jar
// Stage 1 writes outline TASTy (including for the Java sources) to a jar; stage 2 compiles against only that jar.
package pipe

trait Codec[T]:
  def encode(t: T): String
object Codec:
  given Codec[Int] = _.toString
  inline def encodeAll[T](ts: T*)(using c: Codec[T]): List[String] = ts.toList.map(c.encode)

case class Rec(a: Int, b: String = "b") derives CanEqual
enum Mode:
  case On
  case Off(reason: String)
opaque type Name = String
object Name:
  def apply(s: String): Name = s
  extension (n: Name) def value: String = n
class UsesJava extends JHelper_1:
  override def help(x: Int): Int = x + JHelper_1.CONST
