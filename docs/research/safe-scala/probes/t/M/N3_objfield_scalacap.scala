import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

object O8 { val r: Cap = new Cap }
object P8 { def f: () -> Unit = () => O8.r.use() }
