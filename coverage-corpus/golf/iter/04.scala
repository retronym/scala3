package golf

import scala.annotation.{tailrec, switch, targetName, static}
import scala.compiletime.{erasedValue, constValue, summonInline, summonFrom, error}
import scala.deriving.Mirror
import scala.quoted.*
import scala.language.implicitConversions
import scala.language.experimental.{captureChecking, separationChecking}
import scala.util.boundary, boundary.break
import java.util.function.IntUnaryOperator

opaque type Meters = Double

enum Shape(val sides: Int) derives CanEqual:
  case Circle(r: Double) extends Shape(0)
  case Square(s: Double) extends Shape(4)
  case Dot extends Shape(1)

enum Term[A]:
  case Lit(n: Int) extends Term[Int]
  case Bool(b: Boolean) extends Term[Boolean]
  case If[T](c: Term[Boolean], t: Term[T], e: Term[T]) extends Term[T]
  case Pair[X, Y](x: Term[X], y: Term[Y]) extends Term[(X, Y)]

def eval[A](e: Term[A]): A = e match
  case Term.Lit(n) => n
  case Term.Bool(b) => b
  case Term.If(c, t, f) => if eval(c) then eval(t) else eval(f)
  case Term.Pair(x, y) => (eval(x), eval(y))

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
      case s: Mirror.SumOf[T] => new Show[T]:
        def show(t: T) = shows(s.ordinal(t)).asInstanceOf[Show[T]].show(t)
      case p: Mirror.ProductOf[T] => new Show[T]:
        def show(t: T) =
          val fields = t.asInstanceOf[Product].productIterator.zip(shows)
          constValue[m.MirroredLabel] + fields.map((v, s) => s.asInstanceOf[Show[Any]].show(v)).mkString("(", ",", ")")

case class Person(name: String, age: Int)
sealed trait Pet
case class Cat(lives: Int) extends Pet

type Len[T <: Tuple] <: Int = T match
  case EmptyTuple => 0
  case _ *: t => compiletime.ops.int.S[Len[t]]

trait Functor[F[_]]:
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]

given Functor[List] with
  extension [A](fa: List[A]) def fmap[B](f: A => B) = fa.map(f)

def poly: [T] => T => List[T] = [T] => (t: T) => List(t)

trait Base(val tag: String):
  def describe: String = s"Base($tag)"
  lazy val lz: Int = tag.length
trait Loud extends Base:
  override def describe: String = super.describe.toUpperCase
class Impl(t: String) extends Base(t), Loud, Serializable:
  @caps.unsafe.untrackedCaptures private var count = 0
  def bump(): Int =
    count += 1
    count
  @targetName("plus") def ++(o: Impl): String = tag + o.tag
  inline def twice(inline x: Int): Int = x + x
  class Inner(val y: Int) { def outer: Impl = Impl.this }
  object Local { val z = count }

implicit class Rich(val s: String) extends AnyVal:
  def shout: String = s.toUpperCase + "!"
given Conversion[Boolean, Int] = b => if b then 1 else 0

class Printer:
  def print(s: String): Unit = println(s)
class Facade:
  private val p = Printer()
  export p.print

inline def describeType[T]: String = summonFrom {
  case _: Ordering[T] => "ordered"
  case _ => "unordered"
}

def showMacro(e: Expr[Any])(using Quotes): Expr[String] =
  import quotes.reflect.*
  val t = e.asTerm
  Expr(t.show + ":" + t.tpe.widen.show)

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

object Caps:
  import caps.{any, fresh, SharedCapability, Mutable}
  class S extends SharedCapability
  class Ref(init: Int) extends Mutable:
    private var current = init
    def get: Int = current
    update def put(x: Int): Unit = current = x
  class Ops[A](xs: Array[A]^) extends AnyVal:
    this: Ops[A]^ =>
    def f(p: A => Boolean): Array[A]^{this} = xs
  def closures(c: S): Unit =
    val y: (x: S^{c}) -> Object^{fresh} = x => x
    val t1: (x: () => Unit) -> (y: () ->{x} Unit) -> Unit = x => y => ()
  def capVar[C^](a: Ref^{C}): Ref^{C} = a
  def wf(c: S) =
    var x: Any^{c} = c
  def only: Object^{any.only[SharedCapability]} = Object()

object Patterns:
  object Even { def unapply(i: Int): Boolean = i % 2 == 0 }
  object Csv { def unapplySeq(s: String): Option[Seq[String]] = Some(s.split(",").toSeq) }
  def classify(x: Any): String = x match
    case 0 | 1 => "tiny"
    case i: Int if i > 100 => "big"
    case Even() => "even"
    case s @ Csv(a, b, rest*) => s"$a/$b/${rest.size}"
    case (a, b) => s"pair $a $b"
    case xs: List[Int @unchecked] => xs.sum.toString
    case s"$k=$v" => k + v
    case p: Person => p.name
    case null => "null"
    case _ => "other"
  def switchy(i: Int): String = (i: @switch) match
    case 1 => "a"
    case 2 => "b"
    case 3 => "c"
    case _ => "z"
  def strings(s: String): Int = s match
    case "alpha" | "beta" => 1
    case "gamma" => 2
    case _ => 3
  def shapes(s: Shape): Double = s match
    case Shape.Circle(r) => 3.14 * r * r
    case Shape.Square(x) => x * x

object Control:
  @tailrec def loop(n: Int, acc: Long = 0): Long = if n == 0 then acc else loop(n - 1, acc + n)
  def tryIt(s: String): Int =
    try s.toInt
    catch
      case _: NumberFormatException => -1
      case scala.util.control.NonFatal(e) => -2
    finally println("done")
  def firstBig(xs: List[Int]): Int = boundary:
    for x <- xs do if x > 10 then break(x)
    -1
  def whiles(n: Int): Int =
    var i = 0
    var s = 0
    while i < n do
      s += (if i % 3 == 0 then i else -1)
      i += 1
    s
  def lambdas(xs: List[Int]): List[Int] =
    var acc = 0
    val f = (x: Int) =>
      acc += x
      (y: Int) => x + y + acc
    xs.map(f).map(_(1))
  def sam: Runnable = () => println("run")
  def javaOp: IntUnaryOperator = _ + 1
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
  @caps.unsafe.untrackedCaptures var age = 28
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

@main def run(): Unit =
  println(eval(Term.If(Term.Bool(true), Term.Pair(Term.Lit(1), Term.Lit(2)), Term.Pair(Term.Lit(3), Term.Lit(4)))))
  import Show.given
  println(List(1, 2).shown + summon[Mirror.Of[Person]].fromProduct(("a", 1)) + (Cat(9): Pet))
  val n: Len[(Int, String)] = 2
  println(List(1, 2).fmap(_ + 1) ++ poly(3))
  val im = Impl("x")
  val in = new im.Inner(2)
  println(im.describe + im.lz + im.bump() + (im ++ im) + im.twice(3) + in.outer.tag + im.Local.z)
  println("s".shout + (true: Int) + describeType[Int])
  Facade().print("hi")
  println(Patterns.classify(5) + Patterns.switchy(2) + Patterns.strings("beta") + Patterns.shapes(Shape.Square(2)))
  println(Control.loop(10) + Control.tryIt("x") + Control.firstBig(List(1, 20)) + Control.whiles(5))
  println(Control.lambdas(List(1, 2)) :+ Control.javaOp.applyAsInt(1) :+ Control.nonLocal(List(5)))
  Control.sam.run()
  val tuple = (1, "a", true) ++ (2.0, 'c')
  val named = (name = "n", age = 3)
  println(tuple.size + named.name + interpolate(1, 2.5, "x") + Warnings.useOld)
  for
    x <- List(1, 2)
    y <- Option(x) if y > 0
    z = x + y
  yield z
  val arr = Array.tabulate(3)(i => i * i)
  val big = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)
  println(arr.sum + big(22) + Option(1).fold(0)(_ + 1) + summon[Ordering[Int]].compare(1, 2))
