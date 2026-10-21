import language.experimental.captureChecking
import language.experimental.separationChecking

class C3(val r: java.util.Random^)
object P3 { def h(c: C3): () -> Unit = () => c.r.setSeed(1) }
