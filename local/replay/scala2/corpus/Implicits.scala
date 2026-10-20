// Givens.scala for Scala 2: type class instances as implicit objects/defs, an extension method via an
// implicit class, an implicit conversion, and a local instance that wins by scope.
import scala.language.implicitConversions

trait Show[A] {
  def show(a: A): String
}

object Show {
  implicit object IntShow extends Show[Int] {
    def show(a: Int) = a.toString
  }
  implicit object StringShow extends Show[String] {
    def show(a: String) = "\"" + a + "\""
  }
  implicit def listShow[A](implicit sa: Show[A]): Show[List[A]] = new Show[List[A]] {
    def show(as: List[A]) = as.map(sa.show).mkString("[", ", ", "]")
  }
}

object syntax {
  implicit class ShowOps[A](private val a: A) extends AnyVal {
    def shown(implicit s: Show[A]): String = s.show(a)
  }
  implicit def intToString(i: Int): String = i.toString
}

object Implicits {
  import syntax._
  def render: String = List(1, 2, 3).shown + "x".shown
  def viaConversion(i: Int): String = (i: String) + "!"
  def pickLocal: String = {
    implicit val local: Show[Int] = new Show[Int] { def show(a: Int) = "local" }
    42.shown
  }
}
