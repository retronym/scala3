package test
import language.experimental.captureChecking
import language.experimental.separationChecking
import scala.util.Random
object Probe uses O3, Random:
  def q1: () -> Unit = () => test.O3.r.use()
  def q2: () -> Unit = () => O3.r.use()
  def q3: () -> Long = () => Random.nextLong()
  def q4: () -> Long = () => scala.util.Random.nextLong()
