import scala.compiletime.*
import scala.compiletime.ops.int.{Max, ToString, S}
import scala.compiletime.ops.int as I
import scala.compiletime.ops.string.{+ => concat, Length, Substring}
import scala.compiletime.ops.boolean.{&&, ||, !}
import scala.compiletime.ops.any.{==, !=, IsConst}
import scala.deriving.Mirror
val a: I.+[1, 2] = 3
val b: I.*[3, 4] = 12
val c: Max[3, 7] = 7
val d: ToString[42] = "42"
val e: concat["a", "b"] = "ab"
val f: Length["abc"] = 3
val g: true && false = false
val h: 1 == 1 = true
val i: Substring["hello", 1, 3] = "el"
inline def fact(inline n: Int): Int = inline if n <= 1 then 1 else n * fact(n - 1)
inline def sizeOf[T]: Int = inline erasedValue[T] match
  case _: Byte => 1
  case _: Int => 4
  case _: Long => 8
  case _: (a *: b) => sizeOf[a] + sizeOf[b]
  case _: EmptyTuple => 0
inline def labels[T](using m: Mirror.ProductOf[T]): List[String] = constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]]
inline def checked(s: String): String =
  inline if constValue[Length[s.type]] > 3 then error("too long: " + s) else s
inline def nameOf(inline x: Any): String = codeOf(x)
inline def isConst[T]: Boolean = constValueOpt[T].isDefined
inline def all[T <: Tuple]: List[Any] = inline erasedValue[T] match
  case _: EmptyTuple => Nil
  case _: (h *: t) => constValue[h] :: all[t]
transparent inline def mkPair(inline b: Boolean) = inline if b then (1, "a") else ("a", 1)
inline def summonEq[T]: Boolean = summonInline[T =:= T] != null
case class Pt(x: Int, y: Int)
inline def uninline(inline x: Int) = { requireConst(x); x }
def test =
  val p: (Int, String) = mkPair(true)
  (fact(5), sizeOf[(Int, Long, Byte)], labels[Pt], nameOf(1 + 2), all[(1, "a", true)], summonEq[Int], checked("ab"),
   constValue[S[4]], byName(1), uninline(3))
inline def byName(inline x: Int): Int = x + x
