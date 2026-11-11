import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C7(val r: Cap)
object O7 extends C7(new Cap)
object P:
  def f: () -> Unit = () => O7.use2()
  extension (c: C7) def use2(): Unit = c.r.use()
