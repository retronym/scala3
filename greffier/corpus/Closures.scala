object Closures:
  def counter(start: Int): () => Int =
    var n = start
    () => { n += 1; n }

  def applyTwice(f: Int => Int, x: Int): Int = f(f(x))

  def local(xs: List[Int], k: Int): List[Int] =
    def scale(x: Int): Int = x * k
    def shift(x: Int): Int = x + xs.length
    xs.map(scale).map(shift)

  def byName(cond: Boolean, thunk: => String): String =
    if cond then thunk else ""

  def partial: PartialFunction[Int, String] =
    case 0 => "zero"
    case n if n > 0 => "pos"

  // Call sites with literal closures: under -opt the inliner inlines applyTwice/byName here
  // and the closure optimiser then calls the lambda bodies directly.
  def demo: Int = applyTwice(x => x * 2, 21)
  def thunked: String = byName(true, "lazy " + "value")
