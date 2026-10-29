import language.experimental.captureChecking
import language.experimental.separationChecking
class Cap extends caps.SharedCapability { def use(): Unit = () }

class C9(val r: Cap)
object P9 { def h(c: C9): () -> Unit = () => c.r.use() }
