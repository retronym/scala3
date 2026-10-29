import language.experimental.safe
object Probe:
  def spin(n: Int): Unit = { var i = 0; while i < n * 2_000_000 do i += 1 }
  def run(): String =
    val secret = Classified(7)   // one digit of a secret
    val t0 = java.time.Instant.now()
    secret.map(d => { spin(d); d })
    val ms = java.time.Duration.between(t0, java.time.Instant.now()).toMillis
    s"elapsed=${ms}ms (scales with the classified digit)"
