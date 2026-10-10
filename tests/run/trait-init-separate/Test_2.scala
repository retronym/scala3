// scalajs: --skip

object ODef extends WithDef
object ODefault extends WithDefault { def f(x: Int) = x }
object OVal extends WithVal
object OLazyVal extends WithLazyVal
object OObject extends WithObject
object OCaseClass extends WithCaseClass
object OEnum extends WithEnum
object OExtension extends WithExtension
object OExport extends WithExport { val c = new C }
object OTypeExport extends WithTypeExport { val c = new C }
object OGiven extends WithGiven
object OGivenParams extends WithGivenParams
object OStructuralGiven extends WithStructuralGiven
object ODeferredGiven extends WithDeferredGiven

// A class compiled against a trait read from TASTy must call the trait's
// `$init$` exactly when the trait, compiled from source, has one.
object Test:
  def hasInit(cls: Class[?]) = cls.getDeclaredMethods.exists(_.getName == "$init$")

  def callsInit(cls: Class[?]) =
    val bytes = cls.getClassLoader.getResourceAsStream(cls.getName + ".class").readAllBytes()
    new String(bytes, "ISO-8859-1").contains("$init$")

  def main(args: Array[String]): Unit =
    for (trt, obj) <- List(
      classOf[WithDef] -> ODef,
      classOf[WithDefault] -> ODefault,
      classOf[WithVal] -> OVal,
      classOf[WithLazyVal] -> OLazyVal,
      classOf[WithObject] -> OObject,
      classOf[WithCaseClass] -> OCaseClass,
      classOf[WithEnum] -> OEnum,
      classOf[WithExtension] -> OExtension,
      classOf[WithExport] -> OExport,
      classOf[WithTypeExport] -> OTypeExport,
      classOf[WithGiven] -> OGiven,
      classOf[WithGivenParams] -> OGivenParams,
      classOf[WithStructuralGiven] -> OStructuralGiven,
      classOf[WithDeferredGiven] -> ODeferredGiven,
    ) do
      assert(hasInit(trt) == callsInit(obj.getClass), trt.getName)
