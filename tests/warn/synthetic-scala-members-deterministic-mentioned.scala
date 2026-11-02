//> using options -Wshadow:type-parameter-shadow

// The same method as in `synthetic-scala-members-deterministic.scala`, preceded by code
// that mentions the two synthesized types of `scala`. Now the shadowing is reported.
object prior:
  type T = scala.ContextFunction3[Int, Int, Int, Int]
  type U = scala.Function23[Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int]

class C:
  def f[
    ContextFunction3, // warn
    Function23 // warn
  ](x: ContextFunction3, y: Function23) = x
