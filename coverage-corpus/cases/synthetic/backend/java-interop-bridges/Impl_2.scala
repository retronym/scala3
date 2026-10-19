package jb2
import jb.JBase_1
import java.util.function.*

trait ScalaTrait:
  def abstractM(i: Int): Int
  def concrete(x: Int): Int = abstractM(x) + 1
  val traitVal: String = "tv"
  var traitVar: Int = 0
abstract class ScalaAbs[T](val t: T):
  def get: T = t
  def generic[U](u: U): (T, U) = (t, u)

class Impl extends JBase_1[String]:
  protected def make(i: Int): String = i.toString
  def consume(t: String, extras: Int*): Unit = println(t + extras.sum)
  override def compare(a: String, b: String): Int = -super.compare(a, b)
  def useProtected = prot + JBase_1.protStatic + JBase_1.protStaticMethod(3)
  def lambdaUsesProtected = (i: Int) => prot + i + JBase_1.protStatic

class Visitor extends JBase_1.Visitor[String]:
  def visitInt(i: Int) = s"int $i"
  def visitStr(s: String) = s"str $s"

class MyNode extends JBase_1.Node[MyNode]

object Use:
  def main(args: Array[String]): Unit =
    val im = Impl()
    im.consume("a", 1, 2, 3)
    println(im.useProtected + im.lambdaUsesProtected(1) + im.compare("a", "b") + im.compare("b", "a"))
    println(im.fold[Integer](0, (acc, s) => acc + s.length, java.util.List.of("ab", "c")))
    println(JBase_1.applyInt(_ + 1, 1) + JBase_1.applyLong(_ * _) + JBase_1.supply(() => 1) + JBase_1.dbl(_ * 1.5))
    JBase_1.run(() => println("run"))
    println(JBase_1.voidCall(() => null) == null)
    println(JBase_1.test(_.isEmpty).toString + JBase_1.boxed(1) + JBase_1.boxed(null) + JBase_1.prims(Array(1, 2)).length + JBase_1.objs(1, "a", 2.0).length)
    val arrs = JBase_1.listOfArrays(); arrs.add(Array(1))
    println(Visitor().visitAny(1) + Visitor().visitAny("s"))
    MyNode().children.add(MyNode())
    val inner = new im.InnerOfGeneric; inner.value = "v"
    println(JBase_1.Op.ADD.applyAsInt(1, 2) + JBase_1.Op.valueOf("MUL").applyAsInt(2, 3))
    JBase_1.Op.MUL match
      case JBase_1.Op.ADD => println("add")
      case JBase_1.Op.MUL => println("mul")
    try JBase_1.mayThrow(true) catch case e: JBase_1.Checked => println(e.getMessage)
    val j = new JavaSide()
    println(j.abstractM(2) + j.concrete(3) + j.traitVal + j.get + j.generic(1)._2 + JavaSide.fromScala(Impl()))
    val sam: IntBinaryOperator = _ + _
    val sup: Supplier[Unit] = () => ()
    val bi: BiConsumer[String, Integer] = (s, i) => println(s + i)
    bi.accept("x", 1); println(sam.applyAsInt(1, 2) + sup.get().toString)
