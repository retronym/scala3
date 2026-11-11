package test
import language.experimental.captureChecking
import language.experimental.separationChecking

class Cap extends caps.SharedCapability { def use(): Unit = () }

class C(val r: Cap)

// The capability created for the parent constructor is retained by `O.r` and `d.r`.
// Neither `O` nor `new D` have a `new C(...)` site that refines `r`, so the
// retained capability has to be part of the class's use set.
object O extends C(new Cap) // error
class D extends C(new Cap) // error

object Test:
  def f1: () -> Unit = () => O.r.use()
  def f2: () -> Unit =
    val d = new D
    () => d.r.use()
