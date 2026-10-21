package test
import language.experimental.captureChecking
import language.experimental.separationChecking

// Separate compilation: the captures declared in A_1.scala are visible here.
object Test uses O, scala.util.Random:
  def f1: () -> Unit = () => O.r.use() // error
  def f2: () -> Unit =
    val d = new D
    () => d.r.use() // error
  def f3: () -> Long = () => scala.util.Random.nextLong() // error
