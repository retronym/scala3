import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C7(val r: Cap)
object O7 extends C7(new Cap):
  def go(): Unit = r.use()
object P:
  def f: () -> Unit = () => O7.go()
