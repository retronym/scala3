import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }
object Glob { val c: Cap = new Cap }
class T { def m(): Unit = Glob.c.use(); val f = () => Glob.c.use() }
class T2 extends T { val g: () -> Unit = () => Glob.c.use() }
