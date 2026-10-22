//> using options -experimental
import scala.util.TupledFunction
extension [F, Args <: Tuple, R](f: F)
  def tupledGen(using tf: TupledFunction[F, Args => R]): Args => R = tf.tupled(f)
extension [F, Args <: Tuple, R](f: Args => R)
  def untupledGen(using tf: TupledFunction[F, Args => R]): F = tf.untupled(f)
def test =
  val f = (a: Int, b: String, c: Boolean) => a.toString + b + c
  val g = f.tupledGen
  val h: (Int, String, Boolean) => String = g.untupledGen
  g((1, "x", true)) + h(2, "y", false)
