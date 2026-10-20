trait Logger {
  val prefix: String = "[log]"
  def log(msg: String): String = s"$prefix $msg"
}

trait Timestamped extends Logger {
  override def log(msg: String): String = super.log(s"@t $msg")
}

trait Counting extends Logger {
  private var count = 0
  override def log(msg: String): String = {
    count += 1
    super.log(s"#$count $msg")
  }
  lazy val summary: String = s"logged $count"
}

class Service extends Timestamped with Counting {
  override val prefix: String = "[svc]"
}

object Mixins {
  def run: String = {
    val s = new Service
    s.log("a"); s.log("b")
    s.summary
  }
}
