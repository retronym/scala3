import language.experimental.safe
object Leak { var v = "" }
object Probe:
  def run(): String =
    Secret.value.map(s => { Leak.v = s; s })
    Leak.v
