// Closures: SAM conversion, serializable lambdas, lambdas in super constructor args and static contexts, partial application, eta-expansion.
package closures

abstract class WithFun(val f: Int => Int, val g: () => String)
class Child(k: Int) extends WithFun(x => x + k, () => s"k=$k")
object StaticCtx { val f: Int => Int = _ * 2; val composed = f.andThen(_ + 1).compose[String](_.length) }
@FunctionalInterface trait MySam { def apply(x: Int): String }
trait SamWithDefault { def run(i: Int): Int; def twice(i: Int) = run(run(i)) }
abstract class AbstractSam { def go(): Unit }
def serializeRoundTrip[T](t: T): T =
  val bos = java.io.ByteArrayOutputStream()
  val oos = java.io.ObjectOutputStream(bos); oos.writeObject(t); oos.close()
  java.io.ObjectInputStream(java.io.ByteArrayInputStream(bos.toByteArray)).readObject().asInstanceOf[T]
def method(a: Int, b: String): String = b * a
def overloaded(i: Int): Int = i
def overloaded(s: String): Int = s.length
def curried(a: Int)(b: Int)(c: Int) = a * b * c
def poly[T](t: T): List[T] = List(t)
def byName(x: => Int) = () => x
class Counter { var n = 0; def inc = () => { n += 1; n } }
@main def Test =
  val c = Child(3); println(c.f(1) + c.g())
  println(StaticCtx.composed("abc"))
  val sam: MySam = x => x.toString * 2
  val sd: SamWithDefault = _ + 1
  val as: AbstractSam = () => println("go")
  as.go()
  val r: Runnable = () => println("r")
  val cmp: java.util.Comparator[String] = (a, b) => a.length - b.length
  println(sam(2) + sd.twice(1) + cmp.compare("a", "bb"))
  val ser: Int => Int = serializeRoundTrip((x: Int) => x + 1)
  val serCap = { val k = 10; serializeRoundTrip((x: Int) => x + k) }
  println(ser(1) + serCap(1))
  val e1 = method; val e2: String => Int = overloaded; val e3 = curried(1); val e4 = curried(1)(2); val e5 = poly[Int]
  val pa = method(2, _); val pa2 = method(_, "x")
  println(e1(2, "a") + e2("abc") + e3(2)(3) + e4(5) + e5(1) + pa("y") + pa2(3))
  val ctr = Counter(); val inc = ctr.inc; inc(); inc()
  println(ctr.n + byName({ ctr.n += 1; ctr.n })())
  val fs = (1 to 3).map(i => (j: Int) => i * j)
  println(fs.map(_(2)).sum)
  val pf: PartialFunction[Int, String] = { case 1 => "one" }
  val lifted = pf.lift; val orElse = pf.orElse[Int, String] { case _ => "other" }
  println(lifted(2).toString + orElse(2) + List(1, 2).collect(pf))
  val f22 = (a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int, k: Int, l: Int, m: Int, n: Int, o: Int, p: Int, q: Int, r: Int, s: Int, t: Int, u: Int, v: Int) => a + v
  val f23 = (a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int, k: Int, l: Int, m: Int, n: Int, o: Int, p: Int, q: Int, r: Int, s: Int, t: Int, u: Int, v: Int, w: Int) => a + w
  println(f22.tupled((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22)) + f23(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23))
  val ctxF: Int ?=> String = summon[Int].toString
  given Int = 5
  println(ctxF)
