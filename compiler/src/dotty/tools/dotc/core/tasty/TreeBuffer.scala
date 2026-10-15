package dotty.tools
package dotc
package core
package tasty

import dotty.tools.tasty.util.Util.dble
import dotty.tools.tasty.TastyBuffer
import TastyBuffer.{Addr, NoAddr, AddrWidth}

import util.Util.bestFit
import config.Printers.pickling
import ast.untpd.Tree
import java.util.Arrays

class TreeBuffer extends TastyBuffer(50000) {

  private inline val ItemsOverOffsets = 2
  private val initialOffsetSize = bytes.length / (AddrWidth * ItemsOverOffsets)
  private var offsets = new Array[Int](initialOffsetSize)
  private var isRelative = new Array[Boolean](initialOffsetSize)
  private var numOffsets = 0

  // For each offset, `offsetIndexBefore` its reference's target: recorded when the reference is filled, so that
  // `compactify` finds the target's delta without a search
  private var targetIdx = new Array[Int](initialOffsetSize)

  // The indices of the length fields reserved but not yet filled. Lengths nest, so they are filled in LIFO order.
  private var openLengths = new Array[Int](64)
  private var numOpenLengths = 0

  /** A map from trees to the address at which a tree is pickled. */
  private val treeAddrs = util.IntMap[Tree](initialCapacity = 8192)

  def registerTreeAddr(tree: Tree): Addr =
    val idx = treeAddrs(tree)
    if idx < 0 then
      treeAddrs(tree) = currentAddr.index
      currentAddr
    else
      Addr(idx)

  def addrOfTree(tree: Tree): Addr =
    val idx = treeAddrs(tree)
    if idx < 0 then NoAddr else Addr(idx)

  private def offset(i: Int): Addr = Addr(offsets(i))

  private def keepOffset(relative: Boolean): Unit = {
    if (numOffsets == offsets.length) {
      offsets = dble(offsets)
      isRelative = dble(isRelative)
      targetIdx = dble(targetIdx)
    }
    offsets(numOffsets) = length
    isRelative(numOffsets) = relative
    numOffsets += 1
  }

  /** Reserve space for a reference, to be adjusted later */
  def reserveRef(relative: Boolean): Addr = {
    val addr = currentAddr
    keepOffset(relative)
    if relative then
      if numOpenLengths == openLengths.length then openLengths = dble(openLengths)
      openLengths(numOpenLengths) = numOffsets - 1
      numOpenLengths += 1
    reserveAddr()
    addr
  }

  /** Write reference right adjusted into freshly reserved field. */
  def writeRef(target: Addr): Unit = {
    keepOffset(relative = false)
    // `target` is a backward reference, usually to something recent: search back from the latest offset
    targetIdx(numOffsets - 1) = offsetIndexBefore(target, hint = numOffsets - 1)
    fillAddr(reserveAddr(), target)
  }

  /** Fill previously reserved field with a reference to the current address */
  def fillRef(at: Addr, relative: Boolean): Unit = {
    val i =
      if relative then
        numOpenLengths -= 1
        openLengths(numOpenLengths)
      else offsetIndexBefore(at + 1)
    assert(offsets(i) == at.index, s"reference at $at is not offset #$i")
    // Every offset kept so far lies before `currentAddr`, so the last of them is the one before it
    targetIdx(i) = numOffsets - 1
    fillAddr(at, if relative then currentAddr.relativeTo(at) else currentAddr)
  }

  /** The index of the last offset before the given address, or -1 if there is none.
   *  @param hint  an index at or near the result, see `bestFit`
   */
  private def offsetIndexBefore(at: Addr, hint: Int = -1): Int =
    bestFit(offsets, numOffsets, at.index - 1, hint)

  private def deltaOf(idx: Int, scratch: ScratchData): Int =
    if idx < 0 then 0 else scratch.delta(idx)

  /** The address to which `x` is translated under compression */
  def adjusted(x: Addr, scratch: ScratchData): Addr = x - deltaOf(offsetIndexBefore(x), scratch)

  /** Final assembly, involving the following steps:
   *   - compute deltas
   *   - adjust deltas until additional savings are < 1% of total
   *   - adjust offsets according to the adjusted deltas
   *   - shrink buffer, skipping zeroes.
   */
  def compactify(scratch: ScratchData): Unit =

    def reserve(arr: Array[Int]) =
      if arr.length < numOffsets then
        new Array[Int](numOffsets)
      else
        Arrays.fill(arr, 0, numOffsets, 0)
        arr

  /** Compute all shift-deltas */
    def computeDeltas() = {
      scratch.delta = reserve(scratch.delta)
      var lastDelta = 0
      var i = 0
      while (i < numOffsets) {
        val off = offset(i)
        val skippedOff = skipZeroes(off)
        val skippedCount = skippedOff.index - off.index
        assert(skippedCount < AddrWidth, s"unset field at position $off")
        lastDelta += skippedCount
        scratch.delta(i) = lastDelta
        i += 1
      }
    }

    /** The absolute or relative adjusted address at index `i` of `offsets` array*/
    def adjustedOffset(i: Int): Addr = {
      val at = offset(i)
      val original = getAddr(at)
      val targetDelta = deltaOf(targetIdx(i), scratch)
      // A relative reference is a length from the end of its field, which lies between offsets(i) and
      // offsets(i + 1) and so moves by delta(i)
      if isRelative(i) then original + scratch.delta(i) - targetDelta
      else original - targetDelta
    }

    /** Adjust all offsets according to previously computed deltas */
    def adjustOffsets(): Unit =
      var i = 0
      while i < numOffsets do
        val corrected = adjustedOffset(i)
        fillAddr(offset(i), corrected)
        i += 1

    /** Adjust deltas to also take account references that will shrink (and thereby
     *  generate additional zeroes that can be skipped) due to previously
     *  computed adjustments.
     */
    def adjustDeltas(): Int = {
      scratch.delta1 = reserve(scratch.delta1)
      var lastDelta = 0
      var i = 0
      while i < numOffsets do
        val corrected = adjustedOffset(i)
        lastDelta += AddrWidth - TastyBuffer.natSize(corrected.index)
        scratch.delta1(i) = lastDelta
        i += 1
      val saved =
        if (numOffsets == 0) 0
        else scratch.delta1(numOffsets - 1) - scratch.delta(numOffsets - 1)
      val tmp = scratch.delta
      scratch.delta = scratch.delta1
      scratch.delta1 = tmp
      saved
    }

    /** Compress pickle buffer, shifting bytes to close all skipped zeroes. */
    def compress(): Int = {
      var lastDelta = 0
      var start = 0
      var i = 0
      var wasted = 0
      def shift(end: Int) =
        System.arraycopy(bytes, start, bytes, start - lastDelta, end - start)
      while (i < numOffsets) {
        val next = offsets(i)
        shift(next)
        start = next + scratch.delta(i) - lastDelta
        val pastZeroes = skipZeroes(Addr(next)).index
        assert(pastZeroes >= start, s"something's wrong: eliminated non-zero")
        wasted += (pastZeroes - start)
        lastDelta = scratch.delta(i)
        i += 1
      }
      shift(length)
      length -= lastDelta
      wasted
    }

    def adjustTreeAddrs(): Unit =
      // Tree addresses are registered at `currentAddr` and so ascend with `i`;
      // search for each one starting from the result for the previous one.
      var idx = -1
      var i = 0
      while i < treeAddrs.size do
        val addr = Addr(treeAddrs.value(i))
        idx = offsetIndexBefore(addr, hint = idx)
        treeAddrs.setValue(i, (addr - deltaOf(idx, scratch)).index)
        i += 1

    val origLength = length
    computeDeltas()
    //println(s"offsets: ${offsets.take(numOffsets).deep}")
    //println(s"deltas: ${delta.take(numOffsets).deep}")
    var saved = 0
    while
      saved = adjustDeltas()
      pickling.println(s"adjusting deltas, saved = $saved")
      saved > 0 && length / saved < 100
    do ()
    adjustOffsets()
    adjustTreeAddrs()
    val wasted = compress()
    pickling.println(s"original length: $origLength, compressed to: $length, wasted: $wasted") // DEBUG, for now.
  end compactify
}
