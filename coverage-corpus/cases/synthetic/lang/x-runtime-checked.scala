//> using options -experimental
def test(xs: List[Any], o: Option[Int]) =
  val (a: Int) :: rest = xs.runtimeChecked
  val Some(b) = o.runtimeChecked
  val c = (xs.runtimeChecked: @unchecked) match
    case (s: String) :: Nil => s
  (a, rest, b, c)
