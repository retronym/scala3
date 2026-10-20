object ForDesugar {
  def pairs(xs: List[Int], ys: List[String]): List[(Int, String)] =
    for {
      x <- xs
      if x % 2 == 0
      y <- ys
    } yield (x, y)

  def greet(name: String, n: Int): String =
    s"hello $name, you are ${n + 1} years old"

  def sum(xs: Array[Int]): Int = {
    var acc = 0
    for (x <- xs) acc += x
    acc
  }
}
