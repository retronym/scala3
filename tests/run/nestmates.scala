// scalajs: --skip
//> using options -Ynestmates
// Under -Ynestmates, ExpandPrivate no longer mangles private method names for
// nestmate accesses.  Stack traces therefore show the original source name.

class OuterA:
  private def secret(): String =
    Thread.currentThread.getStackTrace.apply(1).getMethodName
  class Inner:
    def trigger(): String = secret()

class CompanionB:
  private def classSecret(): String =
    Thread.currentThread.getStackTrace.apply(1).getMethodName
object CompanionB:
  def trigger(c: CompanionB): String = c.classSecret()

class CompanionC:
  def trigger(): String = CompanionC.objectSecret()
object CompanionC:
  private def objectSecret(): String =
    Thread.currentThread.getStackTrace.apply(1).getMethodName

@main def Test(): Unit =
  val outerA = new OuterA()
  println(new outerA.Inner().trigger())
  println(CompanionB.trigger(new CompanionB()))
  println(new CompanionC().trigger())
