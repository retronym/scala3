package dotty.tools.dotc.core

import Names.*
import Decorators.*

import org.junit.Test
import org.junit.Assert.*

class NameTest:
  @Test def test(): Unit = {
    val n = termName("hello")
    val tn = n.toTypeName
    assert(tn.toTermName eq n)
    assertFalse(n == tn)
    assertFalse(n eq tn)
    assertFalse(n == EmptyTermName)
  }
  @Test def internedWhileReachable(): Unit = {
    val n = termName("internedWhileReachable")
    assert(termName("internedWhileReachable") eq n)
    val dn = NameKinds.DefaultGetterName(n, 1)
    assert(NameKinds.DefaultGetterName(n, 1) eq dn)
  }

  @Test def unreachableNamesAreCollected(): Unit = {
    // Simple names and their derived names, both hanging off a base name that is held
    // forever by StdNames, must not be retained by the name tables.
    def create(i: Int): (java.lang.ref.WeakReference[Name], java.lang.ref.WeakReference[Name]) =
      val simple = termName(s"unreachableNamesAreCollected$i")
      val derived = NameKinds.DefaultGetterName(StdNames.nme.apply, 100000 + i)
      (java.lang.ref.WeakReference(simple), java.lang.ref.WeakReference(derived))
    val refs = (0 until 1000).map(create)
    var attempts = 0
    while refs.exists((s, d) => s.get != null || d.get != null) && attempts < 50 do
      System.gc()
      Thread.sleep(10)
      attempts += 1
    assertTrue("simple names retained", refs.forall(_._1.get == null))
    assertTrue("derived names retained", refs.forall(_._2.get == null))
  }
