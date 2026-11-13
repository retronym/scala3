package dotty.tools.dotc.util

import org.junit.Test
import org.junit.Assert.*

class WeakInternerTest:

  final class Key(val n: Int, val h: Int)

  final class Interner extends WeakInterner[Key](16):
    def intern(n: Int, h: Int): Key =
      val e = find(h, _.n == n)
      if e != null then e else add(Key(n, h), h)
    def chunkCount: Int = elems.length
    /** Intern (n, h), running `between` after the failed `find` and before the `add`, as a constructor that interns
     *  its parts would. */
    def internAround(n: Int, h: Int)(between: => Unit): Key =
      val e = find(h, _.n == n)
      if e != null then e
      else
        between
        add(Key(n, h), h)

  @Test def internsByPredicate(): Unit =
    val t = Interner()
    val a = t.intern(1, 7)
    val b = t.intern(2, 7) // same hash, different element
    assertNotSame(a, b)
    assertSame(a, t.intern(1, 7))
    assertSame(b, t.intern(2, 7))
    assertEquals(2, t.size)

  @Test def growsAcrossChunks(): Unit =
    val t = Interner()
    val n = WeakInterner.ChunkSize * 2 // needs four chunks at a load of at most 1/2
    // a poor hash, so that probe sequences run across chunk boundaries
    val keys = Array.tabulate(n)(i => t.intern(i, i * 31 >>> 3))
    assertTrue(t.chunkCount >= 4)
    for i <- 0 until n do assertSame(keys(i), t.intern(i, i * 31 >>> 3))
    assertEquals(n, t.size)

  @Test def dropsCollectedElements(): Unit =
    val t = Interner()
    val kept = Array.tabulate(1000)(i => t.intern(i, i))
    for i <- 1000 until 200000 do t.intern(i, i) // garbage once interned
    var tries = 0
    while t.size > 1000 && tries < 50 do
      System.gc()
      t.intern(-1, -1) // drains the reference queue
      tries += 1
    assumeCollected(t.size < 200000)
    for i <- 0 until 1000 do assertSame(kept(i), t.intern(i, i))

  @Test def nestedInternBetweenFindAndAdd(): Unit =
    val t = Interner()
    // same hash: the nested add takes the slot the outer find saw as free
    val a = t.internAround(1, 5)(t.intern(2, 5))
    // another hash: a nested failed find moves the free slot elsewhere
    val b = t.internAround(3, 6)(t.internAround(4, 9)(()))
    // and a nested find that fails without an add
    val c = t.internAround(5, 7)(t.find(7, _.n == 99))
    for (n, h, k) <- List((1, 5, a), (3, 6, b), (5, 7, c)) do assertSame(k, t.intern(n, h))
    assertEquals(2, t.intern(2, 5).n)
    assertEquals(4, t.intern(4, 9).n)
    assertEquals(5, t.size)

  @Test def clearForgetsEverything(): Unit =
    val t = Interner()
    val a = t.intern(1, 1)
    t.clear()
    assertEquals(0, t.size)
    assertNotSame(a, t.intern(1, 1))

  private def assumeCollected(cond: Boolean): Unit =
    org.junit.Assume.assumeTrue("no weak references were cleared", cond)
