import language.experimental.safe
object Probe:
  def run(): String =
    val arr = new Array[String](1)
    Secret.value.map(s => { arr(0) = s; s })
    arr(0)
