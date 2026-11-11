//> using options -experimental
import scala.language.experimental.relaxedExtensionImports
object A:
  extension (x: Int) def foo: Int = x + 1
object B:
  extension (x: String) def foo: String = x * 2
import A.*
import B.*
def test = (1.foo, "x".foo)
