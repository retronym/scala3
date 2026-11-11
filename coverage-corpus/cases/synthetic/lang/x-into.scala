//> using options -experimental
import scala.language.experimental.into
import scala.Conversion.into
given Conversion[Int, String] = _.toString
given Conversion[Array[Int], IterableOnce[Int]] = _.iterator
def concat(xs: into[IterableOnce[Int]]*): List[Int] = xs.toList.flatMap(_.iterator)
class Builder:
  def add(s: into[String]): Builder = this
  def addAll(xs: into[String]*): Builder = this
into trait Text
case class Plain(s: String) extends Text
given Conversion[String, Plain] = Plain(_)
def show(t: Text) = t.toString
def test =
  Builder().add(1).add("x").addAll(1, 2, 3)
  concat(Array(1, 2), List(3)) ++ List(show("x"))
