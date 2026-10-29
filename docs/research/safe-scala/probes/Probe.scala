import language.experimental.safe

// Stand-in for tacit's Classified: map only accepts a pure function.
final class Classified[T](private val v: T):
  def map[U](f: T -> U): Classified[U] = Classified(f(v))
  override def toString = "Classified(****)"

object Probe:
  val env  = scala.util.Properties.envOrNone("HOME")
  val prop = scala.util.Properties.propOrNone("user.dir")
  def off = scala.runtime.LazyVals.getOffset(classOf[String], "hash")
  def par = java.util.stream.IntStream.range(0, 100).parallel().sum()
  def frames = new Exception().getStackTrace.map(e => e.getClassName + ":" + e.getFileName).toList.take(3)

  val secret = Classified("sk-SECRET-123")

  def main(args: Array[String]): Unit =
    scala.util.Properties.propOrNone("probe.out").foreach(_ => ())
    // leak the classified value through "pure" functions
    secret.map { s => new Exception("LEAK via stderr: " + s).printStackTrace(); s }
    secret.map { s => s.getBytes.foreach(b => scala.runtime.coverage.Invoker.invoked(b.toInt, "/tmp/safeprobe-leak")); s }
    // sanity: did CC let that through? print results via exception message to stderr
    throw new RuntimeException(s"env=$env prop=$prop off=$off par=$par frames=$frames")
