import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C7(val r: Cap)
class D extends C7(new Cap)
object P:
  def f: () -> Unit = { val d = new D; () => d.r.use() }
