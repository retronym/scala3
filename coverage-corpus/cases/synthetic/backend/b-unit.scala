// Unit: BoxedUnit through generics, Unit-returning lambdas, unit values in expressions, discarded values.
package unitcase

def side(): Unit = println("side")
def generic[T](t: T): T = t
def apply[T](f: () => T): T = f()
trait Proc[T] { def run(): T }
class UnitProc extends Proc[Unit] { def run(): Unit = () }
def unitMatch(x: Any) = x match
  case () => "unit"
  case u: Unit => "typed unit"
  case _ => "other"
class Holder[T](var v: T)
@main def Test =
  val u: Unit = ()
  val u2 = generic(())
  val u3 = apply(() => side())
  val list: List[Unit] = List((), side(), u)
  val arr: Array[Unit] = Array((), ())
  val opt: Option[Unit] = Some(())
  val f: Int => Unit = _ => ()
  val g: () => Unit = side
  val h: Unit => Int = _ => 1
  val k = (x: Unit) => x
  val tuple = ((), 1, ())
  val any: Any = ()
  val p: Proc[Unit] = UnitProc()
  val r = p.run()
  val hu = Holder(()); hu.v = side()
  var vu: Unit = (); vu = generic(())
  val ifUnit = if args.isEmpty then () else side()
  val matchUnit = 1 match { case 1 => (); case _ => side() }
  val tryUnit = try side() catch { case _: Throwable => () } finally ()
  val whileUnit = while false do ()
  val assign = { var x = 1; x = 2 }
  val block = { side(); () }
  val ret: Unit = return_()
  val units = Seq.fill(3)(()).foreach(identity)
  java.util.Optional.of(1).ifPresent(i => println(i))
  val runnable: Runnable = () => side()
  val callable: java.util.concurrent.Callable[Unit] = () => side()
  val fut = scala.concurrent.Future.unit
  println(Seq(u, u2, u3, list, arr.toList, opt, f(1), g(), h(()), k(()), tuple, any, r, hu.v, vu, ifUnit, matchUnit, tryUnit, whileUnit, assign, block, ret, units, runnable.run(), callable.call()).map(_.toString))
  println(unitMatch(()) + unitMatch(1) + (() == ()) + ().hashCode + ().toString + classOf[Unit] + classOf[scala.runtime.BoxedUnit])
def return_(): Unit = return
def args: Array[String] = Array()
