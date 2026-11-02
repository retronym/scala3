// A `.` that ends a line with no identifier after it: the next line is not
// skipped, its statement is parsed (`next`, `k` and `last` are members).
object M:
  def msg: String = "m"
  def show(err: String): String = msg + err.

  /** Next. */
  def next: Int = 1 // error
  def h(s: String): String = s.
  private def k: Int = 3 // error
  def lam: Int => Int = x => x.
  def last: Int = next + k // error
