import language.experimental.captureChecking
import language.experimental.separationChecking

class C4(val r: java.util.Random^)
object O4 extends C4(new java.util.Random)
object P4 { def f: () -> Unit = () => O4.r.setSeed(1) }
