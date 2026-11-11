//> using options -Wnonunit-statement

// The statements of i4036 under -Wnonunit-statement: checking whether `1: v.x.x...x.T` has an
// uninteresting type compared the deep path with Unit/Any/... and took minutes.
trait A { def x: this.type = this; type T }
trait B { def y: this.type = this; def x: y.type = y; type T }
object A {
  val v = new A { type T = Int }
  v: v.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.type

  1: v.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.T

  val u = new B { type T = Int }
  u: u.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.
    x.type

  val z = new B { type T = this.type }
  z: z.T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#
    T#T#T#T#T#T#T#T#T#T#T#T#T#T#T#T
}
