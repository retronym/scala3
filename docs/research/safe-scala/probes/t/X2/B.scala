package test
import language.experimental.captureChecking
import language.experimental.separationChecking
object Probe uses O3, scala.util.Random:
  val x: Nothing = O3
  val y: Nothing = scala.util.Random
  def f1: () -> Unit = () => O3.r.use()
  def f3: () -> Long = () => scala.util.Random.nextLong()
