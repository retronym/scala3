//> using options -preview
package a:
  package object b:
    val x = 1
    def y = 2
package c:
  def test =
    val z = a.b
    z.x + z.y + a.b.x
