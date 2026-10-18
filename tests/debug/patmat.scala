object Even:
  def unapply(x: Int): Option[Int] = if x % 2 == 0 then Some(x / 2) else None

object Test:
  def cond(x: Any): Boolean = x.hashCode < 0

  def shared(x: Int): Int =
    x match
      case Even(1) => 1
      case Even(2) => 2
      case Even(n) => n
      case _ => 0

  def guard(x: Any): Int =
    x match
      case s: String if s.isEmpty => 1
      case y if cond(y) => 2
      case z => 3

  def switch(x: Int): Int =
    x match
      case 1 => 10
      case 2 => 20
      case 3 => 30
      case 4 | 5 => 45
      case _ => 0

  def strSwitch(s: String): Int =
    s match
      case "a" => 1
      case "b" => 2
      case "c" => 3
      case _ => 0

  def constBody(x: Int): Int =
    x match
      case 1 =>
        10
      case 2 =>
        20

  def main(args: Array[String]): Unit =
    shared(6)
    guard("x")
    switch(5)
    strSwitch("c")
    constBody(2)
