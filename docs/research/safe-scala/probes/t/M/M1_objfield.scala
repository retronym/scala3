import language.experimental.captureChecking
import language.experimental.separationChecking

object O1 { val r: java.util.Random^ = new java.util.Random }
object P1 { def f: () -> Unit = () => O1.r.setSeed(1) }
