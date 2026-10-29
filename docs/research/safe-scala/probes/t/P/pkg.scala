package test
import language.experimental.captureChecking
import language.experimental.separationChecking

class Cap extends caps.SharedCapability { def use(): Unit = () }
class C(val r: Cap)
object O extends C(new Cap)
class D extends C(new Cap)
object O8 { val r: Cap = new Cap }

object Test:
  def f1: () -> Unit = () => O.r.use()
  def f2: () -> Unit = { val d = new D; () => d.r.use() }
  def f3: () -> Unit = () => O8.r.use()
