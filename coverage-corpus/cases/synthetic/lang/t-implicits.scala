trait Show[T] { def show(t: T): String }
object Show:
  given Show[Int] = _.toString
  given [T](using s: Show[T]): Show[List[T]] = _.map(s.show).mkString(",")
  given lowPrio[T]: Show[T] = _ => "?"
trait LowPriority { implicit def fallback[T]: Ordering[T] = Ordering.by(_.hashCode) }
object Priorities extends LowPriority { implicit val intOrd: Ordering[Int] = Ordering.Int.reverse }
trait Monoid[T] { def empty: T; def combine(a: T, b: T): T }
given Monoid[Int] with
  def empty = 0
  def combine(a: Int, b: Int) = a + b
given [A: Monoid, B: Monoid]: Monoid[(A, B)] with
  def empty = (summon[Monoid[A]].empty, summon[Monoid[B]].empty)
  def combine(x: (A, B), y: (A, B)) = (summon[Monoid[A]].combine(x._1, y._1), summon[Monoid[B]].combine(x._2, y._2))
def fold[T](xs: List[T])(using m: Monoid[T]): T = xs.foldLeft(m.empty)(m.combine)
// by-name implicits for recursive derivation
trait Codec[T] { def enc(t: T): String }
object Codec:
  given Codec[Int] = _.toString
  given [T](using c: => Codec[T]): Codec[List[T]] = l => l.map(c.enc).mkString
case class Rec(xs: List[Rec])
object Rec:
  given c: Codec[Rec] = r => summon[Codec[List[Rec]]].enc(r.xs)
// implicit conversions and views
import scala.language.implicitConversions
case class Meters(v: Double)
implicit def d2m(d: Double): Meters = Meters(d)
given Conversion[Int, Meters] = i => Meters(i.toDouble)
def len(m: Meters) = m.v
// implicit scope: companions of type args, prefixes, opaque types
object Scope:
  opaque type Tag = Int
  object Tag:
    given Show[Tag] = t => s"#$t"
    def apply(i: Int): Tag = i
// context bounds, using clauses, anonymous givens, given imports
def showAll[T: Show](xs: T*): String = xs.map(summon[Show[T]].show).mkString
object Instances:
  given intShow: Show[Int] = i => s"i$i"
  given strShow: Show[String] = s => s
def useImported =
  import Instances.{given Show[Int]}
  summon[Show[Int]].show(1)
// ambiguity resolved by specificity
trait Base[T]; trait Derived[T] extends Base[T]
given Base[Int] = new Base[Int] {}
given Derived[Int] = new Derived[Int] {}
// NotGiven, summonFrom
import scala.util.NotGiven
def noShow[T](using NotGiven[Show[T]]) = 1
inline def maybeShow[T](t: T): String = scala.compiletime.summonFrom {
  case s: Show[T] => s.show(t)
  case _ => "none"
}
// implicit classes, extension resolution via implicit scope
implicit class Rich(val s: String) extends AnyVal { def shout = s.toUpperCase }
def test =
  import Show.given
  import Priorities.*
  (summon[Show[List[Int]]].show(List(1, 2)), List(3, 1, 2).sorted, fold(List((1, 2), (3, 4))),
   Rec.c.enc(Rec(Nil)), len(1.5) + len(2), summon[Show[Scope.Tag]].show(Scope.Tag(1)), showAll(1, 2),
   useImported, summon[Base[Int]], maybeShow(1), maybeShow(new Object), "a".shout)
