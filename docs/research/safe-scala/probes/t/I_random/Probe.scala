import language.experimental.safe
object Probe:
  def run(): String =
    Secret.value.map(s => { scala.util.Random.setSeed(s.hashCode); s })
    val got = scala.util.Random.nextLong()
    val expected = new scala.util.Random("sk-SECRET-123".hashCode).nextLong()
    s"seed-derived=$got matches=${got == expected}"
