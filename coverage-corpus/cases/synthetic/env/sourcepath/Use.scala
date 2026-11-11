//> using options -sourcepath {case}/_sourcepath
object Use:
  def main(args: Array[String]): Unit =
    println(sp.Lib.make(1).twice)
    println(List(sp.Lib(2), sp.Lib(1)).sorted.map(_.t) :+ sp.Other.x)
