import language.experimental.captureChecking
import language.experimental.separationChecking

class C2 { val r: java.util.Random^ = new java.util.Random }
object P2 { def g(c: C2): () -> Unit = () => c.r.setSeed(1) }
