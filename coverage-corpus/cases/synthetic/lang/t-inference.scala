def id[T](x: T): T = x
def pair[A, B](a: A, b: B) = (a, b)
def foldl[A, B](xs: List[A])(z: B)(f: (B, A) => B): B = xs.foldLeft(z)(f)
def poly[T <: AnyRef](x: T, y: T): T = x
def hk[F[_], A](fa: F[A]): F[A] = fa
def hk2[F[_, _], A, B](fab: F[A, B]) = fab
def sam(r: Runnable) = r
trait Fun[-A, +B] { def apply(a: A): B }
def useFun(f: Fun[Int, String]) = f(1)
def overload(x: Int): Int = x
def overload(x: Long): Long = x
def overload(x: Any): String = x.toString
def overload2(f: Int => Int): Int = 1
def overload2(f: String => Int)(using DummyImplicit): Int = 2
def defaults[T](x: T = 1, y: List[T] = Nil) = (x, y)
def eta(f: Int => Int) = f(1)
def method(x: Int): Int = x
def curried(a: Int)(b: Int)(c: Int) = a + b + c
def byName[T](t: => T): () => T = () => t
def varargsInfer[T](xs: T*): List[T] = xs.toList
def bounds[T >: Null <: CharSequence](t: T) = t
def fbound[T <: Comparable[T]](t: T) = t
def test =
  val a = id(1)
  val b = pair(1, "s")
  val c = foldl(List(1, 2))(List.empty[String])((acc, x) => x.toString :: acc)
  val d = poly("a", new Object)
  val e = hk(List(1)); val e2 = hk[Option, Int](Some(1)); val e3 = hk2(Map(1 -> "a")); val e4 = hk2(Right(1): Either[String, Int])
  val f = sam(() => println("run"))
  val g = useFun(_.toString)
  val h = (overload(1), overload(1L), overload("s"), overload2((x: Int) => x))
  val i = defaults(); val i2 = defaults("s")
  val j = eta(method); val k = curried(1); val k2 = curried(1)(2); val l = method
  val m = byName { println(1); 1 }
  val n = varargsInfer(1, "a", 2.0)
  val o = bounds("s"); val p = fbound("s")
  val q = List(1, 2).map(_ + 1).filter(_ > 1).foldLeft(0)(_ + _)
  val r = Option(1).fold("none")(_.toString)
  val s = List((1, "a")).map { case (i, s) => i + s.length }
  val t = List(1).map((x: Int) => (y: Int) => x + y)
  val u: (Int, Int) => Int = _ + _
  val v = (x: Int) => x; val w = [T] => (t: T) => t
  val x = Seq(1, 2).collect { case i if i > 1 => i.toString }
  val y = if true then List(1) else Vector(2)
  val z = Map(1 -> "a").map((k, v) => (v, k))
  val arr = Array(1, 2, 3); val arr2 = Array("a"); val arr3 = Array(List(1)); val arr4 = Array.ofDim[Int](2, 2)
  val tup = (1, 2, 3).map[[X] =>> Option[X]]([T] => (t: T) => Some(t))
  (a, b, c, d, e, e2, e3, e4, f, g, h, i, i2, j, k, k2, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z, arr, arr2, arr3, arr4, tup)
