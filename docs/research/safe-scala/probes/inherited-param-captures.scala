import language.experimental.captureChecking
import language.experimental.separationChecking

class Cap extends caps.SharedCapability { def use(): Unit = () }

class C(val r: Cap)

// The capability passed to the parent constructor is retained by `O.r` and `d.r`.
// Neither `O` nor `new D` have a `new C(...)` site that refines `r`, so the
// implied capture set of the instance has to account for the inherited parameter.
object O extends C(new Cap)
class D extends C(new Cap)

object Test:
  def f1: () -> Unit = () => O.r.use() // error
  def f2: () -> Unit =
    val d = new D
    () => d.r.use() // error
