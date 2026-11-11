import language.experimental.safe
object Probe:
  def launder(f: String => String): String -> String = f match
    case g: (String -> String) => g
  def run(): String =
    var leaked = ""
    val impure: String => String = s => { leaked = s; s }
    Secret.value.map(launder(impure))
    leaked
