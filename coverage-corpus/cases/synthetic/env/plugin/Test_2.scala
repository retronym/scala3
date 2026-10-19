//> using options -Xplugin:{classes} -P:count:verbose -Xplugin-require:count
object Test:
  @deprecated("x", "1") def old = 1
  def main(args: Array[String]): Unit = println(List(1, 2).map(_ + 1))
