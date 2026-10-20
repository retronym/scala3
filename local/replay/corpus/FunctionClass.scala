// A class extending a function type. Under -opt -opt-inline:** the inliner may copy the
// parent trait's (Function1) forwarders and default methods into F itself.
class F extends (Int => Int) { def apply(x: Int) = x }

object FunctionClass:
  def viaFun(f: Int => Int): Int = f(2)
  def direct: Int = new F().apply(1)
  def demo: Int = viaFun(new F)
  def composed: Int => Int = new F().andThen(_ + 1)
