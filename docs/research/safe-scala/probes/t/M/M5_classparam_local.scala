import language.experimental.captureChecking
import language.experimental.separationChecking

class C5(val r: java.util.Random^)
object P5 { def f: () -> Unit = { val c = new C5(new java.util.Random); () => c.r.setSeed(1) } }
