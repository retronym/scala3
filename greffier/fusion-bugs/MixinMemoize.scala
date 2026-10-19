trait Logger:
  val prefix: String = "[log]"
  val seenInInit: String = prefix

class Service extends Logger:
  override val prefix: String = "[svc]"

@main def Test = println(new Service().seenInInit)
