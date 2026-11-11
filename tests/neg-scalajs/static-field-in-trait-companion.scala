import scala.annotation.static

trait T
object T:
  @static val s: Int = 1 // error
  @static def m(): Int = 2

class C
object C:
  @static val s: Int = 1
