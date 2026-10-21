import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C7(val r: Cap)
object O7 extends C7(new Cap)
object O8 { val r: Cap = new Cap }
class H(val r: Cap) { def m: () -> Unit = () => r.use() }
class H2 { val r: Cap = new Cap; def m: () -> Unit = () => r.use() }
object P:
  val q1: Cap^{} = O7.r
  val q2: Cap^{} = O8.r
