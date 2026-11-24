import scala.util.boundary, boundary.break

object Test:
  val lock = new Object
  val lock2 = new Object
  var log = 0

  def simple(n: Int): Int = boundary:
    lock.synchronized:
      if n > 0 then break(1)
      2

  def unit(n: Int): Unit = boundary:
    lock.synchronized:
      if n > 0 then break()
    log += 1

  def nested(n: Int): Int = boundary:
    lock.synchronized:
      lock2.synchronized:
        if n > 0 then break(1)
        2

  // the argument of `break` is evaluated with the monitor held
  def arg(): Boolean = boundary:
    lock.synchronized:
      break(Thread.holdsLock(lock))

  def loop(xs: List[Int]): Int = boundary:
    for x <- xs do
      lock.synchronized:
        if x > 1 then break(x)
    0

  def whileLoop(): Int = boundary:
    var i = 0
    while true do
      lock.synchronized:
        i += 1
        if i > 3 then break(i)
    0

  // the boundary in the synchronized block is still translated to a label
  def inner(n: Int): Int = lock.synchronized:
    boundary:
      if n > 0 then break(1)
      2

  // `break` out of one boundary, or out of both and the monitor
  def both(n: Int): Int = boundary: outer ?=>
    val r: Int = lock.synchronized:
      boundary: inner ?=>
        if n == 1 then break(1)(using inner)
        if n == 2 then break(2)(using outer)
        3
    r + 10

  def withFinally(n: Int): Int = boundary:
    try
      lock.synchronized:
        if n > 0 then break(1)
        2
    finally log += 1

  def finallyInSynchronized(n: Int): Int = boundary:
    lock.synchronized:
      try
        if n > 0 then break(1)
        2
      finally log += (if Thread.holdsLock(lock) then 1 else 100)

  // some breaks to a label are translated, others are not
  def mixed(n: Int): Int = boundary:
    if n == 0 then break(0)
    lock.synchronized:
      if n == 1 then break(1)
    if n == 2 then break(2)
    3

  def check[T](actual: T, expected: T): Unit =
    assert(actual == expected, s"$actual != $expected")
    assert(!Thread.holdsLock(lock) && !Thread.holdsLock(lock2))

  def main(args: Array[String]): Unit =
    check(simple(1), 1)
    check(simple(0), 2)
    check({ unit(1); log }, 0)
    check({ unit(0); log }, 1)
    check(nested(1), 1)
    check(nested(0), 2)
    check(arg(), true)
    check(loop(List(0, 1, 2, 3)), 2)
    check(loop(Nil), 0)
    check(whileLoop(), 4)
    check(inner(1), 1)
    check(inner(0), 2)
    check((0 to 3).map(both).toList, List(13, 11, 2, 13))
    log = 0
    check((withFinally(1), log), (1, 1))
    check((finallyInSynchronized(1), log), (1, 2))
    check((0 to 3).map(mixed).toList, List(0, 1, 2, 3))
