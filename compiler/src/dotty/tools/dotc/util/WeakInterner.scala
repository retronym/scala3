package dotty.tools.dotc.util

import java.lang.ref.{ReferenceQueue, WeakReference}

/** A weak hash set for hash-consing, laid out so that G1 does not pay for it on every young collection.
 *
 *  It is open-addressed (linear probing) over parallel arrays of hashes and elements, split into chunks of
 *  `ChunkSize` slots (256 KB each). One probe sequence runs across the chunks, so a lookup costs what it would in one
 *  flat array, but no array reaches G1's humongous threshold (half a region, 2 MB at an 8 GB heap). A humongous array
 *  is allocated straight into the old generation, so every insert into it stores a young element into an old
 *  object, which dirties a card that the next young collection scans. A chunk is allocated young, and a growth
 *  rebuilds the table into fresh, young chunks.
 *
 *  Each element is held by a `Ref`, a `WeakReference` that also carries the element's hash. When an element is
 *  collected its slot becomes a tombstone, and the next rebuild drops it. There is no per-element chain link.
 *
 *  Lookups pass the equality test as an inline predicate, so callers can compare an element's parts without building
 *  a candidate first. Not thread-safe.
 */
abstract class WeakInterner[A <: AnyRef](initialCapacity: Int):
  import WeakInterner.*

  private val InitialCapacity = Integer.highestOneBit(math.max(initialCapacity, 16) * 2 - 1)

  private[util] var mask: Int = InitialCapacity - 1
  private[util] var hashes: Array[Array[Int]] = newHashes(InitialCapacity)
  private[util] var elems: Array[Array[AnyRef | Null]] = newElems(InitialCapacity)
  /** live elements plus tombstones */
  private var used = 0
  private var live = 0
  private val queue = new ReferenceQueue[AnyRef]
  // The slot where a failed `find` would insert (the first tombstone it passed, or the empty slot that ended it), so
  // that `add` can store there without probing again. It is valid only while `stamp` is unchanged and for the same
  // hash: the predicate or the element's constructor may intern into this table between the `find` and the `add`.
  private[util] var stamp = 0
  private[util] var free = -1
  private[util] var freeHash = 0
  private[util] var freeStamp = -1

  /** The element held at the non-null slot content `e`, or null if `e` is a tombstone or its element was collected. */
  private[util] inline def deref(e: AnyRef): A | Null =
    if e eq Tombstone then null else e.asInstanceOf[Ref].get().asInstanceOf[A | Null]

  /** The element with hash `h` that satisfies `p`, or null. */
  inline def find(h: Int, inline p: A => Boolean): A | Null =
    removeStaleEntries()
    var i = h & mask
    var result: A | Null = null
    var tomb = -1
    while
      val e = elems(i >>> ChunkBits)(i & ChunkMask)
      if e == null then
        free = if tomb >= 0 then tomb else i
        freeHash = h
        freeStamp = stamp
        false
      else
        if tomb < 0 && (e eq Tombstone) then tomb = i
        if hashes(i >>> ChunkBits)(i & ChunkMask) == h then
          val x = deref(e)
          if x != null && p(x) then result = x
        if result != null then false
        else
          i = (i + 1) & mask
          true
    do ()
    result

  /** Add `elem`, with hash `h`, which a `find` just failed to find. */
  def add[T <: A](elem: T, h: Int): T =
    val ref = new Ref(elem, h, queue)
    if (used + 1) * 2 > mask + 1 then
      // grow if live elements fill a quarter of the table, otherwise rebuild at the same size to drop tombstones
      rebuild(if live * 4 >= mask + 1 then (mask + 1) * 2 else mask + 1)
      insert(ref, h)
    else if freeStamp == stamp && freeHash == h then
      store(free, ref, h)
    else
      insert(ref, h)
    live += 1
    stamp += 1
    elem

  private def insert(ref: Ref, h: Int): Unit =
    var i = h & mask
    while
      val e = elems(i >>> ChunkBits)(i & ChunkMask)
      e != null && (e ne Tombstone)
    do i = (i + 1) & mask
    store(i, ref, h)

  /** Store `ref` at the empty or tombstone slot `i`. */
  private def store(i: Int, ref: Ref, h: Int): Unit =
    if elems(i >>> ChunkBits)(i & ChunkMask) == null then used += 1 // a tombstone is already counted
    elems(i >>> ChunkBits)(i & ChunkMask) = ref
    hashes(i >>> ChunkBits)(i & ChunkMask) = h

  /** Reinsert the live elements into fresh chunks of `capacity` slots. */
  private def rebuild(capacity: Int): Unit =
    val oldElems = elems
    mask = capacity - 1
    elems = newElems(capacity)
    hashes = newHashes(capacity)
    used = 0
    live = 0
    stamp += 1
    var c = 0
    while c < oldElems.length do
      val es = oldElems(c)
      var j = 0
      while j < es.length do
        val e = es(j)
        if e != null && deref(e) != null then
          val ref = e.asInstanceOf[Ref]
          insert(ref, ref.hash)
          live += 1
        j += 1
      c += 1

  /** Turn the slots of collected elements into tombstones. */
  protected def removeStaleEntries(): Unit =
    var stale = queue.poll()
    while stale != null do
      val ref = stale.asInstanceOf[Ref]
      var i = ref.hash & mask
      var done = false
      while !done do
        val e = elems(i >>> ChunkBits)(i & ChunkMask)
        if e == null then done = true // a rebuild already dropped it
        else if e eq ref then
          elems(i >>> ChunkBits)(i & ChunkMask) = Tombstone
          live -= 1
          done = true
        else i = (i + 1) & mask
      stale = queue.poll()

  def clear(): Unit =
    stamp += 1
    mask = InitialCapacity - 1
    elems = newElems(InitialCapacity)
    hashes = newHashes(InitialCapacity)
    used = 0
    live = 0
    while queue.poll() != null do ()

  /** The number of elements, counting ones collected since the last lookup. */
  def size: Int = live

object WeakInterner:
  inline val ChunkBits = 16
  inline val ChunkSize = 1 << ChunkBits
  inline val ChunkMask = ChunkSize - 1

  final class Ref(elem: AnyRef, val hash: Int, queue: ReferenceQueue[AnyRef]) extends WeakReference[AnyRef](elem, queue)

  private[util] val Tombstone: AnyRef = new Object

  private def chunks(capacity: Int): Int = (capacity + ChunkSize - 1) >>> ChunkBits
  private def chunkLength(capacity: Int): Int = math.min(capacity, ChunkSize)

  private def newHashes(capacity: Int): Array[Array[Int]] =
    Array.fill(chunks(capacity))(new Array[Int](chunkLength(capacity)))

  private def newElems(capacity: Int): Array[Array[AnyRef | Null]] =
    Array.fill(chunks(capacity))(new Array[AnyRef | Null](chunkLength(capacity)))
