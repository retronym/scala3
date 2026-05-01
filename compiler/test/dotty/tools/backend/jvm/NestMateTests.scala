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
}
