import language.experimental.captureChecking
import language.experimental.separationChecking

class R(val self: java.util.Random^)
object Loc extends R(new java.util.Random)
object LocSeeded extends scala.util.Random(0)

object Probe:
  def a: () -> Unit = () => Loc.self.setSeed(1)              // same-unit object, field typed ^
  def b: () -> Long = () => LocSeeded.nextLong()             // same-unit object, parent from TASTy
  def c: () -> Long = () => scala.util.Random.nextLong()     // library object from TASTy
  def d: () -> Unit = () => scala.util.Random.setSeed(1)
