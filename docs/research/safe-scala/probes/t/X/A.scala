import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }
object O8 { val r: Cap = new Cap }
class C7(val r: Cap)
object O7 extends C7(new Cap)
