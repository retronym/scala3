import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C6(val r: Cap)
object P6 { def f: () -> Unit = { val c = new C6(new Cap); () => c.r.use() } }
