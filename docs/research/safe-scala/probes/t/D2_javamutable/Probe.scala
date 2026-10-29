import language.experimental.safe
object Probe:
  def run(): String =
    val b = java.util.stream.Stream.builder[String]()
    Secret.value.map(s => { b.add(s); s })
    b.build().findFirst().get()
