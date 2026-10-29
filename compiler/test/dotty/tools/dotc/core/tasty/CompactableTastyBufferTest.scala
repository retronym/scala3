package dotty.tools.dotc.core.tasty

import dotty.tools.tasty.TastyBuffer.{Addr, AddrWidth}

import org.junit.Test
import org.junit.Assert.*
import scala.util.control.NonFatal

import scala.collection.mutable.ArrayBuffer

/** Tests `CompactableTastyBuffer` against a model. `RefBuffer` is what a client of the buffer can do;
 *  `CompactableTastyBuffer` and a slow and simple `ModelBuffer` both implement it, a random program of
 *  operations is run on each, and the two must decode to the same items: data bytes, and references that
 *  name the item they refer to. As in TASTy, a reference field follows a tag that says what it is, so that
 *  the bytes can be read back without knowing what was written. The model knows nothing of compaction, so
 *  `adjusted` is checked against where the decoder finds each item in the compacted buffer.
 *
 *  There is no property testing library on the compiler's test classpath, so the programs are generated
 *  from a seeded `Random`.
 */
class CompactableTastyBufferTest:
  import CompactableTastyBufferTest.*, Op.*

  @Test def empty(): Unit =
    val buf = new CompactableTastyBuffer
    buf.compactify(new ScratchData)
    assertEquals(0, buf.length)

  @Test def lengthAndBackwardReference(): Unit =
    val buf = new CompactableTastyBuffer
    buf.writeByte(7)
    val start = buf.currentAddr
    val len = buf.reserveRef(relative = true)
    buf.writeByte(8)
    buf.writeRef(start)
    buf.fillRef(len, relative = true)
    val scratch = new ScratchData
    val before = buf.length
    buf.compactify(scratch)
    // both references shrink from AddrWidth bytes to one
    assertEquals(before - 2 * (AddrWidth - 1), buf.length)
    assertEquals(Seq(7, 0x82, 8, 0x81), bytesOf(buf))
    assertEquals(Addr(0), buf.adjusted(Addr(0), scratch))
    assertEquals(Addr(1), buf.adjusted(start, scratch))
    assertEquals(Addr(buf.length), buf.adjusted(Addr(before), scratch))

  @Test def forwardReference(): Unit =
    val buf = new CompactableTastyBuffer
    val fwd = buf.reserveRef(relative = false)
    buf.writeByte(1)
    buf.fillRef(fwd, relative = false)
    buf.writeByte(2)
    buf.compactify(new ScratchData)
    assertEquals(Seq(0x82, 1, 2), bytesOf(buf))

  @Test def truncateDropsReferences(): Unit =
    val buf = new CompactableTastyBuffer
    buf.writeByte(1)
    val mark = buf.currentAddr
    val len = buf.reserveRef(relative = true)
    buf.writeRef(Addr(0))
    buf.fillRef(len, relative = true)
    buf.truncate(mark)
    buf.writeByte(2)
    buf.compactify(new ScratchData)
    assertEquals(Seq(1, 2), bytesOf(buf))

  /** A length field left open by an exception is dropped with the bytes it was reserved in */
  @Test def truncateDropsOpenLengths(): Unit =
    val buf = new CompactableTastyBuffer
    val outer = buf.reserveRef(relative = true)
    val mark = buf.currentAddr
    buf.reserveRef(relative = true) // never filled: an exception
    buf.writeByte(1)
    buf.truncate(mark)
    buf.writeByte(2)
    buf.fillRef(outer, relative = true) // fills the outer field, not the dropped one
    assertEquals("CompactableTastyBuffer(length = 5, refs = [0L -> 0], open lengths = [])", buf.toString)

  /** The model of a program, by hand */
  @Test def modelOfAProgram(): Unit =
    val model = ModelBuffer()
    val run = Interpreter(model)
    run(List(Lit(7), Len(List(Lit(8), Back(0))), Fwd, Lit(9), Fill(0)))
    assertEquals(List(Data(7), Ref(true, 4), Data(8), Ref(false, 0), Ref(false, 6), Data(9)), model.decoded)
    assertEquals(List(0, 1, 6, 7, 12, 17, 18), run.itemAddrs.map(_.index).toList)

  /** The decoder rejects a reference that leads elsewhere */
  @Test def decoderIsNotVacuous(): Unit =
    val real = RealBuffer()
    Interpreter(real)(List(Lit(7), Lit(8), Back(0)))
    real.buf.compactify(new ScratchData)
    assertEquals(Seq(7, 8, REF, 0x80), bytesOf(real.buf))
    real.buf.bytes(3) = 0x81.toByte // the reference now leads to item 1
    assertEquals(List(Data(7), Data(8), Ref(false, 1)), real.decoded)
    real.buf.bytes(3) = 0x83.toByte // the reference now leads inside item 2
    try
      real.decoded
      fail("a wrong reference was accepted")
    catch case _: AssertionError => ()

  @Test def randomProgramsCompactToTheirModel(): Unit =
    forRandomPrograms(truncating = false): program =>
      val (model, real, run) = runBoth(program)
      val scratch = new ScratchData
      real.buf.compactify(scratch)
      assertEquals(model.decoded, real.decoded)
      checkAdjusted(real, run.itemAddrs, scratch)

  /** Writing something and truncating it leaves the buffer as if it was never written */
  @Test def truncatedIsAsNeverWritten(): Unit =
    forRandomPrograms(truncating = true): program =>
      val (model, real, run) = runBoth(program)
      val (modelWithout, realWithout, _) = runBoth(program.flatMap(_.withoutTruncated))
      assertEquals(modelWithout.decoded, model.decoded)
      assertEquals(bytesOf(realWithout.buf), bytesOf(real.buf))
      val scratch = new ScratchData
      real.buf.compactify(scratch)
      realWithout.buf.compactify(new ScratchData)
      assertEquals(model.decoded, real.decoded)
      assertEquals(bytesOf(realWithout.buf), bytesOf(real.buf))
      checkAdjusted(real, run.itemAddrs, scratch)

  /** `adjusted` never moves an address forward, and keeps the order of the items */
  @Test def adjustedIsMonotone(): Unit =
    forRandomPrograms(truncating = true): program =>
      val real = RealBuffer()
      val run = Interpreter(real)
      run(program)
      val scratch = new ScratchData
      real.buf.compactify(scratch)
      var last = -1
      for addr <- run.itemAddrs do
        val x = real.buf.adjusted(addr, scratch).index
        assertTrue(s"adjusted($addr) = $x, after $last", x >= last)
        assertTrue(x <= addr.index)
        last = x
      assertEquals(real.buf.length, last)

object CompactableTastyBufferTest:

  // The grammar of the test, in the manner of TASTy: an item is a data byte, or a tag followed by a
  // field, which is a Nat once compacted. The tags say what the field is, so that the bytes can be
  // read back without knowing what was written.

  val LEN = 126 // a length: from the end of its field to the end of what it covers
  val REF = 127 // a reference: the address of an item

  /** What a buffer holds, as a sequence of items */
  sealed trait Item
  case class Data(b: Int) extends Item // 1 to 125: not a tag, and not zero, which compaction skips
  /** A reference to the start of the item with index `target` (the number of items if it refers to the end) */
  case class Ref(relative: Boolean, target: Int) extends Item
  val Unfilled = -1

  /** Where each item of a compacted buffer starts, and its end */
  def itemStarts(buf: CompactableTastyBuffer): IndexedSeq[Int] =
    val starts = ArrayBuffer[Int]()
    var pos = 0
    while pos < buf.length do
      starts += pos
      val tag = buf.bytes(pos) & 0xff
      pos = if tag == LEN || tag == REF then buf.skipNat(Addr(pos + 1)).index else pos + 1
    starts += pos
    starts.toIndexedSeq

  /** The items of a compacted buffer. Each field must lead to the start of an item. */
  def decode(buf: CompactableTastyBuffer): List[Item] =
    val starts = itemStarts(buf)
    val items =
      for k <- 0 until starts.size - 1 yield
        val tag = buf.bytes(starts(k)) & 0xff
        if tag != LEN && tag != REF then Data(tag)
        else
          val value = buf.getNat(Addr(starts(k) + 1))
          val target = if tag == LEN then starts(k + 1) + value else value
          val i = java.util.Arrays.binarySearch(starts.toArray, target)
          assertTrue(s"the reference at item $k leads to $target, where no item starts", i >= 0)
          Ref(relative = tag == LEN, i)
    items.toList

  def bytesOf(buf: CompactableTastyBuffer): Seq[Int] = (0 until buf.length).map(i => buf.bytes(i) & 0xff)

  // The two implementations

  /** What a client of `CompactableTastyBuffer` can do, and what it must be able to read back */
  trait RefBuffer:
    def currentAddr: Addr
    def writeByte(b: Int): Unit
    def reserveRef(relative: Boolean): Addr
    def writeRef(target: Addr): Unit
    def fillRef(at: Addr, relative: Boolean): Unit
    def truncate(addr: Addr): Unit
    def decoded: List[Item]

  class RealBuffer extends RefBuffer:
    val buf = new CompactableTastyBuffer
    export buf.{currentAddr, writeByte, reserveRef, writeRef, fillRef, truncate}
    def decoded = decode(buf)

  /** The slow and simple implementation: the items, each at a known address */
  class ModelBuffer extends RefBuffer:
    private val items = ArrayBuffer[Item]()
    private val addrs = ArrayBuffer[Int]()
    private var length = 0

    def currentAddr = Addr(length)
    def decoded = items.toList

    private def itemAt(addr: Addr): Int =
      if addr.index == length then items.size
      else
        val i = addrs.indexOf(addr.index)
        assert(i >= 0, s"no item starts at $addr")
        i

    def writeByte(b: Int) =
      if b == LEN || b == REF then length += 1 // a tag: the item starts here, and is its field
      else
        addrs += length
        items += Data(b)
        length += 1
    private def field(item: Item): Unit =
      addrs += length - 1 // the tag before it
      items += item
      length += AddrWidth
    def reserveRef(relative: Boolean) =
      val at = currentAddr
      field(Ref(relative, Unfilled))
      at
    def writeRef(target: Addr) =
      field(Ref(relative = false, Unfilled)) // first, as the reference may be to itself
      items(items.size - 1) = Ref(relative = false, itemAt(target))
    def fillRef(at: Addr, relative: Boolean) =
      val i = itemAt(at - 1)
      assert(items(i) == Ref(relative, Unfilled), s"item $i is ${items(i)}")
      items(i) = Ref(relative, items.size)
    def truncate(addr: Addr) =
      val n = itemAt(addr)
      items.dropRightInPlace(items.size - n)
      addrs.dropRightInPlace(addrs.size - n)
      length = addr.index

  // Programs, run on a buffer

  /** The operations of a program. Where an operation makes a choice (`k`), the choice is made
   *  when it is run, from what is available then.
   */
  enum Op:
    case Lit(b: Int)
    /** A backward reference to the start of one of the items written so far, or to the end */
    case Back(k: Int)
    /** A length field around the operations */
    case Len(body: List[Op])
    /** A forward reference, filled later by `Fill` or at the end */
    case Fwd
    /** Fills one of the pending forward references with the current address */
    case Fill(k: Int)
    /** Writes the operations and truncates them again, as `TreePickler` does with a type that
     *  turns out to be shared. The forward references made inside are filled before the end.
     */
    case Truncated(body: List[Op])

    def withoutTruncated: List[Op] = this match
      case Len(body) => List(Len(body.flatMap(_.withoutTruncated)))
      case Truncated(_) => Nil
      case op => List(op)
  import Op.*

  def genProgram(rnd: scala.util.Random, size: Int, truncating: Boolean): List[Op] =
    def gen(budget: Int, depth: Int): List[Op] =
      val ops = new ArrayBuffer[Op]
      var left = budget
      while left > 0 do
        left -= 1
        rnd.nextInt(12) match
          case 0 | 1 | 2 => ops += Lit(1 + rnd.nextInt(125))
          case 3 | 4 | 5 => ops += Back(rnd.nextInt(1000))
          case 6 | 7 if depth < 5 =>
            val n = rnd.nextInt(left + 1)
            left -= n
            ops += Len(gen(n, depth + 1))
          case 8 => ops += Fwd
          case 9 => ops += Fill(rnd.nextInt(1000))
          case 10 if truncating && depth < 5 =>
            val n = rnd.nextInt(left + 1)
            left -= n
            ops += Truncated(gen(n, depth + 1))
          case _ => ops += Lit(1 + rnd.nextInt(125))
      ops.toList
    gen(size, 0)

  /** Runs a program on a buffer, keeping where each item starts and the forward references still to be filled */
  class Interpreter(buf: RefBuffer):
    val itemAddrs = ArrayBuffer[Addr]()
    private val pending = ArrayBuffer[Addr]()

    def apply(program: List[Op]): Unit =
      runOps(program)
      while pending.nonEmpty do fill(0)
      itemAddrs += buf.currentAddr // the end

    private def fill(k: Int): Unit = buf.fillRef(pending.remove(k), relative = false)

    private def item(tag: Int = -1): Unit =
      itemAddrs += buf.currentAddr
      if tag >= 0 then buf.writeByte(tag)

    private def runOps(ops: List[Op]): Unit = ops.foreach:
      case Lit(b) =>
        item()
        buf.writeByte(b)
      case Back(k) =>
        val target = (itemAddrs :+ buf.currentAddr)(k % (itemAddrs.size + 1))
        item(REF)
        buf.writeRef(target)
      case Len(body) =>
        item(LEN)
        val at = buf.reserveRef(relative = true)
        runOps(body)
        buf.fillRef(at, relative = true)
      case Fwd =>
        item(REF)
        pending += buf.reserveRef(relative = false)
      case Fill(k) => if pending.nonEmpty then fill(k % pending.size)
      case Truncated(body) =>
        val mark = buf.currentAddr
        val numItems = itemAddrs.size
        // the forward references pending outside cannot be filled inside, as they would then refer to dropped bytes
        val outer = pending.toList
        pending.clear()
        runOps(body)
        while pending.nonEmpty do fill(0)
        pending ++= outer
        buf.truncate(mark)
        itemAddrs.dropRightInPlace(itemAddrs.size - numItems)

  val NumPrograms = 3000

  def forRandomPrograms(truncating: Boolean)(check: List[Op] => Unit): Unit =
    for seed <- 0 until NumPrograms do
      val program = genProgram(new scala.util.Random(seed), size = 5 + seed % 60, truncating)
      try check(program)
      catch case NonFatal(ex) =>
        throw new AssertionError(s"seed $seed: $ex\n${program.mkString("\n")}", ex)

  def runBoth(program: List[Op]): (ModelBuffer, RealBuffer, Interpreter) =
    val model = ModelBuffer()
    val real = RealBuffer()
    Interpreter(model)(program)
    val run = Interpreter(real)
    run(program)
    (model, real, run)

  /** `adjusted` must map each item's address before compaction to where the decoder finds it after */
  def checkAdjusted(real: RealBuffer, itemAddrs: collection.Seq[Addr], scratch: ScratchData): Unit =
    val starts = itemStarts(real.buf)
    assertEquals(itemAddrs.size, starts.size)
    for k <- starts.indices do
      assertEquals(s"adjusted(address of item $k)", starts(k), real.buf.adjusted(itemAddrs(k), scratch).index)

