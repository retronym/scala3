@main def greet(name: String, times: Int, flags: String*): Unit =
  for _ <- 1 to times do println(s"hi $name ${flags.mkString}")
@main def noArgs(): Unit = ()
@main def opt(x: Int = 3, y: Double = 1.5, z: Boolean = false, l: Long = 1L): Unit = ()
