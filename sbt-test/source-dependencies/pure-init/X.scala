object X {
  inline def ignore(x: Any): Unit = ()
  def main(args: Array[String]): Unit = {
    ignore(new B)
    O
    ignore(C(1))
    assert(Counter.n == 3, s"initializer of A ran ${Counter.n} times, expected 3")
  }
}
