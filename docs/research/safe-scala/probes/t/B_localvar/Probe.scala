import language.experimental.safe
object Probe:
  def run(): String =
    var leaked = ""
    Secret.value.map(s => { leaked = s; s })
    leaked
