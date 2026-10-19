//> using options -experimental
import scala.language.experimental.erasedDefinitions
import scala.compiletime.erasedValue
sealed trait State
final class On extends State
final class Off extends State
class IsOff[S <: State] extends compiletime.Erased
object IsOff:
  given IsOff[Off] = new IsOff[Off]
class IsOn[S <: State] extends compiletime.Erased
object IsOn:
  given IsOn[On] = new IsOn[On]
class Machine[S <: State]:
  def turnOn(using erased ev: IsOff[S]): Machine[On] = new Machine[On]
  def turnOff(using erased ev: IsOn[S]): Machine[Off] = new Machine[Off]
erased val proof: Int = 1
def needs(erased x: Int, y: Int): Int = y
inline def erasedFun: (erased x: Int, y: Int) => Int = (erased x, y) => y
def test =
  val m = new Machine[Off].turnOn.turnOff
  needs(proof, 2) + erasedFun(1, 2)
