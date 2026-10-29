import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C7(val r: Cap)
object O7 extends C7(new Cap)
class D extends C7(new Cap)
object P:
  val x: Nothing = O7.r
  val y: Nothing = (() => O7.r.use())
  val d = new D
  val z: Nothing = d.r
  val w: Nothing = d
  val v: Nothing = O7
