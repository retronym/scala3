package dotty.tools.dotc.util

import WeakHashSet.Entry

/** A `WeakHashSet` that several threads read without a lock (interactive
 *  mode, see `ContextState.sharing`): a hash-consing hit, the
 *  common case, is a lookup in the published table; only an insertion or
 *  a miss takes the set's monitor, where the subclass re-checks before it
 *  inserts, so that two threads interning equal elements get one object.
 *
 *  What a reader may see without the monitor:
 *  - A new entry is stored at the head of its bucket with release
 *    semantics (`addEntryAt`) and the head is loaded with acquire
 *    semantics (`findPublished`), so the entry and the element it refers
 *    to are seen complete.
 *  - A bucket is otherwise only shortened in place (stale entries
 *    unlinked, `-=`), always to an entry further down, so a reader that
 *    sees an old link still walks a finite chain.
 *  - The batch set's `resize` relinks the entries into the new table in
 *    place, which a reader on the old table could see as a cycle. Here
 *    `resize` relinks copies of the live entries and publishes the new
 *    table only when it is complete; a reader on the old table may miss
 *    an element and take the monitor.
 *
 *  A miss is therefore never trusted: the caller looks again under the
 *  monitor.
 */
trait ConcurrentWeakHashSet[A <: AnyRef] extends WeakHashSet[A]:

  // The table readers look in, a complete one (see `resize`).
  @volatile private var published: Array[Entry[A] | Null] = table

  /** The element in the published table with hash `h` that satisfies
   *  `matches`, or null if none is seen (which proves nothing).
   */
  protected inline def findPublished(h: Int)(inline matches: A => Boolean): A | Null =
    val tab = published
    var entry = Publish.slotAcquire(tab.asInstanceOf[Array[Object]], h & (tab.length - 1)).asInstanceOf[Entry[A] | Null]
    var result: A | Null = null
    while entry != null do
      if entry.hash == h then
        val e = entry.get
        if e != null && matches(e) then
          result = e
          entry = null
        else entry = entry.tail
      else entry = entry.tail
    result

  override protected def addEntryAt(bucket: Int, elem: A, elemHash: Int, oldHead: Entry[A] | Null): A =
    Stats.record(statsItem("addEntryAt"))
    Publish.setSlotRelease(table.asInstanceOf[Array[Object]], bucket, new Entry(elem, elemHash, oldHead, queue))
    count += 1
    if count > threshold then resize()
    elem

  override protected def resize(): Unit =
    // Copy the live entries into a private table of the same size, which
    // the batch `resize` then relinks into the new table.
    val old = table
    val copy = new Array[Entry[A] | Null](old.length)
    var bucket = 0
    while bucket < old.length do
      var entry = old(bucket)
      var last: Entry[A] | Null = null
      while entry != null do
        val e = entry.get
        if e == null then count -= 1 // its stale entry, once polled, is not found
        else
          val fresh = new Entry(e, entry.hash, null, queue)
          if last == null then copy(bucket) = fresh else last.tail = fresh
          last = fresh
        entry = entry.tail
      bucket += 1
    table = copy
    super.resize()
    published = table

  override def clear(resetToInitial: Boolean): Unit =
    super.clear(resetToInitial)
    published = table
end ConcurrentWeakHashSet
