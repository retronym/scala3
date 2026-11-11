// Nesting: outer pointers, lambda lifting, local/anonymous classes, nested objects, captured vars, InnerClasses attributes.
package nesting

class Outer(val x: Int):
  private val secret = x * 2
  private def priv(i: Int) = i + secret
  class Inner(val y: Int):
    def sum = x + y + secret
    class Deeper:
      def all = x + y + sum + priv(1)
      object DeepObj { def v = all + 1 }
    def mkDeeper = new Deeper
  object InnerObj:
    val z = x + 1
    class InObj { def w = z + secret }
  trait InnerTrait { def t: Int = x }
  def localClass(n: Int) =
    var counter = 0
    class Local(val k: Int) extends InnerTrait:
      def bump() = { counter += k; counter + n + x }
      object LocalObj { def q = counter }
    val l = Local(3)
    l.bump(); l.LocalObj.q + l.t
  def localObject =
    object LO extends Runnable { def run() = println(x) }
    LO
  def anon = new InnerTrait { override def t = x + 100; def extra = secret }
  def lambdas(xs: List[Int]) =
    var acc = 0
    val f = (i: Int) => { acc += i; (j: Int) => i + j + acc + x }
    xs.map(f).map(_(1)) ++ xs.map(i => { def nested(k: Int): Int = if k == 0 then i else nested(k - 1); nested(i) })
  def lazyLocal =
    lazy val lz = { println("init"); x * 3 }
    def useIt = lz + lz
    useIt
  def byNameCapture(p: => Int) = () => p + x
  def returnFromLambda(xs: List[Int]): Int =
    xs.foreach(i => if i > 2 then return i)
    -1
  def tailrecLocal(n: Int) =
    @annotation.tailrec def loop(i: Int, acc: Int): Int = if i == 0 then acc else loop(i - 1, acc + x)
    loop(n, 0)
  def labeledLoops(n: Int) =
    var i = 0; var s = 0
    while i < n do
      var j = 0
      while j < i do { s += j; j += 1 }
      i += 1
    s
object Outer:
  private val companionSecret = 42
  def peek(o: Outer) = o.secret + companionSecret
  class StaticNested { def v = companionSecret }
  object Deep:
    object Deeper:
      object Deepest:
        val v = companionSecret
        def f[T](t: T) = List(t)
trait WithInit:
  val a: Int
  val b = a + 1
  lazy val c = b + 1
  object InTrait { def d = c }
class Impl extends WithInit:
  val a = 1
  class InImpl { def e = InTrait.d + a }
abstract class Abs[T](val t: T):
  def mk: Abs[T] = new Abs[T](t) { def extra = t }
enum Nested:
  case A
  case B(x: Int)
  def describe = this match
    case A => "a"
    case B(x) => class Tmp { def s = x.toString }; Tmp().s
def topLevel(n: Int) =
  class TopLocal(val m: Int) { def plus = m + n }
  TopLocal(1).plus
@main def Test =
  val o = Outer(1)
  val i = new o.Inner(2)
  println(i.sum + i.mkDeeper.all + i.mkDeeper.DeepObj.v + o.InnerObj.z + new o.InnerObj.InObj().w)
  println(o.localClass(2) + o.anon.t + o.lambdas(List(1, 2, 3)).sum + o.lazyLocal + o.byNameCapture(5)())
  println(o.returnFromLambda(List(1, 5)) + o.tailrecLocal(4) + o.labeledLoops(5) + Outer.peek(o))
  println(Outer.StaticNested().v + Outer.Deep.Deeper.Deepest.v + Outer.Deep.Deeper.Deepest.f("s").size)
  println(new Impl().InTrait.d + (new Impl).c + Nested.B(3).describe + topLevel(4))
  o.localObject.run()
