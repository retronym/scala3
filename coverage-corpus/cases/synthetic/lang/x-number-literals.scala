//> using options -experimental
import scala.language.experimental.genericNumberLiterals
import scala.util.FromDigits
case class BigNat(n: BigInt)
object BigNat:
  given FromDigits[BigNat] with
    def fromDigits(digits: String) = BigNat(BigInt(digits))
given FromDigits.Decimal[BigDecimal] with
  def fromDigits(digits: String) = BigDecimal(digits)
given FromDigits.WithRadix[Long] with
  def fromDigits(digits: String, radix: Int) = java.lang.Long.parseLong(digits, radix)
def test =
  val a: BigNat = 123456789012345678901234567890
  val b: BigInt = 1_000_000_000_000_000_000_000
  val c: BigDecimal = 1.5e100
  val d: Long = 0xFFFF
  val e: Double = 1_0.5e-3
  (a, b, c, d, e)
