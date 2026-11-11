// Generic signatures and annotations as seen by Java: bounds, wildcards, inner classes, value classes, @varargs, @BeanProperty, static forwarders.
package sigs

import scala.annotation.varargs
import scala.beans.{BeanProperty, BooleanBeanProperty}

class VC(val x: Int) extends AnyVal
class Outer[A]:
  class Inner[B](val a: A, val b: B)
  def mk[B](b: B): Inner[B] = ???
class Sigs:
  def bounded[T <: Comparable[T]](t: T): T = t
  def lower[T >: String](t: T): List[T] = List(t)
  def wild(xs: List[?], m: Map[String, ? <: Number]): Int = xs.size
  def hk[F[_], A](fa: F[A]): F[A] = fa
  def arrays(a: Array[Int], b: Array[Array[String]], c: Array[List[Int]]): Array[Long] = Array()
  def vc(v: VC): VC = v
  def vcGeneric(v: List[VC]): Option[VC] = v.headOption
  def inter[T <: CharSequence & Comparable[T]](t: T): T = t
  def union(x: Int | String): String | Null = null
  def fn(f: (Int, String) => Boolean): Int => Unit = _ => ()
  def byName(x: => Int): Int = x
  def existential(c: Class[?]): java.util.List[? <: Object] = null
  def nothing: Nothing = throw new Exception
  def nullT: Null = null
  def unit(): Unit = ()
  def singleton(o: Sigs.type): this.type = this
  @varargs def jvarargs(xs: String*): Int = xs.size
  @varargs def jvarargsGen[T](first: Int, xs: T*): Int = xs.size
  @throws[java.io.IOException]("io") def throwsIt(): Unit = ()
  @deprecated("x", "1") def dep = 1
  @transient @volatile var tv: Int = 0
  @native def nat(): Unit
  @BeanProperty var name: String = ""
  @BooleanBeanProperty var active: Boolean = false
  @BeanProperty val ro: Int = 1
object Sigs:
  def staticForwarder(i: Int) = i
  val forwardedVal = 1
  var forwardedVar = 2
  @varargs def objVarargs(xs: Int*) = xs.sum
@SerialVersionUID(42L) class Ser extends Serializable { @transient lazy val l = 1 }
class Companion(x: Int)
object Companion { def apply(s: String) = new Companion(s.length); def main(args: Array[String]): Unit = () }
case class CC[T](a: T, b: List[T])(implicit ord: Ordering[T])
enum E[+T]:
  case A(t: T)
  case B extends E[Nothing]
