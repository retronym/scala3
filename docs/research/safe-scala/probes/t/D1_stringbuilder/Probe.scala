import language.experimental.safe
object Probe:
  def run(): String =
    val sb = new StringBuilder
    Secret.value.map(s => { sb.append(s); s })
    sb.toString
