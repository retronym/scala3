package dotty.tools.dotc.classpath

import org.junit.Test
import org.junit.Assert.*

import dotty.tools.io.virtualDirectory

class VirtualDirectoryClassPathTest {
  @Test def nonRootPackage(): Unit = {
    val root = virtualDirectory("(memory)")
    val c = root.subdirectoryNamed("p").subdirectoryNamed("q").fileNamed("C.class")
    val cp = new VirtualDirectoryClassPath(root)

    assertTrue(cp.hasPackage("p"))
    assertTrue(cp.hasPackage("p.q"))
    assertFalse(cp.hasPackage("r"))
    assertEquals(List("p"), cp.packages("").toList)
    assertEquals(List("p.q"), cp.packages("p").toList)
    assertEquals(List("C"), cp.classes("p.q").map(_.name).toList)
    assertEquals(Some(c), cp.findClassFile("p.q.C"))

    // AggregateClassPath only consults the entries for which `hasPackage` holds
    val aggregate = AggregateClassPath(Seq(cp))
    assertEquals(Some(c), aggregate.findClassFile("p.q.C"))
  }
}
