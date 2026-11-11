case class Pt(x: Int, y: Int)
extension (sc: StringContext)
  def j(args: Any*): String = sc.s(args*)
  def rx(): scala.util.matching.Regex = sc.parts.mkString.r
def test(i: Int, d: Double, s: String, c: Char, b: Boolean, l: Long, bi: BigInt, p: Pt) =
  s"$i ${i + 1} $s ${p.x} $$ ${"nested " + s"$i"}" +
  f"$i%5d $d%.2f $s%-10s $c%c $b%b $l%x ${i}%08X $d%e $d%g $s%S $i%o %% %n $bi%d ${d}%10.3f ${i.toByte}%d ${i.toShort}%d ${l}%,d" +
  f"${d.toFloat}%f $i%+d $i%(d ${s}%5.2s ${p}%s" +
  raw"\n\t$i" + j"x$i" + rx"a.b".matches("a.b") +
  s"""multi
     |line $i""".stripMargin + f"""$d%1.1f"""
