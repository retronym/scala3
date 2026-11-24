import scala.annotation.tailrec

class C:
  @tailrec
  final def inTryFinally(n: Int): Int =
    try
      if n > 0 then return inTryFinally(n - 1) // error
    finally println()
    0

  @tailrec
  final def inTryCatch(n: Int): Int =
    try
      if n > 0 then return inTryCatch(n - 1) // error
      throw new Exception
    catch case _: Exception if n > 0 => 7

  @tailrec
  final def inCatchFinally(n: Int): Int =
    try println()
    catch case _: Exception =>
      if n > 0 then return inCatchFinally(n - 1) // error
    finally println()
    0

  @tailrec
  final def inCatchInTry(n: Int): Int =
    try
      try println()
      catch case _: Exception =>
        if n > 0 then return inCatchInTry(n - 1) // error
    finally println()
    0

  @tailrec
  final def inFinalizer(n: Int): Int =
    try println()
    finally
      if n > 0 then return inFinalizer(n - 1) // error
    0

  @tailrec
  final def matchInReturnInTry(n: Int): Int =
    try
      return (n: Any) match
        case i: Int if i > 0 => matchInReturnInTry(i - 1) // error
        case _ => 0
    catch case _: Exception => 1

  @tailrec
  final def inSynchronized(n: Int): Int =
    synchronized:
      if n > 0 then return inSynchronized(n - 1) // error
    0
