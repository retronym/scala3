package dotty.tools.backend.jvm

import dotty.DottyBytecodeTest

import scala.language.unsafeNulls
import org.junit.Assert.*
import org.junit.Test

import scala.tools.asm.Opcodes
import scala.tools.asm.tree.*
import scala.jdk.CollectionConverters.*

/** Status-quo tests for private-access widening performed by [[dotty.tools.dotc.transform.ExpandPrivate]].
 *
 *  Before JEP 181 (NestMates) support is added, cross-boundary private accesses are handled by:
 *    1. Renaming the accessed private member with an expanded name (`Owner$$member`).
 *    2. Removing the `ACC_PRIVATE` flag and adding `ACC_PUBLIC`, so the JVM can reach the member
 *       across class boundaries.  (The backend's [[BCodeUtils.javaFlags]] emits either
 *       `ACC_PRIVATE` or `ACC_PUBLIC` — there is no package-private path.)
 *
 *  Expanded-name convention:
 *    - A private member of `class Foo` gets the JVM name `Foo$$member`.
 *    - A private member of `object Foo` (module class `Foo$`) gets `Foo$$$member`
 *      (the trailing `$` of the module-class name, plus the `$$` expand separator).
 *
 *  Access patterns covered:
 *    - Inner class accessing a private member of its outer class
 *    - Companion object accessing a private method of its companion class
 *    - Companion class accessing a private method of its companion object
 *    - Companion object accessing a private val (getter) of its companion class
 */
class NestMateTests extends DottyBytecodeTest {
  // Variant that enables -Ynestmates so the nestmates tests can share the same source snippets.
  private object WithNestmates extends DottyBytecodeTest {
    override def initCtx = {
      val ctx = super.initCtx
      ctx.setSetting(ctx.settings.Ynestmates, true)
    }
  }

  private val ACC_PRIVATE = Opcodes.ACC_PRIVATE
  private val ACC_PUBLIC  = Opcodes.ACC_PUBLIC

  private def methodNamed(cls: ClassNode, name: String): Option[MethodNode] =
    cls.methods.asScala.find(_.name == name)

  private def fieldNamed(cls: ClassNode, name: String): Option[FieldNode] =
    cls.fields.asScala.find(_.name == name)

  /** Inner class accessing a `private def` of the outer class.
   *
   *  Status quo: the private method in `Outer` is renamed to `Outer$$secret` and made
   *  public so that `Inner` (a separate JVM class) can call it.
   */
  @Test def innerClassAccessesOuterPrivateDef(): Unit = {
    val source =
      """class Outer:
        |  private def secret(): Int = 42
        |  class Inner:
        |    def trigger(): Int = secret()
        |""".stripMargin
    checkBCode(source) { dir =>
      val outerCls = loadClassNode(dir.lookupName("Outer.class", directory = false).input)

      // Original private name must no longer exist as a private member.
      methodNamed(outerCls, "secret").foreach { m =>
        assertTrue("method 'secret' still private after ExpandPrivate",
          (m.access & ACC_PRIVATE) == 0)
      }

      // A public method with the expanded name must exist.
      val expandedMethod = methodNamed(outerCls, "Outer$$secret")
      assertTrue("expanded method 'Outer$$secret' not found in Outer", expandedMethod.isDefined)
      assertTrue("'Outer$$secret' must be public", (expandedMethod.get.access & ACC_PUBLIC) != 0)
      assertTrue("'Outer$$secret' must not be private", (expandedMethod.get.access & ACC_PRIVATE) == 0)

      // Inner class must invoke the expanded name.
      val innerCls = loadClassNode(dir.lookupName("Outer$Inner.class", directory = false).input)
      val trigger  = getMethod(innerCls, "trigger")
      val hasExpandedCall = trigger.instructions.iterator.asScala.exists {
        case m: MethodInsnNode => m.name == "Outer$$secret"
        case _                 => false
      }
      assertTrue("trigger() must call Outer$$secret", hasExpandedCall)
    }
  }

  /** Companion object accessing a `private def` of the companion class.
   *
   *  Status quo: the private method in `Foo` (the class) is renamed to `Foo$$secret`
   *  and made public so that `Foo$` (the module class) can call it.
   */
  @Test def companionObjectAccessesClassPrivateDef(): Unit = {
    val source =
      """class Foo:
        |  private def secret(): Int = 42
        |object Foo:
        |  def trigger(f: Foo): Int = f.secret()
        |""".stripMargin
    checkBCode(source) { dir =>
      val fooCls = loadClassNode(dir.lookupName("Foo.class", directory = false).input)

      methodNamed(fooCls, "secret").foreach { m =>
        assertTrue("method 'secret' in Foo still private after ExpandPrivate",
          (m.access & ACC_PRIVATE) == 0)
      }

      val expandedMethod = methodNamed(fooCls, "Foo$$secret")
      assertTrue("expanded method 'Foo$$secret' not found in Foo.class", expandedMethod.isDefined)
      assertTrue("'Foo$$secret' must be public", (expandedMethod.get.access & ACC_PUBLIC) != 0)
      assertTrue("'Foo$$secret' must not be private", (expandedMethod.get.access & ACC_PRIVATE) == 0)
    }
  }

  /** Companion class accessing a `private def` of the companion object.
   *
   *  Status quo: the private method in `Bar$` (the module class) is renamed to `Bar$$$secret`
   *  (`Bar$` + `$$` separator + `secret`) and made public so that `Bar` can call it.
   */
  @Test def companionClassAccessesObjectPrivateDef(): Unit = {
    val source =
      """class Bar:
        |  def trigger(): Int = Bar.secret()
        |object Bar:
        |  private def secret(): Int = 42
        |""".stripMargin
    checkBCode(source) { dir =>
      val barModuleCls = loadClassNode(dir.lookupName("Bar$.class", directory = false).input)

      methodNamed(barModuleCls, "secret").foreach { m =>
        assertTrue("method 'secret' in Bar$ still private after ExpandPrivate",
          (m.access & ACC_PRIVATE) == 0)
      }

      // Module-class expanded name: "Bar$" + "$$" + "secret" = "Bar$$$secret"
      val expandedMethod = methodNamed(barModuleCls, "Bar$$$secret")
      assertTrue("expanded method 'Bar$$$secret' not found in Bar$.class",
        expandedMethod.isDefined)
      assertTrue("'Bar$$$secret' must be public", (expandedMethod.get.access & ACC_PUBLIC) != 0)
      assertTrue("'Bar$$$secret' must not be private", (expandedMethod.get.access & ACC_PRIVATE) == 0)
    }
  }

  /** Companion object accessing a `private val` of the companion class.
   *
   *  Status quo: the private getter for `x` in `Baz` is renamed to `Baz$$x` and made
   *  public; the backing field itself remains private because only the accessor is widened.
   */
  @Test def companionObjectAccessesClassPrivateVal(): Unit = {
    val source =
      """class Baz:
        |  private val x: Int = 1
        |object Baz:
        |  def trigger(b: Baz): Int = b.x
        |""".stripMargin
    checkBCode(source) { dir =>
      val bazCls = loadClassNode(dir.lookupName("Baz.class", directory = false).input)

      val expandedGetter = methodNamed(bazCls, "Baz$$x")
      assertTrue("expanded getter 'Baz$$x' not found in Baz.class", expandedGetter.isDefined)
      assertTrue("'Baz$$x' must be public", (expandedGetter.get.access & ACC_PUBLIC) != 0)
      assertTrue("'Baz$$x' must not be private", (expandedGetter.get.access & ACC_PRIVATE) == 0)

      // Backing field should remain private — only the accessor is widened.
      val field = fieldNamed(bazCls, "x")
      assertTrue("backing field 'x' should exist", field.isDefined)
      assertTrue("backing field 'x' should remain private",
        (field.get.access & ACC_PRIVATE) != 0)
    }
  }

  // ── Tests under -Ynestmates ──────────────────────────────────────────────────────────────────

  /** Under -Ynestmates: inner class accesses outer private def without name mangling.
   *  The method keeps its original name and stays ACC_PRIVATE; the JVM allows the access
   *  via the NestHost/NestMembers classfile attributes.
   */
  @Test def nestmates_innerClassKeepsOriginalName(): Unit = {
    val source =
      """class Outer:
        |  private def secret(): Int = 42
        |  class Inner:
        |    def trigger(): Int = secret()
        |""".stripMargin
    WithNestmates.checkBCode(source) { dir =>
      val outerCls = loadClassNode(dir.lookupName("Outer.class", directory = false).input)

      // No mangled method should exist.
      assertTrue("mangled 'Outer$$secret' must NOT be present under -Ynestmates",
        methodNamed(outerCls, "Outer$$secret").isEmpty)

      // Original method must remain private.
      val secretMethod = methodNamed(outerCls, "secret")
      assertTrue("method 'secret' must exist under -Ynestmates", secretMethod.isDefined)
      assertTrue("method 'secret' must remain ACC_PRIVATE", (secretMethod.get.access & ACC_PRIVATE) != 0)

      // Inner class must call the original name.
      val innerCls = loadClassNode(dir.lookupName("Outer$Inner.class", directory = false).input)
      val trigger  = getMethod(innerCls, "trigger")
      val callsOriginal = trigger.instructions.iterator.asScala.exists {
        case m: MethodInsnNode => m.name == "secret"
        case _                 => false
      }
      assertTrue("trigger() must call 'secret' (not a mangled name)", callsOriginal)

      // NestHost attribute: Outer$Inner must name Outer as its host.
      assertTrue("Outer$Inner must have NestHost = Outer",
        innerCls.nestHostClass == "Outer")

      // NestMembers attribute: Outer must list Outer$Inner.
      assertTrue("Outer must have a NestMembers list", outerCls.nestMembers != null)
      assertTrue("Outer.NestMembers must contain Outer$Inner",
        outerCls.nestMembers.contains("Outer$Inner"))
    }
  }

  /** Under -Ynestmates: companion object accesses companion class private def without mangling. */
  @Test def nestmates_companionObjectKeepsClassPrivateName(): Unit = {
    val source =
      """class Foo:
        |  private def secret(): Int = 42
        |object Foo:
        |  def trigger(f: Foo): Int = f.secret()
        |""".stripMargin
    WithNestmates.checkBCode(source) { dir =>
      val fooCls = loadClassNode(dir.lookupName("Foo.class", directory = false).input)

      assertTrue("mangled 'Foo$$secret' must NOT be present under -Ynestmates",
        methodNamed(fooCls, "Foo$$secret").isEmpty)

      val secretMethod = methodNamed(fooCls, "secret")
      assertTrue("method 'secret' must exist in Foo", secretMethod.isDefined)
      assertTrue("method 'secret' in Foo must remain ACC_PRIVATE",
        (secretMethod.get.access & ACC_PRIVATE) != 0)

      // The module class Foo$ is a nestmate: its NestHost must point to Foo.
      val fooModuleCls = loadClassNode(dir.lookupName("Foo$.class", directory = false).input)
      assertTrue("Foo$ must have NestHost = Foo", fooModuleCls.nestHostClass == "Foo")

      // Foo (the host) must list Foo$ in its NestMembers.
      assertTrue("Foo must have NestMembers", fooCls.nestMembers != null)
      assertTrue("Foo.NestMembers must contain Foo$", fooCls.nestMembers.contains("Foo$"))
    }
  }

  /** Under -Ynestmates: companion class accesses companion object private def without mangling. */
  @Test def nestmates_companionClassKeepsObjectPrivateName(): Unit = {
    val source =
      """class Bar:
        |  def trigger(): Int = Bar.secret()
        |object Bar:
        |  private def secret(): Int = 42
        |""".stripMargin
    WithNestmates.checkBCode(source) { dir =>
      val barModuleCls = loadClassNode(dir.lookupName("Bar$.class", directory = false).input)

      assertTrue("mangled 'Bar$$$secret' must NOT be present under -Ynestmates",
        methodNamed(barModuleCls, "Bar$$$secret").isEmpty)

      val secretMethod = methodNamed(barModuleCls, "secret")
      assertTrue("method 'secret' must exist in Bar$", secretMethod.isDefined)
      assertTrue("method 'secret' in Bar$ must remain ACC_PRIVATE",
        (secretMethod.get.access & ACC_PRIVATE) != 0)
    }
  }

  /** Under -Ynestmates: a class nested in a companion object accessing a private member
   *  of the companion class.
   */
  @Test def nestmates_nestedInCompanionObjectAccessesClassPrivateDef(): Unit = {
    val source =
      """class Foo:
        |  private def secret(): Int = 42
        |object Foo:
        |  class Inner:
        |    def trigger(f: Foo): Int = f.secret()
        |""".stripMargin
    WithNestmates.checkBCode(source) { dir =>
      val fooCls = loadClassNode(dir.lookupName("Foo.class", directory = false).input)
      val innerCls = loadClassNode(dir.lookupName("Foo$Inner.class", directory = false).input)

      // Foo.secret must remain private.
      val secretMethod = methodNamed(fooCls, "secret")
      assertTrue("Foo.secret must remain ACC_PRIVATE", (secretMethod.get.access & ACC_PRIVATE) != 0)

      // Foo$Inner.trigger must call Foo.secret directly.
      val trigger = getMethod(innerCls, "trigger")
      val callsOriginal = trigger.instructions.iterator.asScala.exists {
        case m: MethodInsnNode => m.name == "secret" && m.owner == "Foo"
        case _                 => false
      }
      assertTrue("Foo$Inner.trigger must call 'secret' directly", callsOriginal)

      // Foo$Inner must have NestHost = Foo.
      assertTrue("Foo$Inner must have NestHost = Foo", innerCls.nestHostClass == "Foo")
    }
  }

  /** Under -Ynestmates: standalone class (no companion object).
   *  The class itself is the host.
   */
  @Test def nestmates_standaloneClass(): Unit = {
    val source =
      """class SoloClass:
        |  private def secret(): Int = 42
        |  class Inner:
        |    def trigger(): Int = secret()
        |""".stripMargin
    WithNestmates.checkBCode(source) { dir =>
      val soloCls = loadClassNode(dir.lookupName("SoloClass.class", directory = false).input)
      val innerCls = loadClassNode(dir.lookupName("SoloClass$Inner.class", directory = false).input)

      assertTrue("SoloClass must be its own host (no NestHost attribute)", soloCls.nestHostClass == null)
      assertTrue("SoloClass must list its inner in NestMembers",
        soloCls.nestMembers != null && soloCls.nestMembers.contains("SoloClass$Inner"))
      assertTrue("Inner must point to SoloClass as host", innerCls.nestHostClass == "SoloClass")
    }
  }

  /** Under -Ynestmates: standalone object (no companion class).
   *  The module class itself is the host.
   */
  @Test def nestmates_standaloneObject(): Unit = {
    val source =
      """object SoloObject:
        |  private def secret(): Int = 42
        |  class Inner:
        |    def trigger(): Int = secret()
        |""".stripMargin
    WithNestmates.checkBCode(source) { dir =>
      val soloModuleCls = loadClassNode(dir.lookupName("SoloObject$.class", directory = false).input)
      val innerCls = loadClassNode(dir.lookupName("SoloObject$Inner.class", directory = false).input)

      assertTrue("SoloObject$ must be its own host", soloModuleCls.nestHostClass == null)
      assertTrue("SoloObject$ must list its inner in NestMembers",
        soloModuleCls.nestMembers != null && soloModuleCls.nestMembers.contains("SoloObject$Inner"))
      assertTrue("Inner must point to SoloObject$ as host", innerCls.nestHostClass == "SoloObject$")
    }
  }
}
