// Traits: mixin forwarders, super accessors, trait fields and lazy vals, trait parameters, bridges through linearization.
package traits

trait Base[T] { def get: T; def describe: String = s"Base($get)" }
trait Logging extends Base[Int] { abstract override def describe: String = "Log:" + super.describe }
trait Counting extends Base[Int] { var count = 0; abstract override def describe: String = { count += 1; "Count:" + super.describe } }
class Impl extends Base[Int] { def get = 1 }
class Stacked extends Impl with Logging with Counting
trait WithFields:
  val a: Int = 1
  var b: String = "b"
  lazy val c: Long = a * 2L
  private val d = 4
  private var e = 5
  protected val f = 6
  def sum = a + c + d + e + f
  final def fin = 1
trait Param(val p: Int, q: String):
  def pq = s"$p$q"
trait Sub extends Param
class UsesParam extends Param(1, "x") with Sub
trait Generic[A] { def id(a: A): A = a; def poly[B](b: B): (A, B) = (id(null.asInstanceOf[A]), b) }
class Specific extends Generic[String] { override def id(a: String): String = a + "!" }
trait Ord[T] extends Comparable[T]
class Ver(val v: Int) extends Ord[Ver] { def compareTo(o: Ver) = v - o.v }
trait SelfTyped { self: WithFields => def useSelf = sum + 1 }
class Both extends WithFields with SelfTyped
trait StaticHolder
object StaticHolder { @annotation.static val s = 1; @annotation.static def m() = 2 }
trait Diamond { def m: String = "D" }
trait L extends Diamond { override def m = "L" + super.m }
trait R extends Diamond { override def m = "R" + super.m }
class LR extends L with R { override def m = super[L].m + super[R].m + super.m }
abstract class AbsWithTrait extends WithFields { override val a = 10 }
object ObjWithTrait extends AbsWithTrait
trait Init { println("init"); val x = { println("x"); 1 } }
class InitOrder extends Init { val pre = x + 1 }
@main def Test =
  val s = Stacked(); println(s.describe + s.describe + s.count)
  val b = Both(); b.b = "bb"; println(b.useSelf + b.c + b.fin + b.b)
  println(UsesParam().pq + Specific().id("s") + Specific().poly(1) + Ver(1).compareTo(Ver(2)) + StaticHolder.s + StaticHolder.m())
  println(LR().m + ObjWithTrait.sum + InitOrder().x)
