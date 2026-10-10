// scalajs: --skip

class C { def f = 1; type X = Int }

trait WithDef:
  def f = 1
trait WithDefault:
  def f(x: Int = 1): Int
trait WithVal:
  val x = 1
trait WithLazyVal:
  lazy val x = 1
trait WithObject:
  object Inner
trait WithCaseClass:
  case class D(x: Int)
trait WithEnum:
  enum E { case A, B }
trait WithExtension:
  extension (i: Int) def twice: Int = i * 2
trait WithExport:
  val c: C
  export c.f
trait WithTypeExport:
  val c: C
  export c.X
trait WithGiven:
  given Ordering[Int] = Ordering.Int
trait WithGivenParams:
  given [T] => Ordering[List[T]] = null
trait WithStructuralGiven:
  given Ordering[String]:
    def compare(a: String, b: String) = 0
trait WithDeferredGiven:
  given Ordering[Int] = compiletime.deferred
