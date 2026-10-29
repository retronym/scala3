import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C7(val r: Cap)
object O7 extends C7(new Cap)
object P7 { def f: () -> Unit = () => O7.r.use() }
