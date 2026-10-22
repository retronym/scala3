object Main:
  def main(args: Array[String]): Unit = println("LEAKED: " + Probe.run())
