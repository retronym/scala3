package test
import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }
class C(val r: Cap)
object O2 extends C(new Cap) uses caps.any
object Probe uses O2, scala.util.Random:
  def f1: () -> Unit = () => O2.r.use()
  def f2: () -> Unit = () => scala.util.Random.self.setSeed(1)
  def f3: () -> Long = () => scala.util.Random.nextLong()
  def f4: () -> Unit = () => O2.r
