//> using options -classpath {libcp}:{tmp}/early.jar
package pipe2
import pipe.*

object B:
  def main(args: Array[String]): Unit =
    println(Codec.encodeAll(1, 2, 3))
    println(Rec(1).copy(b = "c") == Rec(1, "c"))
    println(Mode.Off("x").reason + Mode.On)
    println(Name("n").value)
    println(new UsesJava().help(1) + JHelper_1.max("a", "b") + JHelper_1.Kind.B.ordinal())
    val cb: JHelper_1.Cb[Int] = s => s.length
    println(new JHelper_1.Pt(1, 2).x())
