//> using options -Yno-fuse-phases

// A closure capturing a parameter of a @tailrec method must see that iteration's
// argument, not the variable TailRec reassigns. This held only because TailRec and
// CapturedVars were fused into one MegaPhase.

object Test {
  @annotation.tailrec
  def foo(i: Unit ?=> Int): Unit ?=> Int =
    if (i == 0) 0 else foo(i - 1)

  @annotation.tailrec
  def bar(n: Int, acc: List[() => Int]): List[() => Int] =
    if n == 0 then acc else bar(n - 1, (() => n) :: acc)

  def main(args: Array[String]): Unit =
    given Unit = ()
    val r: Int = foo(3)
    assert(r == 0)
    assert(bar(3, Nil).map(_()) == List(1, 2, 3))
}
