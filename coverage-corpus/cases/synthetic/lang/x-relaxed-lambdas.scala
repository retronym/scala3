//> using options -preview
def test(xs: List[Int]) =
  val a = xs.map: x => x + 1
  val b = Seq((1, 2), (3, 4)).map: (a, b) => a + b
  val c = Seq((1, 2), (3, 4)).collect: case (a, b) if b > 2 => a
  val d = xs.map: x => x.toString
  val e = xs.foldLeft(0): (acc, x) =>
    acc + x
  (a, b, c, d, e)
