trait Show[A]:
  def show(a: A): String

object Show:
  given Show[Int] with
    def show(a: Int) = a.toString
  given Show[String] with
    def show(a: String) = s"\"$a\""
  given [A](using sa: Show[A]): Show[List[A]] with
    def show(as: List[A]) = as.map(sa.show).mkString("[", ", ", "]")

extension [A](a: A)(using s: Show[A])
  def shown: String = s.show(a)

given Conversion[Int, String] = _.toString

object Givens:
  def render: String = List(1, 2, 3).shown + "x".shown
  def viaConversion(i: Int): String = (i: String) + "!"
  def pickLocal: String =
    given Show[Int] with
      def show(a: Int) = "local"
    42.shown
