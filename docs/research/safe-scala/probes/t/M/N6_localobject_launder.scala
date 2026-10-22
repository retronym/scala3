import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C7(val r: Cap)
object P7:
  def launder(cap: Cap): () -> Unit =
    object O extends C7(cap)
    () => O.r.use()
