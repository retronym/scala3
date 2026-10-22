import language.experimental.safe
class Box { var v = "" }
object Probe:
  def run(): String =
    val b = new Box
    Secret.value.map(s => { b.v = s; s })
    b.v
