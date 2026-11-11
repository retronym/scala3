package test
import language.experimental.captureChecking
import language.experimental.separationChecking
object Probe uses O3:
  def f1: () -> Unit = () => O3.r.use()
