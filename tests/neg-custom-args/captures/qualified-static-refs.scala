package test
import language.experimental.captureChecking

class Cap extends caps.SharedCapability { def use(): Unit = () }

object M extends caps.SharedCapability:
  val c: Cap = new Cap
  def ping(): Unit = ()

// Qualified references to a static capability are charged like unqualified ones.
object Test uses M:
  def f1: () -> Unit = () => M.c.use() // error
  def f2: () -> Unit = () => test.M.c.use() // error
  def f3: () -> Unit = () => _root_.test.M.c.use() // error
  def f4: () -> Unit = () => test.M.ping() // error
