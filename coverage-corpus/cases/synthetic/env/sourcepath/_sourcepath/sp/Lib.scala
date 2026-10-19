package sp

// Loaded lazily from -sourcepath, not passed on the command line.
class Lib[T](val t: T):
  def twice: (T, T) = (t, t)
object Lib:
  given Ordering[Lib[Int]] = Ordering.by(_.t)
  inline def make[T](t: T): Lib[T] = Lib(t)
