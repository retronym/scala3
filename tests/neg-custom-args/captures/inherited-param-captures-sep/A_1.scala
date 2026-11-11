package test
import language.experimental.captureChecking
import language.experimental.separationChecking

class Cap extends caps.SharedCapability { def use(): Unit = () }
class C(val r: Cap)

object O extends C(new Cap), caps.SharedCapability
class D extends C(new Cap) uses caps.any
