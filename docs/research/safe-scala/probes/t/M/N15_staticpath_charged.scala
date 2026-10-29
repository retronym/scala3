import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }
object Glob { val c: Cap = new Cap }
class T:
  def m2(): Unit =
    val h: () -> Unit = () => Glob.c.use()
  val k: () -> Unit = () => Glob.c.use()
