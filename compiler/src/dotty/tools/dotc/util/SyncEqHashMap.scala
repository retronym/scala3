package dotty.tools.dotc.util

/** An `EqHashMap` whose operations run under its monitor, for a cache read
 *  and filled by several threads (interactive mode, see
 *  `ContextState.concurrentReaders`). `getOrElseUpdate` evaluates `value`
 *  outside the monitor, since computing a cached value may itself take
 *  locks or recurse into the cache; two threads computing the same value
 *  both get their own, and the one that stores last wins (the values of
 *  these caches are derived and idempotent). The iterators are over a
 *  snapshot.
 */
class SyncEqHashMap[Key, Value](initialCapacity: Int = 8, capacityMultiple: Int = 2)
extends EqHashMap[Key, Value](initialCapacity, capacityMultiple):
  override def lookup(key: Key): Value | Null = synchronized(super.lookup(key))
  override def update(key: Key, value: Value): Unit = synchronized(super.update(key, value))
  override def remove(key: Key): Value | Null = synchronized(super.remove(key))
  override def getOrElseUpdate(key: Key, value: => Value): Value =
    lookup(key) match
      case null =>
        val v = value
        synchronized:
          super.lookup(key) match
            case null => super.update(key, v); v
            case v1 => v1
      case v => v
  override def clear(resetToInitial: Boolean): Unit = synchronized(super.clear(resetToInitial))
  override def size: Int = synchronized(super.size)
  override def iterator: Iterator[(Key, Value)] = synchronized(super.iterator.toList).iterator
  override def keysIterator: Iterator[Key] = synchronized(super.keysIterator.toList).iterator
  override def valuesIterator: Iterator[Value] = synchronized(super.valuesIterator.toList).iterator
