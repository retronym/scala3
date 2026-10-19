object Test:
  def f(n: Int): Int =
    lazy val x = n + 1
    x
