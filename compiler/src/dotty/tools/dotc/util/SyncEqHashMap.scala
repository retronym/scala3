package dotty.tools.dotc.util

import GenericHashMap.DenseLimit
import dotty.tools.sharing.Publish

/** An `EqHashMap` for a cache read and filled by several threads
 *  (interactive mode, see `ContextState.sharing`): a hit is found
 *  without a lock, every other operation runs under the map's monitor.
 *  `getOrElseUpdate` evaluates `value` outside the monitor, since computing
 *  a cached value may itself take locks or recurse into the cache; two
 *  threads computing the same value both get their own, and the one that
 *  stores first wins (the values of these caches are derived and
 *  idempotent). The iterators are over a snapshot.
 *
 *  The lock-free lookup relies on what the writers keep true of a table
 *  once it is published: a key slot goes from null to a key once and never
 *  changes after; a value slot is stored before its key, both with release
 *  semantics, and is only replaced by another value. A reader that loads
 *  the key with acquire semantics and finds its key then sees a complete
 *  value for it. `remove` and `clear`, which would move or clear slots,
 *  work on a fresh copy of the table; growing fills a new table before it
 *  is published. A reader that sees no key, or a null value, has proved
 *  nothing and looks again under the monitor.
 */
class SyncEqHashMap[Key, Value](initialCapacity: Int = 8, capacityMultiple: Int = 2)
extends EqHashMap[Key, Value](initialCapacity, capacityMultiple):

  // The table readers look in, a complete one (see `growTable`).
  @volatile private var published: Array[AnyRef | Null] = table

  override def lookup(key: Key): Value | Null =
    val found = lookupPublished(key)
    if found != null then found else synchronized(super.lookup(key))

  private def lookupPublished(key: Key): Value | Null =
    val tab = published.asInstanceOf[Array[Object]]
    val mask = tab.length - 2
    // as `isDense`, from the table's length (`allocate`)
    val dense = tab.length <= DenseLimit * 2
    var idx = if dense then 0 else hash(key) & mask
    var k = Publish.slotAcquire(tab, idx)
    while k != null do
      if k eq key.asInstanceOf[AnyRef] then
        return Publish.slotAcquire(tab, idx + 1).asInstanceOf[Value | Null]
      idx = (idx + 2) & mask
      if dense && idx == 0 then return null
      k = Publish.slotAcquire(tab, idx)
    null

  // Under the monitor: store `value` for `key`, the value before the key.
  private def store(key: Key, value: Value): Unit =
    val tab = table.asInstanceOf[Array[Object]]
    val mask = tab.length - 2
    var idx = if isDense then 0 else hash(key) & mask
    var k = tab(idx)
    while k != null do
      if k eq key.asInstanceOf[AnyRef] then
        Publish.setSlotRelease(tab, idx + 1, value.asInstanceOf[AnyRef])
        return
      idx = (idx + 2) & mask
      k = tab(idx)
    Publish.setSlotRelease(tab, idx + 1, value.asInstanceOf[AnyRef])
    Publish.setSlotRelease(tab, idx, key.asInstanceOf[AnyRef])
    used += 1
    if used > limit then growTable()

  override def update(key: Key, value: Value): Unit = synchronized(store(key, value))

  override def getOrElseUpdate(key: Key, value: => Value): Value =
    lookup(key) match
      case null =>
        val v = value
        synchronized:
          super.lookup(key) match
            case null => store(key, v); v
            case v1 => v1
      case v => v

  override def remove(key: Key): Value | Null = synchronized:
    if super.lookup(key) == null then null
    else
      table = table.clone()
      val result = super.remove(key)
      published = table
      result

  override protected def growTable(): Unit =
    super.growTable()
    published = table

  override def clear(resetToInitial: Boolean): Unit = synchronized:
    super.clear(resetToInitial = true)
    published = table

  override def size: Int = synchronized(super.size)
  override def iterator: Iterator[(Key, Value)] = synchronized(super.iterator.toList).iterator
  override def keysIterator: Iterator[Key] = synchronized(super.keysIterator.toList).iterator
  override def valuesIterator: Iterator[Value] = synchronized(super.valuesIterator.toList).iterator
