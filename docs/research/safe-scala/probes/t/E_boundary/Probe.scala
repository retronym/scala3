import language.experimental.safe
import scala.util.boundary, boundary.break
object Probe:
  def run(): String =
    boundary[String]:
      Secret.value.map(s => break(s))
      "none"
