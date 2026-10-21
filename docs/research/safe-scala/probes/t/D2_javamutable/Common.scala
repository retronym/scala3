import language.experimental.safe
final class Classified[T](private val v: T):
  def map[U](f: T -> U): Classified[U] = Classified(f(v))
  override def toString = "Classified(****)"
object Secret:
  val value = Classified("sk-SECRET-123")
