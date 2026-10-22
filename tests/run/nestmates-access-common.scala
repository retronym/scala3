// Private members accessed from other classes of their nest stay private, on the JVM (through
// nestmate access control) and on Scala.js (through static calls to private methods).
// Unlike nestmates-access.scala, this test does not use reflection, so it also runs on Scala.js.

class Outer:
  private var counter = 0
  private def secret(x: Int): Int = x + 1
  private val field = "field"
  private lazy val lzy = "lazy"
  class Inner:
    def viaMethod = secret(41)
    def viaVar = { counter += 1; counter }
    def viaField = field
    def viaLazy = lzy
    def viaLambda = List(1, 2).map(secret)
    class Deep:
      def deep = secret(100)
  def local =
    class Local:
      def run = secret(10)
    new Local().run

// A same-named method in a subclass must not override the private one
class Sub extends Outer:
  def secret(x: Int): Int = -1

class Priv private (val x: Int):
  private def this() = this(-1)
object Priv:
  def make = new Priv(1)
  def makeAux = new Priv()
  private def fromObject = "fromObject"
  class InObject:
    def call = fromObject

trait T:
  private def traitSecret = "traitSecret"
  class InTrait:
    def call = traitSecret
object T:
  def call(t: T) = t.traitSecret

object O:
  private def osecret(x: Int) = x * 10
  trait U:
    def g: Int => Int = x => osecret(x)

enum E:
  case A, B
  private def enumSecret = s"enum $this"
object E:
  def call = A.enumSecret

@main def Test(): Unit =
  val o = new Sub
  val i = new o.Inner
  assert(i.viaMethod == 42)
  assert(i.viaVar == 1)
  assert(i.viaField == "field")
  assert(i.viaLazy == "lazy")
  assert(i.viaLambda == List(2, 3))
  assert(new i.Deep().deep == 101)
  assert(o.local == 11)
  assert(o.secret(0) == -1)
  assert(Priv.make.x == 1)
  assert(Priv.makeAux.x == -1)
  assert(new Priv.InObject().call == "fromObject")
  val t = new T {}
  assert(new t.InTrait().call == "traitSecret")
  assert(T.call(t) == "traitSecret")
  assert((new O.U {}).g(3) == 30)
  assert(E.call == "enum A")
  println("ok")
