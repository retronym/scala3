sealed trait Shape
case class Circle(r: Double) extends Shape
case class Rect(w: Double, h: Double) extends Shape
case object Dot extends Shape

object Patmat {
  def area(s: Shape): Double = s match {
    case Circle(r) if r > 0 => math.Pi * r * r
    case Circle(_)          => 0.0
    case Rect(w, h)         => w * h
    case Dot                => 0.0
  }

  def describe(x: Any): String = x match {
    case i: Int if i < 0      => "negative"
    case (a, b)               => s"pair $a $b"
    case xs: List[_]          => s"list of ${xs.size}"
    case Some(v)              => s"some $v"
    case _                    => "other"
  }
}
