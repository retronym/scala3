// A stuck match type can erase to Nothing; no value is an instance of it.
// The Scala.js backend asserted that isInstanceOf[Nothing] cannot occur.
type Matcher[A] = A match { case String => A }

@main def Test =
  var n = 0
  def s: Any = { n += 1; "x" }
  assert(!s.isInstanceOf[Matcher[Nothing]])
  assert(n == 1)
