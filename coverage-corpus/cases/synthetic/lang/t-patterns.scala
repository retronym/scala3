case class P(x: Int, y: Int)
object Even { def unapply(i: Int): Boolean = i % 2 == 0 }
object Half { def unapply(i: Int): Option[Int] = if i % 2 == 0 then Some(i / 2) else None }
object Split { def unapplySeq(s: String): Option[Seq[String]] = Some(s.split(",").toSeq) }
class NameBased(val _1: Int, val _2: String) { def isEmpty = false; def get = this }
object NB { def unapply(x: Int): NameBased = new NameBased(x, x.toString) }
object Prod { def unapply(s: String): (Int, String) = (s.length, s) }
object VarSeq { def unapplySeq(x: Int): (Int, Seq[Int]) = (x, List(1, 2, 3)) }
object Dir { def unapply(x: Any): Option[(Int, Int)] = Some((1, 2)) }
sealed trait T
case class L(v: Int) extends T
case class N(l: T, r: T) extends T
case object E extends T
def test(x: Any, t: T, s: String, i: Int, xs: List[Int], o: Option[(Int, String)]) =
  val a = x match
    case 1 | 2 | 3 => "small"
    case i: Int if i > 100 => "big"
    case s: String => s
    case P(0, y) => y.toString
    case p @ P(_, _) => p.toString
    case (a, b) => s"$a$b"
    case l: List[?] => l.size.toString
    case m: Map[?, ?] => "map"
    case arr: Array[Int] => arr.length.toString
    case null => "null"
    case _: Unit => "unit"
    case 'c' => "char"
    case 1.5 => "dbl"
    case true => "t"
    case Nil => "nil"
    case Some(Some(_)) => "ss"
    case Dir(a, b) => s"$a$b"
  val b = t match
    case L(v) if v > 0 => v
    case N(L(a), L(b)) => a + b
    case N(l @ N(_, _), _) => 1
    case N(_, r) => 2
    case E => 0
    case L(_) => -1
  val c = s match
    case Split(a, b, rest*) => a + b + rest.size
    case Split(single) => single
    case s"$k=$v" => k + v
    case s"pre${mid}post" => mid
    case _ => ""
  val d = i match
    case Even() => 1
    case Half(Half(q)) => q
    case NB(n, str) => n
  val e = xs match
    case Nil => 0
    case h :: Nil => h
    case h :: t :: rest => h + t + rest.length
  val f = xs match
    case List(1, 2, _*) => 1
    case Seq(a, b) => 2
    case _ :+ last => last
    case _ => 3
  val g = o match
    case Some((n, str)) if str.nonEmpty => n
    case Some(_) | None => 0
  val h = s match { case Prod(len, str) => len }
  val k = i match { case VarSeq(a, b, c*) => a + b }
  val (p, q) = (1, 2)
  val P(px, py) = P(1, 2)
  val List(l1, l2) = List(1, 2): @unchecked
  val Some(sv) = Some(1): @unchecked
  for (k, v) <- Map(1 -> 2) do println(k + v)
  val pf: PartialFunction[Any, Int] = { case i: Int => i; case s: String if s.nonEmpty => s.length }
  val res = try 1 catch { case e: IllegalArgumentException => 2; case scala.util.control.NonFatal(e) => 3 } finally ()
  val typed = (x: @unchecked) match { case (h: Int) :: (t: List[Int] @unchecked) => h; case _ => 0 }
  val big: Long = 5L
  val lm = big match { case 1L => 1; case 5L => 5; case _ => 0 }
  val sm = s match { case "a" => 1; case "b" | "c" => 2; case _ => 3 }
  val cm = s.head match { case 'a' => 1; case 'b' => 2; case _ => 3 }
  val im = i match { case 1 => 1; case 2 => 2; case 3 => 3; case 4 => 4; case 5 => 5; case 100 => 6; case _ => 7 }
  (a, b, c, d, e, f, g, h, k, p, q, px, py, l1, l2, sv, pf(1), res, typed, lm, sm, cm, im)
