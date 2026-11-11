// Boxing: primitives through generics, value classes, Function/Tuple specialization, arrays, erasure bridges, numeric conversions.
package boxing

class Meter(val v: Double) extends AnyVal:
  def +(o: Meter) = new Meter(v + o.v)
  override def toString = s"${v}m"
class Wrapper[T](val t: T) extends AnyVal:
  def map[U](f: T => U): Wrapper[U] = new Wrapper(f(t))
trait Container[T] { def get: T; def put(t: T): Container[T] }
class IntContainer(val i: Int) extends Container[Int]:
  def get: Int = i
  def put(t: Int) = new IntContainer(t)
abstract class Num[@specialized(Int, Double) T] { def plus(a: T, b: T): T }
object IntNum extends Num[Int] { def plus(a: Int, b: Int) = a + b }
def generic[T](t: T): T = t
def genericArr[T: scala.reflect.ClassTag](n: Int, t: T): Array[T] = Array.fill(n)(t)
def anyOps(a: Any) = a match
  case i: Int => i + 1
  case d: Double => d.toInt
  case c: Char => c.toInt
  case b: Boolean => if b then 1 else 0
  case l: Long => l.toInt
  case s: Short => s.toInt
  case b: Byte => b.toInt
  case f: Float => f.toInt
  case _ => 0
def eqs(a: Any, b: Any) = (a == b, a.equals(b), a.## == b.##)
@main def Test =
  val f1: Int => Int = _ + 1
  val f2: (Int, Double) => Long = (i, d) => (i + d).toLong
  val f3: Double => Boolean = _ > 0
  val f0: () => Char = () => 'c'
  val fl: Long => Float = _.toFloat
  val fu: Int => Unit = i => println(i)
  val tup = (1, 2.0); val tupS = (1, 'c', true, 1L, 1.0f, 1.toByte, 1.toShort)
  val m = new Meter(1) + new Meter(2)
  val ms = List(new Meter(1), new Meter(2)).map(_.v).sum
  val arrM = Array(new Meter(3))
  val w = new Wrapper(1).map(_ + 1).map(_.toString).t
  val c: Container[Int] = new IntContainer(1).put(2)
  val ints = Array(1, 2, 3); val longs = Array(1L); val dbls = Array(1.0); val chars = "abc".toCharArray; val bools = Array(true)
  val boxedArr: Array[Any] = Array(1, "s", 2.0)
  val gen = genericArr(2, 1.5); val genI = genericArr(2, 1); val genU = genericArr(1, ())
  println(f1(1) + f2(1, 2.0) + f3(1.0).toString + f0() + fl(1L) + fu(3) + tup._1 + tup._2 + tupS._3 + m + ms + arrM(0).v + w)
  println(c.get.toString + IntNum.plus(1, 2) + generic(1) + generic(1.0) + generic('c') + generic(true) + ints.sum + longs(0) + dbls(0) + chars(0) + bools(0))
  println(boxedArr.toList ++ gen ++ genI ++ genU)
  println(anyOps(1) + anyOps(2.0) + anyOps('a') + anyOps(true) + anyOps(1L) + anyOps(1.toShort) + anyOps(1.toByte) + anyOps(1f))
  println(eqs(1, 1L) ++ eqs(1.0, 1) ++ eqs('a', 97) ++ eqs(Integer.valueOf(1), 1) ++ eqs(BigInt(1), 1))
  val jInt: java.lang.Integer = 3; val sInt: Int = jInt; val jl: java.util.List[Integer] = java.util.List.of(1, 2)
  val sum = jl.stream().mapToInt(_.intValue).sum
  val conv = (1.toByte + 1.toShort + 'a' + 1L + 1.5f + 2.5).toString + (1 / 2.0) + (7 % 3) + (-1 >>> 28) + (1L << 40) + (5 ^ 3) + (~5)
  val nan = Double.NaN; val cmp = (nan == nan, 0.0 == -0.0, 1.0 / 0, (1.0).isNaN, math.max(1, 2L))
  val opt: Option[Int] = Some(1); val o2 = opt.map(_ * 2).getOrElse(0)
  val n: Null = null; val maybe: Integer = null
  val mutable = scala.collection.mutable.ArrayBuffer(1, 2); mutable += 3
  var accum = 0; for i <- 0 until 10 by 2 do accum += i
  println(sInt + sum + conv + cmp + o2 + n + maybe + mutable.sum + accum + (1 to 3).map(_ * 2).sum)
