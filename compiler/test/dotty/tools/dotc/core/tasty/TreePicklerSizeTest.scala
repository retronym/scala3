package dotty.tools.dotc.core.tasty

import org.junit.Test
import org.junit.Assert.*

import org.objectweb.asm.ClassReader

import scala.collection.mutable.ListBuffer

/** HotSpot does not JIT-compile a method of more than 8000 bytes of bytecode (`HugeMethodLimit`,
 *  while `DontCompileHugeMethods` is on, as it is by default), and such a method runs in the
 *  interpreter for good. `TreePickler.pickleTree` is one big match, in which `withLength` is
 *  inlined at every case, and it is within a few hundred bytes of the limit. Adding a few lines
 *  to `withLength` took it over, and made pickling three and a half times slower.
 */
class TreePicklerSizeTest:

  @Test def noMethodIsHuge(): Unit =
    val sizes = codeSizes(classOf[TreePickler])
    val huge = sizes.filter(_._2 > 8000)
    assertTrue(s"HotSpot will not compile methods over 8000 bytes: ${huge.mkString(", ")}", huge.isEmpty)
    assertTrue(sizes.exists(_._1 == "pickleTree"))

  /** The name and bytecode length of each method, read from the class file */
  private def codeSizes(cls: Class[?]): List[(String, Int)] =
    val in = cls.getResourceAsStream(cls.getSimpleName + ".class")
    val cr = try ClassReader(in) finally in.close()
    val buf = new Array[Char](cr.getMaxStringLength)
    val sizes = ListBuffer[(String, Int)]()
    var off = cr.header + 6 // access flags, this and super class
    off += 2 + 2 * cr.readUnsignedShort(off) // interfaces
    for isMethod <- List(false, true) do
      val members = cr.readUnsignedShort(off)
      off += 2
      for _ <- 0 until members do
        val name = cr.readUTF8(off + 2, buf)
        off += 6 // access flags, name, descriptor
        val attributes = cr.readUnsignedShort(off)
        off += 2
        for _ <- 0 until attributes do
          if isMethod && cr.readUTF8(off, buf) == "Code" then
            sizes += ((name, cr.readInt(off + 6 + 4))) // code_length follows max_stack and max_locals
          off += 6 + cr.readInt(off + 2)
    sizes.toList
