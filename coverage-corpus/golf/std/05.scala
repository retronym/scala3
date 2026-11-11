package golf

import scala.annotation.{tailrec, switch, targetName, static}
import scala.compiletime.{erasedValue, constValue, summonInline, summonFrom, error}
import scala.deriving.Mirror
import scala.quoted.*
import scala.language.implicitConversions
import scala.language.`future-migration`
import scala.util.boundary, boundary.break
import java.util.function.IntUnaryOperator


enum Shape(val sides: Int) derives CanEqual:
  case Circle(r: Double) extends Shape(0)
  case Square(s: Double) extends Shape(4)
  case Dot extends Shape(1)
enum Term[A]:
  case Lit(n: Int) extends Term[Int]
  case Bool(b: Boolean) extends Term[Boolean]
  case If[T](c: Term[Boolean], t: Term[T], e: Term[T]) extends Term[T]
  case Pair[X, Y](x: Term[X], y: Term[Y]) extends Term[(X, Y)]

trait Show[T]:
  def show(t: T): String
  extension (t: T) def shown: String = show(t)

object Show:
  given Show[Int] = _.toString
  given Show[String] = s => s"'$s'"
  given [T: Show] => Show[List[T]] = _.map(_.shown).mkString("[", ",", "]")
  inline def summonAll[T <: Tuple]: List[Show[?]] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts) => summonInline[Show[t]] :: summonAll[ts]
  inline def derived[T](using m: Mirror.Of[T]): Show[T] =
    val shows = summonAll[m.MirroredElemTypes]
    inline m match
      case s: Mirror.SumOf[T] => t => shows(s.ordinal(t)).asInstanceOf[Show[T]].show(t)
      case p: Mirror.ProductOf[T] => t =>
        val fields = t.asInstanceOf[Product].productIterator.zip(shows)
        constValue[m.MirroredLabel] + fields.map((v, s) => s.asInstanceOf[Show[Any]].show(v)).mkString("(", ",", ")")

case class Person(name: String, age: Int) derives Show
sealed trait Pet derives Show
case class Cat(lives: Int) extends Pet derives Show


val poly: [T] => T => List[T] = [T] => (t: T) => List(t)


class Printer:
  def print(s: String): Unit = println(s)
class Facade:
  private val p = Printer()
  export p.print

inline def describeType[T]: String = summonFrom {
  case _: Ordering[T] => "ordered"
  case _ => "unordered"
}

def matchMacro(e: Expr[Int])(using Quotes): Expr[Int] = e match
  case '{ ($a: Int) + ($b: Int) } => '{ $a * $b }
  case '{ ${Expr(n)}: Int } => Expr(n + 1)
  case '{ List[Int](${Varargs(xs)}*).sum } => Expr.ofList(xs).asExprOf[List[Int]] match
    case '{ $l: List[Int] } => '{ $l.length }
  case '{ val x: Int = $rhs
          x + 1 } => rhs
  case '{ (y: Int) => $body(y): Int } => '{ $body(0) }
  case _ => '{ $e - 1 }

def typeMacro[T: Type](types: List[Type[?]])(using Quotes): Expr[String] =
  import quotes.reflect.*
  val names = types.collect { case '[List[t]] => Type.show[t] }
  Type.of[T] match
    case '[Option[u]] => Expr(TypeRepr.of[u].typeSymbol.name + names)
    case '[t *: ts] => Expr.summon[Ordering[t]].fold(Expr("none"))(o => '{ $o.toString })
    case _ => Expr(TypeRepr.of[T].dealias.simplified.show)

object Gadts:
  sealed trait Ev[F[_], G[_]]
  case class Same[H[_]]() extends Ev[H, H]
  sealed trait X[A]
  case class XL() extends X[List[Int]]
  case class XO() extends X[Option[Int]]
  def hk[F[_], G[_]](x: X[F[Int]], ev: Ev[F, G]) = ev match
    case Same() => x match
      case XL() => 1
  sealed trait Foo[A]
  case class Bar[C](x: Foo[C]) extends Foo[Int]
  case class End[B]() extends Foo[B]
  def nested[M](foo: Foo[M]): Int = foo match
    case End() => 0
    case Bar(End()) => 1
  type Part[A] = A match
    case BigInt => Int
    case String => Char
  def part[A](a: A): Part[A] = a match
    case n: BigInt => (n % 10).toInt
    case s: String => s.charAt(0)
  def stuck = part(BigDecimal("10"))
object Jvm:
  def over(x: Int): String = "int"
  def over(x: Long): String = "long"
  def over(x: Double): String = "double"
  def over(x: Any): String = "any"
  def over(x: Int, ys: Int*): String = "varargs"
  def over[T <: CharSequence](x: T): String = "generic"
  def overFn(f: Int => Int): Int = f(1)
  def overFn(f: String => Int)(using DummyImplicit): Int = f("a")
  def resolve(b: Byte, c: Char, s: Short) = over(b) + over(c) + over(s) + over(1, 2) + over("s") + overFn((x: Int) => x)
  trait Box[T]:
    def get: T
    def put(t: T): Box[T]
  class IntBox(v: Int) extends Box[Int]:
    def get: Int = v
    def put(t: Int): IntBox = IntBox(t)
  abstract class Cmp[T] extends java.util.Comparator[T]:
    def compare(a: T, b: T): Int = a.hashCode - b.hashCode
  def boxing[T: scala.reflect.ClassTag](t: T, n: Int) =
    val arr = Array.fill(n)(t)
    val anys: Array[Any] = Array(1, 'c', 2L, 1.5f, true, ())
    val ji: java.lang.Integer = n
    val sum: Int = ji + 1
    (arr.length, anys.count(_ == 1), ji == n, (n: Any) == 1L, sum.toByte, 'a' + 1)
  def javaApi(xs: java.util.List[String]) =
    val f: java.util.function.Function[String, Integer] = _.length
    val c = java.util.Comparator.comparing[String, Integer](f).reversed()
    xs.sort(c)
    xs.stream().map[Integer](f).filter(_ > 1).mapToInt(_.intValue).sum
  val pf: PartialFunction[Any, Int] =
    case i: Int if i > 0 => i
    case s: String => s.length
  def lambdas(xs: List[(Int, String)]) =
    var acc = 0
    val g = xs.map(_._2).collect(pf) ++ xs.map(_._1).flatMap(pf.lift)
    val h = xs.map { case (i, s) => acc += i
      s * i }
    val part = over(_: Int, 3)
    val curried = ((a: Int, b: Int) => a + b).curried
    val tupled = Function.tupled((a: Int, s: String) => s * a)
    (g, h, part(1), curried(1)(2), xs.map(tupled), pf.applyOrElse(2.5, _ => -1), (pf orElse { case _ => 0 })(true))
  def tryIt(s: String): Int =
    try s.toInt
    catch
      case _: NumberFormatException => -1
      case scala.util.control.NonFatal(e) => -2
    finally println("done")
  def firstBig(xs: List[Int]): Int = boundary:
    for x <- xs do if x > 10 then break(x)
    -1
  def nonLocal(xs: List[Int]): Int =
    xs.foreach(x => if x > 3 then return x)
    0

object Warnings:
  def inexhaustive(s: Shape): Int = s match
    case Shape.Dot => 1
  def unreachable(x: Int): Int = x match
    case _ => 1
    case 2 => 2
  def pure(): Unit =
    1 + 1
    ()
  def unchecked(x: Any): Boolean = x.isInstanceOf[List[String]]
  @deprecated("old", "1.0") def old = 1
  def useOld = old
  def recurse(n: Int): Int = recurse(n)
  def etaOld = recurse _
  extension (s: String) def length = 42
  var age = 28
  def assign() = (age = 29)
  def alnum = List(1) concat List(2)
  @annotation.nowarn("v") def tryAlone = try 1
  inline def anon = new Object {}
  @volatile val vol: Int = 1
  def catchAbstract[E <: Exception](f: => Unit): Option[E] =
    try { f
      None } catch case e: E => Some(e)
  def unionMatch(x: (Int, String) | (String, Int)) = x match
    case (i: Int, s) => s
  def manyBinders = List.fill(25)(0) match
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, rest*) => w
    case _ => 0

def interpolate(i: Int, d: Double, s: String): String =
  f"$i%05d $d%.3f $s%-8s ${i.toLong}%x $d%e" + raw"\n$i" + s"${s.length} $$"

object Interop:
  import scala.language.dynamics
  import scala.reflect.Selectable.reflectiveSelectable
  import java.lang.constant.ConstantDesc
  import java.util.concurrent.TimeUnit
  class Dyn extends Dynamic:
    def selectDynamic(n: String): String = n
    def applyDynamic(n: String)(args: Any*): String = n + args.size
    def applyDynamicNamed(n: String)(args: (String, Any)*): String = n
    def updateDynamic(n: String)(v: Any): Unit = ()
  def dyn(d: Dyn) =
    d.qux = 3
    d.foo + d.bar(1, 2) + d.baz(x = 1)
  type Rec = AnyRef {
    def name: String
    val age: Int
  }
  def structural(r: Rec) = r.name + r.age
  def unit(u: TimeUnit) = u match
    case TimeUnit.SECONDS => 1
    case TimeUnit.MINUTES => 2
  def sealedJava(c: ConstantDesc) = c match
    case i: Integer => 1
    case s: String => 2
  @scala.annotation.varargs def va(xs: Int*) = xs.sum
  @SerialVersionUID(1L) class Ser extends Serializable:
    @throws[java.io.IOException] def io(): Unit = ()
    @transient @volatile var t = 0
    @scala.beans.BeanProperty var bean: Int = 0

object Equality:
  import scala.language.strictEquality
  case class Id(v: Int) derives CanEqual
  enum Col derives CanEqual:
    case R, G
  class Box[T](val t: T)
  given [T](using CanEqual[T, T]): CanEqual[Box[T], Box[T]] = CanEqual.derived
  def test(a: Id, c: Col, l: List[Int]) = (a == Id(1), c == Col.R, Box(1) == Box(2), l == Nil, 1 == 1L)

object Legacy:
  def f(x: Int*): Int = x.sum
  def spread(xs: List[Int]) = f(xs: _*)
  private[this] def hidden: Int = 1
  protected[this] def guarded: Int = hidden
  def deep =
    val List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, _*) = List.fill(25)(0)
    w
  val ((x1, f1), (x2, f2)) = ((0d, BigDecimal(1)), (1d, BigDecimal(2)))
  def notVar(i: Int) = i match
    case X: Int => X
  def implicitFn(implicit f: () => Unit): Unit = ()
  def callImplicitFn = implicitFn(() => 43)
  def named(using i: Int, s: String = "x") = s * i
  def callNamed = named(using i = 10)

@main def run(): Unit =
  import Show.given
  println(List(1, 2).shown + Person("a", 1).shown + (Cat(9): Pet).shown)
  println(poly(3))
  println(describeType[Int])
  Facade().print("hi")
  println(Jvm.resolve(1, 'c', 2) + Jvm.boxing("x", 2) + Jvm.lambdas(List((1, "a"))) + Jvm.tryIt("x"))
  println(Jvm.javaApi(java.util.Arrays.asList("bb", "a")) + Jvm.firstBig(List(20)) + Jvm.nonLocal(List(5)))
  val tuple = (1, "a", true) ++ (2.0, 'c')
  val named = (name = "n", age = 3)
  println(tuple.size + named.name + interpolate(1, 2.5, "x") + Warnings.useOld)
  val arr = Array.tabulate(3)(i => i * i)
  val big = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)
  println(arr.sum + big(22) + Option(1).fold(0)(_ + 1) + summon[Ordering[Int]].compare(1, 2))
