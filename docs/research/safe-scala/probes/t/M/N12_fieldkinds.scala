import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class E { val r: Cap = new Cap }
class F { val r: Cap^ = new Cap }
class C7(val r: Cap)
class D extends C7(new Cap)
object P:
  val e = new E
  val a: Nothing = (() => e.r.use())
  val f = new F
  val b: Nothing = (() => f.r.use())
  val d = new D
  val c: Nothing = (() => d.r.use())
  val g: Nothing = e
  val h: Nothing = f
