import language.experimental.safe

// Library members that were reachable from safe code although they perform
// untracked effects: ambient reads of the environment, raw memory access via
// sun.misc.Unsafe, file writes through the coverage runtime, and console output
// through Throwable. The last two are reachable from functions typed as pure,
// which lets a Classified-style wrapper leak its contents.
object Test:
  val env  = scala.util.Properties.envOrNone("HOME") // error
  val prop = scala.util.Properties.propOrNone("user.dir") // error
  val home = scala.util.Properties.userHome // error

  def off = scala.runtime.LazyVals.getOffset(classOf[String], "hash") // error
  def cas(o: Object) = scala.runtime.LazyVals.CAS(o, 16L, 0L, 1, 0) // error

  def cov() = scala.runtime.coverage.Invoker.invoked(42, "/tmp/leak") // error

  val pureStderr: String -> String = s => { new Exception(s).printStackTrace(); s } // error
  def st(e: Exception) = e.setStackTrace(Array.empty) // error
