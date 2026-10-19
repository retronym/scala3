//> using options -experimental
import scala.language.experimental.saferExceptions
class LimitExceeded extends Exception
class Other extends Exception
def f(x: Double): Double throws LimitExceeded =
  if x < 1e9 then x * x else throw LimitExceeded()
def g(x: Int): Int throws LimitExceeded | Other =
  if x > 0 then throw Other() else f(x).toInt
def test =
  try
    f(1.0) + g(2)
  catch
    case ex: LimitExceeded => -1
    case ex: Other => -2
