import scala.annotation.tailrec

// A self call in `return f(...)` is only in tail position if the `return` does not leave a
// `try` body, a handler with a finalizer, or a `synchronized` block.
object Test:
  var log = 0
  val lock = new Object

  final def inTryFinally(n: Int): Int =
    try
      if n > 0 then return inTryFinally(n - 1)
    finally log += 1
    0

  final def inTryCatch(n: Int): Int =
    try
      if n > 0 then return inTryCatch(n - 1)
      throw new Exception
    catch case _: Exception if n > 0 => 7

  final def inCatchFinally(n: Int): Int =
    try throw new Exception
    catch case _: Exception => if n > 0 then return inCatchFinally(n - 1)
    finally log += 1
    0

  final def inNestedTry(n: Int): Int =
    try
      try throw new Exception
      catch case _: Exception =>
        if n > 0 then return inNestedTry(n - 1)
    finally log += 1
    0

  // The result of the match is in tail position in the argument of a `return`
  final def matchInReturnInTry(n: Int): Int =
    try
      return (n: Any) match
        case i: Int if i > 0 => matchInReturnInTry(i - 1)
        case _ => throw new Exception
    catch case _: Exception if n > 1 => 8

  // `try` in a match in tail position, and `return` in a match in `try`
  final def matchAroundTry(n: Int): Int = (n: Any) match
    case 0 => throw new Exception
    case _ =>
      try
        (n: Any) match
          case i: Int if i > 0 => return matchAroundTry(i - 1)
          case _ => 0
      catch case _: Exception => 9

  final def inSynchronized(n: Int): Int =
    lock.synchronized:
      if n > 0 then return inSynchronized(n - 1)
      log = if Thread.holdsLock(lock) then 1 else 0
    0

  // The remaining methods are still tail recursive
  @tailrec
  final def afterTry(n: Int): Int =
    try log += 1
    finally log -= 1
    if n > 0 then return afterTry(n - 1)
    0

  @tailrec
  final def inCatch(n: Int): Int =
    try throw TestException
    catch case TestException =>
      if n > 0 then return inCatch(n - 1)
    0

  @tailrec
  final def inMatchInCatch(n: Int): Int =
    try throw TestException
    catch case TestException =>
      return (n: Any) match
        case i: Int if i > 0 => inMatchInCatch(i - 1)
        case _ => 0

  @tailrec
  final def tryInReturn(n: Int): Int =
    return
      try throw TestException
      catch case TestException => if n > 0 then tryInReturn(n - 1) else 0

  object TestException extends Exception with scala.util.control.NoStackTrace

  def main(args: Array[String]): Unit =
    def logged(op: => Int): (Int, Int) = { log = 0; val r = op; (r, log) }
    def check[T](actual: T, expected: T) = assert(actual == expected, s"$actual != $expected")

    check(logged(inTryFinally(3)), (0, 4))
    check(inTryCatch(2), 7)
    check(logged(inCatchFinally(3)), (0, 4))
    check(logged(inNestedTry(3)), (0, 4))
    check(matchInReturnInTry(3), 8)
    check(matchAroundTry(2), 9)
    check(logged(inSynchronized(3)), (0, 1))
    assert(!Thread.holdsLock(lock))

    val many = 1000000
    check(afterTry(many), 0)
    check(inCatch(many), 0)
    check(inMatchInCatch(many), 0)
    check(tryInReturn(many), 0)
