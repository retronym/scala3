package dotty.tools.dotc.core.tasty

import dotty.tools.tasty.TastyFormat.SHAREDtype
import dotty.tools.tasty.TastyBuffer.Addr

import org.junit.Test
import scala.util.control.NonFatal
import org.junit.Assert.*

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer

/** Tests `NodeInterner` with a pickler for small trees that uses it as `TreePickler.pickleType`
 *  does. The trees are case classes, so that equal trees need not be the same object.
 *
 *  The interner shares equal nodes without comparing them. A node that is the same object as one
 *  pickled before (`eq`) never reaches it: the pickler's identity map finds the earlier address
 *  and a reference is written. Any other node is pickled, and then `intern` hashes its form: the
 *  bytes, with each part replaced by the address where the part is pickled in full, and the
 *  length fields left out. Hashing the form wins twice over comparing nodes:
 *
 *   - Cost. Comparing each node with every earlier one would be quadratic. A form costs one pass
 *     over the node's own bytes and a hash lookup, and as a part collapses to one address, a
 *     parent's form does not repeat its children's bytes.
 *   - Equality. The parts were interned first, so two nodes with equal parts have equal forms by
 *     construction, whether their parts were written in place or as references. Nodes are equal
 *     when they pickle the same, which is the relation wanted: `TreePickler` shares types that
 *     are equal in meaning but not `==`, such as a package referred to as a `TermRef` and as a
 *     `ThisType`.
 *
 *  A worked example, `pickle(Node(11, List(Node(10, List(l1, Leaf(2))), Node(10, List(Leaf(1), Leaf(2))), l1)))`,
 *  where the two inner nodes are different objects and `l1` is one object. The tree, and where
 *  each node ends up (`[len=n]` is a length field, `SHARED -> a` a reference):
 *
 *      N11                                          0   N11 [len=17]
 *      ├─ N10                                       5     N10 [len=2]
 *      │  ├─ L1                                    10       L1
 *      │  └─ L2                                    11       L2
 *      ├─ N10   ==, not eq: pickled, then interned 12     SHARED -> 5
 *      │  ├─ L1   (pickled, interned, dropped)
 *      │  └─ L2   (pickled, interned, dropped)
 *      └─ L1    eq: found in the identity map      17     SHARED -> 10
 *
 *  The second `N10` never has the bytes of the first. Before `intern` its parts have already
 *  been replaced by references, and its form is computed from those:
 *
 *      before:  N10 [len=10] SHARED -> 10 SHARED -> 11     form: N10 <10> <11>, the form of the node at 5
 *      after:   SHARED -> 5                                 15 bytes dropped for a 5-byte reference
 *
 *  So a node is compared only after its parts are shared, and a node that is dropped consists of
 *  references only. After `compactify` the fields shrink and the references move with their
 *  targets: `N11 [len=8] N10 [len=2] L1 L2 SHARED -> 2 SHARED -> 4`. Pickling the same tree built
 *  from copies, or hash-consed, gives the same forms and the same bytes.
 */
class NodeInternerTest:
  import NodeInternerTest.*

  @Test def sharesEqualTrees(): Unit =
    val tree = Node(11, List(Node(10, List(Leaf(1), Leaf(2))), Node(10, List(Leaf(1), Leaf(2))), Leaf(3)))
    val p = pickle(tree)
    assertEquals(tree, p.decode())
    assertEquals(5, p.numWritten) // 11, 10, 1, 2, 3; the second 10 is a reference to the first

  @Test def wrapperHasTheFormOfItsPart(): Unit =
    val tree = Node(10, List(Wrapper(Leaf(1)), Leaf(1), Wrapper(Wrapper(Leaf(1)))))
    val p = pickle(tree)
    assertEquals(tree.unwrapped, p.decode())
    assertEquals(2, p.numWritten) // 10 and 1

  @Test def unshareableTreesAreNotShared(): Unit =
    val tree = Node(10, List(Opaque(21), Opaque(21), Node(11, List(Opaque(21))), Node(11, List(Opaque(21)))))
    val p = pickle(tree)
    assertEquals(tree, p.decode())
    assertEquals(7, p.numWritten)

  /** A node abandoned after an exception is forgotten, and the nodes around it are closed in pairs */
  @Test def abandonedNodeIsForgotten(): Unit =
    val buf = new CompactableTastyBuffer
    val interner = NodeInterner(buf, SHAREDtype)
    interner.enter(buf.currentAddr)      // outer
    buf.writeByte(10)
    val length = buf.reserveRef(relative = true)
    interner.lengthReserved(length)
    interner.enter(buf.currentAddr)      // inner, interrupted
    buf.writeByte(11)
    interner.abandon()
    buf.writeByte(1)                     // in place of the inner node
    buf.fillRef(length, relative = true)
    assertEquals(Addr(0), interner.intern())
    assertEquals("NodeInterner(open = [], parts = [], lengths = [], unshareable = 0, forms = 1)", interner.toString)

  /** The pickle does not depend on whether equal subtrees are the same object, unless they are
   *  unshareable by form: those are still shared by identity, as `TreePickler` does.
   */
  @Test def sharingDoesNotDependOnIdentity(): Unit =
    forRandomTrees: tree =>
      if !tree.exists(_.isInstanceOf[Opaque]) then
        val a = pickle(tree)
        val b = pickle(tree.copied)
        val c = pickle(tree.hashConsed(new java.util.HashMap))
        for p <- List(b, c) do assertArrayEquals(a.bytes, p.bytes)

  @Test def randomTreesRoundTripAndShareEveryRepeat(): Unit =
    forRandomTrees: tree =>
      val p = pickle(tree)
      assertEquals(tree.unwrapped, p.decode())
      if !tree.exists(_.isInstanceOf[Opaque]) then
        // every distinct subtree is written once
        val distinct = new java.util.HashSet[Tree]
        def collect(t: Tree): Unit = t match
          case Wrapper(child) => collect(child)
          case Node(_, children) => distinct.add(t.unwrapped); children.foreach(collect)
          case leaf => distinct.add(leaf)
        collect(tree)
        assertEquals(distinct.size, p.numWritten)

object NodeInternerTest:

  def forRandomTrees(check: Tree => Unit): Unit =
    for seed <- 0 until 2000 do
      val tree = genTree(new scala.util.Random(seed), depth = 1 + seed % 7)
      try check(tree)
      catch case NonFatal(ex) => throw new AssertionError(s"seed $seed: $ex\n$tree", ex)

  sealed trait Tree:
    def unwrapped: Tree = this match
      case Wrapper(child) => child.unwrapped
      case Node(tag, children) => Node(tag, children.map(_.unwrapped))
      case leaf => leaf
    def copied: Tree = this match
      case Wrapper(child) => Wrapper(child.copied)
      case Node(tag, children) => Node(tag, children.map(_.copied))
      case Leaf(tag) => Leaf(tag)
      case Opaque(tag) => Opaque(tag)
    def hashConsed(table: java.util.HashMap[Tree, Tree]): Tree =
      val t = this match
        case Wrapper(child) => Wrapper(child.hashConsed(table))
        case Node(tag, children) => Node(tag, children.map(_.hashConsed(table)))
        case leaf => leaf
      table.computeIfAbsent(t, _ => t)
    def exists(p: Tree => Boolean): Boolean = p(this) || (this match
      case Wrapper(child) => child.exists(p)
      case Node(_, children) => children.exists(_.exists(p))
      case _ => false)
  case class Leaf(tag: Int) extends Tree // tags 1 to 9
  case class Node(tag: Int, children: List[Tree]) extends Tree // tags 10 to 19, with a length field
  /** Pickled as its child, as a `LazyRef` is pickled as its underlying type */
  case class Wrapper(child: Tree) extends Tree
  /** A leaf whose pickling makes the trees around it unshareable, as a forward symbol reference does */
  case class Opaque(tag: Int) extends Tree // tags 20 to 29

  assert(SHAREDtype >= 30)

  def genTree(rnd: scala.util.Random, depth: Int): Tree =
    rnd.nextInt(if depth <= 1 then 4 else 10) match
      case 0 | 1 | 2 => Leaf(1 + rnd.nextInt(3)) // few leaves, so that repeats are likely
      case 3 => if rnd.nextInt(8) == 0 then Opaque(20 + rnd.nextInt(2)) else Leaf(1 + rnd.nextInt(3))
      case 4 => Wrapper(genTree(rnd, depth - 1))
      case _ => Node(10 + rnd.nextInt(2), List.fill(rnd.nextInt(4))(genTree(rnd, depth - 1)))

  /** The compacted pickle of a tree, and how many trees other than wrappers are written out, the rest being references */
  class Pickled(val bytes: Array[Byte], val numWritten: Int):
    def decode(): Tree =
      var pos = 0
      def nat(): Int =
        var x = 0
        while bytes(pos) >= 0 do
          x = x * 128 + bytes(pos)
          pos += 1
        x = x * 128 + (bytes(pos) & 0x7f)
        pos += 1
        x
      def tree(): Tree =
        val tag = bytes(pos) & 0xff
        pos += 1
        if tag == SHAREDtype then
          val target = nat()
          val resume = pos
          pos = target
          val t = tree()
          pos = resume
          t
        else if tag < 10 then Leaf(tag)
        else if tag >= 20 then Opaque(tag)
        else
          val end = nat() + pos
          val children = new ArrayBuffer[Tree]
          while pos < end do children += tree()
          assertEquals(end, pos)
          Node(tag, children.toList)
      val t = tree()
      assertEquals(bytes.length, pos)
      t

  /** Pickles a tree as `TreePickler.pickleType` pickles a type: a tree pickled before is a
   *  reference, and any other is pickled and then interned.
   */
  def pickle(root: Tree): Pickled =
    val buf = new CompactableTastyBuffer
    val interner = NodeInterner(buf, SHAREDtype)
    val pickled = new IdentityHashMap[Tree, Addr]
    var numWritten = 0

    def go(t: Tree): Unit =
      val prev: Addr | Null = pickled.get(t)
      if prev != null then interner.writeReference(prev)
      else
        val start = buf.currentAddr
        interner.enter(start)
        pickled.put(t, start)
        t match
          case Wrapper(child) => go(child)
          case Leaf(tag) => buf.writeByte(tag)
          case Opaque(tag) =>
            interner.markUnshareable()
            buf.writeByte(tag)
          case Node(tag, children) =>
            buf.writeByte(tag)
            val length = buf.reserveRef(relative = true)
            interner.lengthReserved(length)
            children.foreach(go)
            buf.fillRef(length, relative = true)
        val at = interner.intern()
        pickled.put(t, at)
        if at == start && !t.isInstanceOf[Wrapper] then numWritten += 1

    go(root)
    buf.compactify(new ScratchData)
    Pickled(buf.bytes.take(buf.length), numWritten)
