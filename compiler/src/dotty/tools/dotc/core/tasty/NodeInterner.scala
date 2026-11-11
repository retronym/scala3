package dotty.tools
package dotc
package core
package tasty

import dotty.tools.tasty.TastyBuffer.{Addr, AddrWidth}
import dotty.tools.tasty.util.Util.dble

import scala.collection.mutable

/** Shares the nodes pickled into a `CompactableTastyBuffer` by their pickled form: the bytes of a node, with
 *  each part (a node pickled inside it) replaced by the address where the part is pickled in
 *  full, and the length fields left out, so that a part pickled in place and a reference to it
 *  give the same form. A reference is `sharedTag` followed by an address.
 *
 *  The pickler calls `enter` before a node and `intern` after, which replaces the node by a
 *  reference if an earlier one has its form; `writeReference` for a node pickled before;
 *  `lengthReserved` for each length field; and `markUnshareable` for what is not part of a form,
 *  or cannot be dropped. `TreePickler` uses it for types; `NodeInternerTest` has a worked example.
 */
final class NodeInterner(buf: CompactableTastyBuffer, sharedTag: Int):
  import buf.*

  // The nodes being pickled, innermost last: where each starts, and how many parts and length
  // fields were recorded, and how often `markUnshareable` was called, before it was entered
  private var starts = new Array[Int](16)
  private var partMarks = new Array[Int](16)
  private var lengthMarks = new Array[Int](16)
  private var unshareableMarks = new Array[Int](16)
  private var depth = 0

  // The parts of the nodes being pickled, in order: start, end, and where the part is pickled in full
  private var parts = new Array[Int](64)
  private var numParts = 0

  // The length fields in the nodes being pickled, in order
  private var lengths = new Array[Int](16)
  private var numLengths = 0

  // Counts the calls of `markUnshareable`: every node open at a call is left out of the sharing
  private var unshareables = 0

  /** The address of each node pickled in full so far, by its pickled form */
  private val byForm = mutable.HashMap[Form, Addr]()

  /** A node starts at `start`, the current address; `intern` or `abandon` closes it */
  def enter(start: Addr): Unit =
    if depth == starts.length then
      starts = dble(starts)
      partMarks = dble(partMarks)
      lengthMarks = dble(lengthMarks)
      unshareableMarks = dble(unshareableMarks)
    starts(depth) = start.index
    partMarks(depth) = numParts
    lengthMarks(depth) = numLengths
    unshareableMarks(depth) = unshareables
    depth += 1

  /** The innermost node is pickled. If an earlier node has the same form, its bytes are dropped
   *  and a reference to the earlier node is written instead. Returns the address where the node
   *  is pickled in full, which is where it started unless it is shared.
   */
  def intern(): Addr =
    depth -= 1
    val start = Addr(starts(depth))
    val end = currentAddr
    val partMark = partMarks(depth)
    // A node that is nothing but one of its parts has the form of that part, and is pickled in
    // full where the part is
    val isPart = numParts - partMark == 3 && parts(partMark) == start.index && parts(partMark + 1) == end.index
    val at =
      if isPart then Addr(parts(partMark + 2))
      else if unshareables > unshareableMarks(depth) || end == start then start
      else
        val key = formOf(start.index, end.index, partMark, lengthMarks(depth))
        byForm.get(key) match
          case Some(same) => same
          case None =>
            byForm(key.copy) = start
            start
    numParts = partMark
    numLengths = lengthMarks(depth)
    if isPart || at == start then part(start, end, at)
    else
      truncate(start)
      writeReference(at)
    at

  /** The innermost node was not pickled: an exception interrupted it. Forget it, and what was
   *  recorded inside it.
   */
  def abandon(): Unit =
    depth -= 1
    numParts = partMarks(depth)
    numLengths = lengthMarks(depth)

  /** Writes a reference to the node pickled in full at `at` */
  def writeReference(at: Addr): Unit =
    val start = currentAddr
    writeByte(sharedTag)
    writeRef(at)
    part(start, currentAddr, at)

  /** A length field was reserved at `at` */
  def lengthReserved(at: Addr): Unit =
    if depth > 0 then
      if numLengths == lengths.length then lengths = dble(lengths)
      lengths(numLengths) = at.index
      numLengths += 1

  /** Something was written that is not part of a form: the nodes being pickled are not shared */
  def markUnshareable(): Unit = unshareables += 1

  /** The node pickled from `start` to `end`, in full at `at`, is a part of the innermost open node */
  private def part(start: Addr, end: Addr, at: Addr): Unit =
    if depth > 0 then
      if numParts + 3 > parts.length then parts = dble(parts)
      parts(numParts) = start.index
      parts(numParts + 1) = end.index
      parts(numParts + 2) = at.index
      numParts += 3

  /** A form is looked up in `scratch`, to avoid a copy, and copied only when it is new */
  private class Form(val elems: Array[Int], val size: Int):
    def copy: Form = Form(java.util.Arrays.copyOf(elems, size), size)
    override def hashCode: Int =
      var h = size
      var i = 0
      while i < size do
        h = 31 * h + elems(i)
        i += 1
      h
    override def equals(that: Any): Boolean = that match
      case that: Form => java.util.Arrays.equals(elems, 0, size, that.elems, 0, that.size)
      case _ => false

  private var scratch = new Array[Int](64)

  /** The form of the node pickled from `start` to `end`, whose parts and length fields are
   *  recorded from `partMark` and `lengthMark` on. A part is encoded as a negative number, so
   *  that it cannot be mistaken for a byte. The bytes of a reference are a fixed-width field
   *  that is only compacted later, so they are the same for the same target.
   */
  private def formOf(start: Int, end: Int, partMark: Int, lengthMark: Int): Form =
    var size = 0
    def add(elem: Int) =
      if size == scratch.length then scratch = dble(scratch)
      scratch(size) = elem
      size += 1
    var i = start
    var part = partMark
    var length = lengthMark
    while i < end do
      if part < numParts && parts(part) == i then
        add(-1 - parts(part + 2))
        i = parts(part + 1)
        part += 3
      else if length < numLengths && lengths(length) == i then
        i += AddrWidth
        length += 1
      else
        add(bytes(i) & 0xff)
        i += 1
    Form(scratch, size)

  /** The open nodes by start, their parts as `start..end @ inFull`, their length fields, and the number of forms */
  override def toString: String =
    val open = (0 until depth).map(starts)
    val ps = (0 until numParts by 3).map(i => s"${parts(i)}..${parts(i + 1)} @ ${parts(i + 2)}")
    val ls = (0 until numLengths).map(lengths)
    s"NodeInterner(open = ${open.mkString("[", ", ", "]")}, parts = ${ps.mkString("[", ", ", "]")}, " +
      s"lengths = ${ls.mkString("[", ", ", "]")}, unshareable = $unshareables, forms = ${byForm.size})"
