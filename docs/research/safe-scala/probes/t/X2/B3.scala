package test
import language.experimental.captureChecking
import language.experimental.separationChecking
object Probe uses scala.util.Random:
  def f3: () -> Long = () => scala.util.Random.nextLong()
  def f2: () -> Unit = () => scala.util.Random.self.setSeed(1)
