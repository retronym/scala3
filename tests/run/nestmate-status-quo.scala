// scalajs: --skip
// Tests that document the status-quo (pre-NestMate JEP 181) name-mangling applied by
// ExpandPrivate when private members are accessed across JVM class boundaries.
// Method names visible in stack traces use the expanded form "Owner$$member".
// After NestMate support is added, these names should revert to their original forms.

// --- 1. Inner class accesses private method of outer class ---
class OuterA:
  private def secret(): String =
    Thread.currentThread.getStackTrace.apply(1).getMethodName
  class Inner:
    def trigger(): String = secret()

// --- 2. Companion object accesses private method of companion class ---
class CompanionB:
  private def classSecret(): String =
    Thread.currentThread.getStackTrace.apply(1).getMethodName
object CompanionB:
  def trigger(c: CompanionB): String = c.classSecret()

// --- 3. Companion class accesses private method of companion object ---
class CompanionC:
  def trigger(): String = CompanionC.objectSecret()
object CompanionC:
  private def objectSecret(): String =
    Thread.currentThread.getStackTrace.apply(1).getMethodName

@main def Test(): Unit =
  // 1. inner→outer: name is mangled to "OuterA$$secret" in the classfile
  val outerA = new OuterA()
  println(new outerA.Inner().trigger())

  // 2. companion-object→class: mangled to "CompanionB$$classSecret"
  val cb = new CompanionB()
  println(CompanionB.trigger(cb))

  // 3. class→companion-object: mangled to "CompanionC$$$objectSecret"
  //    (module-class name "CompanionC$" + "$$" separator + "objectSecret")
  println(new CompanionC().trigger())
