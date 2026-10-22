import language.experimental.safe
object Probe:
  def run(): String =
    try { Secret.value.map(s => throw new RuntimeException(s)); "none" }
    catch case e: RuntimeException => e.getMessage
