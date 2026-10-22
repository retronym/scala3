// scalajs: --skip
// Private members accessed from other classes of the same nest stay private in bytecode.
// Each access below is checked by the JVM's nestmate access control (JEP 181).

import java.lang.reflect.Modifier

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
    def viaEta = { val f: Int => Int = secret; f(1) }
    class Deep:
      def deep = secret(100)
  def local =
    class Local:
      def run = secret(10)
    new Local().run
  def anon: Int = { var res = 0; val r = new Runnable { def run() = res = secret(20) }; r.run(); res }

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

object Solo:
  private def soloSecret = "soloSecret"
  class Inner:
    def call = soloSecret

class Sup:
  private def supSecret = "supSecret"
  class Sub extends Sup:
    def call = supSecret

enum E:
  case A, B
  private def enumSecret = s"enum $this"
object E:
  def call = A.enumSecret

def isPrivate(cls: Class[?], name: String) =
  cls.getDeclaredMethods.filter(_.getName == name).forall(m => Modifier.isPrivate(m.getModifiers))

@main def Test(): Unit =
  val o = new Outer
  val i = new o.Inner
  assert(i.viaMethod == 42)
  assert(i.viaVar == 1)
  assert(i.viaField == "field")
  assert(i.viaLazy == "lazy")
  assert(i.viaLambda == List(2, 3))
  assert(i.viaEta == 2)
  assert(new i.Deep().deep == 101)
  assert(o.local == 11)
  assert(o.anon == 21)
  assert(Priv.make.x == 1)
  assert(Priv.makeAux.x == -1)
  assert(new Priv.InObject().call == "fromObject")
  val t = new T {}
  assert(new t.InTrait().call == "traitSecret")
  assert(T.call(t) == "traitSecret")
  assert(new Solo.Inner().call == "soloSecret")
  val s = new Sup
  assert(new s.Sub().call == "supSecret")
  assert(E.call == "enum A")

  for (cls, name) <- List(classOf[Outer] -> "secret", classOf[Outer] -> "counter", classOf[Priv] -> "fromObject",
                          Class.forName("Priv$") -> "fromObject", Class.forName("T") -> "traitSecret",
                          Class.forName("Solo$") -> "soloSecret", classOf[Sup] -> "supSecret")
  do assert(isPrivate(cls, name), s"$cls.$name")
  assert(classOf[Priv].getDeclaredConstructors.forall(c => Modifier.isPrivate(c.getModifiers)))
  assert(classOf[Outer#Inner].getNestHost == classOf[Outer])
  assert(classOf[Priv].getNestMembers.map(_.getName).toSet ==
    Set("Priv", "Priv$", "Priv$InObject"))
  println("ok")
