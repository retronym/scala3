// Stage 1 of a separate-compilation case: a broad API surface whose TASTy is unpickled by stage 2.
package api

import scala.annotation.{tailrec, targetName, nowarn, unchecked, static, implicitNotFound}
import scala.compiletime.{erasedValue, constValue, summonInline, error, codeOf}
import scala.deriving.Mirror
import scala.quoted.*
import scala.language.implicitConversions

// --- annotations ----------------------------------------------------------------------------------
class myAnnot(val x: Int, val ys: String*) extends scala.annotation.StaticAnnotation
final class refining extends scala.annotation.RefiningAnnotation
@deprecated("use other", "1.0") def old(): Int = 1

// --- opaque types ---------------------------------------------------------------------------------
object Opaques:
  opaque type Meters <: Double = Double
  object Meters:
    def apply(d: Double): Meters = d
    extension (m: Meters) def +(o: Meters): Meters = (m: Double) + o
  opaque type Id[+A] = A
  def id[A](a: A): Id[A] = a
  opaque type Flags = Int
  object Flags:
    val Empty: Flags = 0
    inline def apply(bits: Int): Flags = bits
    extension (f: Flags)
      inline def |(o: Flags): Flags = f | o
      def has(o: Flags): Boolean = (f & o) != 0

// --- enums ----------------------------------------------------------------------------------------
enum Color(val rgb: Int) derives CanEqual:
  case Red extends Color(0xFF0000)
  case Green extends Color(0x00FF00)
  case Blue extends Color(0x0000FF)
  def hex: String = f"#$rgb%06x"
object Color:
  def parse(s: String): Option[Color] = values.find(_.toString == s)

enum Tree[+A]:
  case Leaf(value: A)
  case Node(left: Tree[A], right: Tree[A])
  case Empty
  def size: Int = this match
    case Leaf(_) => 1
    case Node(l, r) => l.size + r.size
    case Empty => 0

enum JEnum extends java.lang.Enum[JEnum]:
  case A, B

// --- GADTs, sealed hierarchies --------------------------------------------------------------------
sealed trait Exp[T]
final case class IntLit(i: Int) extends Exp[Int]
final case class BoolLit(b: Boolean) extends Exp[Boolean]
final case class If[T](c: Exp[Boolean], t: Exp[T], e: Exp[T]) extends Exp[T]
final case class Pair[A, B](a: Exp[A], b: Exp[B]) extends Exp[(A, B)]
def eval[T](e: Exp[T]): T = e match
  case IntLit(i) => i
  case BoolLit(b) => b
  case If(c, t, e) => if eval(c) then eval(t) else eval(e)
  case Pair(a, b) => (eval(a), eval(b))

// --- type classes, givens, derivation -------------------------------------------------------------
@implicitNotFound("No Show for ${T}")
trait Show[T]:
  def show(t: T): String
  extension (t: T) def shown: String = show(t)

object Show:
  given Show[Int] = _.toString
  given Show[String] with
    def show(t: String) = "\"" + t + "\""
  given [T: Show] => Show[List[T]] = l => l.map(summon[Show[T]].show).mkString("[", ",", "]")
  given optShow: [T] => (s: Show[T]) => Show[Option[T]]:
    def show(t: Option[T]) = t.fold("None")(x => s"Some(${s.show(x)})")

  inline def summonAll[T <: Tuple]: List[Show[?]] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts) => summonInline[Show[t]] :: summonAll[ts]

  inline def derived[T](using m: Mirror.Of[T]): Show[T] =
    val elems = summonAll[m.MirroredElemTypes]
    val label = constValue[m.MirroredLabel]
    inline m match
      case s: Mirror.SumOf[T] => (t: T) => label + "." + elems(s.ordinal(t)).asInstanceOf[Show[T]].show(t)
      case p: Mirror.ProductOf[T] => (t: T) =>
        label + t.asInstanceOf[Product].productIterator.zip(elems).map((x, s) => s.asInstanceOf[Show[Any]].show(x)).mkString("(", ",", ")")

case class Person(name: String, age: Int) derives Show
sealed trait Shape derives Show
case class Circle(r: Int) extends Shape derives Show
case class Square(s: Int) extends Shape derives Show

// --- higher-kinded, type lambdas, match types, dependent types -----------------------------------
trait Functor[F[_]]:
  extension [A](fa: F[A]) def map[B](f: A => B): F[B]
trait Monad[F[_]] extends Functor[F]:
  def pure[A](a: A): F[A]
  extension [A](fa: F[A]) def flatMap[B](f: A => F[B]): F[B]
  extension [A](fa: F[A]) def map[B](f: A => B): F[B] = fa.flatMap(a => pure(f(a)))

given Monad[List] with
  def pure[A](a: A) = List(a)
  extension [A](fa: List[A]) def flatMap[B](f: A => List[B]) = fa.flatMap(f)

type EitherT[L] = [R] =>> Either[L, R]
given [L] => Monad[EitherT[L]]:
  def pure[A](a: A) = Right(a)
  extension [A](fa: Either[L, A]) def flatMap[B](f: A => Either[L, B]) = fa.flatMap(f)

type Elem[X] = X match
  case String => Char
  case Array[t] => Elem[t]
  case Iterable[t] => Elem[t]
  case AnyVal => X

type Concat[X <: Tuple, Y <: Tuple] <: Tuple = X match
  case EmptyTuple => Y
  case x *: xs => x *: Concat[xs, Y]

def firstElem[X](x: X): Elem[X] = x match
  case x: String => x.charAt(0)
  case x: Array[t] => firstElem(x(0))
  case x: Iterable[t] => firstElem(x.head)
  case x: AnyVal => x

trait Entry:
  type Key
  type Value
  def key: Key
  def value: Value
def keyOf(e: Entry): e.Key = e.key
def depFun: (e: Entry) => e.Key = e => e.key
val polyFun: [T] => T => List[T] = [T] => (t: T) => List(t)
type Rec = { def name: String; val age: Int }

// --- variance, bounds, F-bounds, self types, intersection/union -----------------------------------
trait Ord[-T] { def cmp(a: T, b: T): Int }
abstract class Animal[Self <: Animal[Self]] { self: Self =>
  def compareTo(o: Self): Int
}
trait HasName { def name: String }
trait HasAge { def age: Int }
def describe(x: HasName & HasAge): String = s"${x.name}/${x.age}"
def widen(x: Int | String): String = x match
  case i: Int => i.toString
  case s: String => s

// --- classes with every kind of member ------------------------------------------------------------
trait Base(val tag: String):
  def abstractDef: Int
  val abstractVal: String
  var concreteVar: Int = 0
  lazy val lz: String = tag.concat(tag)
  protected def prot = 1
  private[api] def pkgPrivate = 2
  def overloaded(x: Int): Int = x
  def overloaded(x: String): String = x
  def defaults(a: Int = 1, b: String = "b")(c: Boolean = a > 0): String = s"$a$b$c"
  def varargs(xs: Int*): Int = xs.sum
  def byName(x: => Int): Int = x + x
  def ctxFun(using s: Show[Int]): Int => String = s.show
  def poly[A, B <: A, C >: B](b: B): C = b
  @targetName("plusplus") def ++(o: Base): String = tag + o.tag
  type Abstract
  type Bounded >: Null <: AnyRef
  type Alias = List[Int]

class Impl(t: String) extends Base(t), Serializable:
  def abstractDef = 3
  val abstractVal = "v"
  type Abstract = Int
  type Bounded = String
  override protected def prot = 5
  inline def inl(inline x: Int): Int = x + 1
  transparent inline def tinl(x: Int): Any = x
  @tailrec final def loop(n: Int, acc: Int = 0): Int = if n == 0 then acc else loop(n - 1, acc + n)
  object Inner:
    def x = t
  class InnerClass(val y: Int):
    def outer = Impl.this
  def local =
    def helper(i: Int) = i * 2
    class Local { def h = helper(3) }
    new Local().h
  def anon: Runnable = new Runnable { def run() = () }
  def sam: Runnable = () => ()

object Impl:
  @static val staticField: Int = 42
  def apply(): Impl = new Impl("x")
  def unapply(i: Impl): Some[String] = Some(i.tag)

class ValueClass(val underlying: Int) extends AnyVal:
  def double: ValueClass = new ValueClass(underlying * 2)

case class CC[+A](a: A, rest: List[A] = Nil)(val extra: Int = 0)

abstract class WithInit:
  val x: Int
  println(x)

// --- implicit conversions, Scala 2-style implicits ------------------------------------------------
implicit class RichInt(val i: Int) extends AnyVal:
  def squared: Int = i * i
implicit def strToInt(s: String): Int = s.length
given Conversion[Boolean, Int] = b => if b then 1 else 0

// --- extension methods, exports ----------------------------------------------------------------------
object Exts:
  extension (s: String)
    def words: List[String] = s.split(" ").toList
    def word(i: Int): String = words(i)
  extension [T](xs: List[T])(using Ord[T])
    def sortedWith: List[T] = xs.sortWith((a, b) => summon[Ord[T]].cmp(a, b) < 0)

class Printer:
  def print(s: String): Unit = ()
  def flush(): Unit = ()
class Scanner:
  def scan(): String = ""
class Copier:
  private val printer = Printer()
  private val scanner = Scanner()
  export printer.{print, flush as flushIt}
  export scanner.*

// --- inline / compiletime -------------------------------------------------------------------------
inline def power(x: Double, inline n: Int): Double =
  inline if n == 0 then 1.0
  else inline if n % 2 == 0 then
    val y = power(x, n / 2)
    y * y
  else x * power(x, n - 1)

inline def typeName[T]: String = inline erasedValue[T] match
  case _: Int => "Int"
  case _: String => "String"
  case _ => "other"

transparent inline def choose(inline b: Boolean): Any = inline if b then 1 else "one"

inline def assertPositive(inline x: Int): Int =
  inline if x < 0 then error("negative") else x

// --- macros (used from stage 2) --------------------------------------------------------------------
inline def showExpr(inline x: Any): String = ${ showExprImpl('x) }
def showExprImpl(x: Expr[Any])(using Quotes): Expr[String] = Expr(x.show)

inline def fieldNames[T]: List[String] = ${ fieldNamesImpl[T] }
def fieldNamesImpl[T: Type](using q: Quotes): Expr[List[String]] =
  import q.reflect.*
  val sym = TypeRepr.of[T].typeSymbol
  Expr(sym.caseFields.map(_.name))

inline def unrolledSum(inline xs: Int*): Int = ${ unrolledSumImpl('xs) }
def unrolledSumImpl(xs: Expr[Seq[Int]])(using Quotes): Expr[Int] = xs match
  case Varargs(es) => es.foldLeft('{ 0 })((acc, e) => '{ $acc + $e })
  case _ => '{ $xs.sum }

// --- named tuples, tuples ---------------------------------------------------------------------------
type NT = (name: String, age: Int)
def nt: NT = (name = "a", age = 1)
def bigTuple = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)

// --- context functions, by-name, function types of many arities -----------------------------------
type Ctx[T] = Show[Int] ?=> T
def withCtx[T](f: Ctx[T]): T = f(using Show.given_Show_Int)
def f22: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Int =
  (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w) => a + w

// --- erased-ish / phantom, singleton types ---------------------------------------------------------
def singleton(x: 42): 42 = x
val lit: "lit" = "lit"
def valueOf3 = valueOf[3]

// --- traits with init order, super calls, linearization -------------------------------------------
trait A1 { def m: String = "A1" }
trait B1 extends A1 { override def m: String = "B1" + super.m }
trait C1 extends A1 { override def m: String = "C1" + super.m }
class D1 extends B1 with C1 { override def m: String = "D1" + super[B1].m + super.m }

// --- Java interop surface --------------------------------------------------------------------------
class JavaLike extends java.util.AbstractList[String] with java.lang.Comparable[JavaLike]:
  def get(i: Int): String = i.toString
  def size(): Int = 3
  def compareTo(o: JavaLike): Int = 0
  @throws[java.io.IOException] def io(): Unit = ()
  @volatile var v: Int = 0
  @transient var t: Int = 0
  @native def nat(): Int
