package test
import language.experimental.captureChecking
import language.experimental.separationChecking

class Cap extends caps.SharedCapability { def use(): Unit = () }

class C(val r: Cap)

// Remedies for a class that retains a capability created for its parent constructor:
// extend Capability, declare the capture, or be local so that it is inferred.
object O extends C(new Cap), caps.SharedCapability
class D extends C(new Cap), caps.SharedCapability
class E extends C(new Cap) uses caps.any

// Forwarding an own parameter to the parent creates nothing new: the instance
// type of `new F(x)` already captures `x`.
class F(x: Cap) extends C(x)

object Test uses O.r:
  def f1(): Unit =
    val g: () ->{O} Unit = () => O.r.use()
  def f2(d: D^): () ->{d} Unit = () => d.r.use()
  def f3(): Unit =
    class L extends C(new Cap)
    val l = new L
    val g: () ->{l} Unit = () => l.r.use()
  def f4(x: Cap): () ->{x} Unit =
    val f = new F(x)
    val g: () ->{f} Unit = () => f.r.use()
    g
  def f5(): Unit =
    val e = new E
    val g: () ->{e} Unit = () => e.r.use()
