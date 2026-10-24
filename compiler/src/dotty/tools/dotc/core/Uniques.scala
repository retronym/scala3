package dotty.tools.dotc
package core

import Types.*, Contexts.*, util.Stats.*, Hashable.*, Names.*
import config.Config
import Symbols.Symbol
import Decorators.*
import util.{ConcurrentWeakHashSet, WeakHashSet, Stats}
import WeakHashSet.Entry
import scala.annotation.tailrec

class Uniques extends WeakHashSet[Type](Config.initialUniquesCapacity):
  override def hash(x: Type): Int = x.hash
  override def isEqual(x: Type, y: Type) = x.equals(y)

/** `Uniques` for a `ContextBase` read by several threads (interactive mode,
 *  see `ContextState.concurrentReaders`): a hit is found without a lock
 *  (`ConcurrentWeakHashSet`), every other operation is under the set's
 *  monitor, and `put` looks again there before it inserts. Interning is on
 *  the hottest path of type creation, so the batch compiler keeps the
 *  unsynchronised set; this class is loaded only when an interactive base
 *  chooses it.
 */
class ConcurrentUniques extends Uniques, ConcurrentWeakHashSet[Type]:
  override def put(elem: Type): Type =
    val found = findPublished(elem.hash)(elem.equals(_))
    if found != null then found else synchronized(super.put(elem))
  override def lookup(elem: Type): Type | Null =
    val found = findPublished(elem.hash)(elem.equals(_))
    if found != null then found else synchronized(super.lookup(elem))
  override def -=(elem: Type): Unit = synchronized(super.-=(elem))
  override def clear(resetToInitial: Boolean): Unit = synchronized(super.clear(resetToInitial))
  override def size: Int = synchronized(super.size)

/** Defines operation `unique` for hash-consing types.
 *  Also defines specialized hash sets for hash consing uniques of a specific type.
 *  All sets offer a `enterIfNew` method which checks whether a type
 *  with the given parts exists already and creates a new one if not.
 */
object Uniques:

  private inline def recordCaching(tp: Type): Unit = recordCaching(tp.hash, tp.getClass)
  private inline def recordCaching(h: Int, clazz: Class[?]): Unit =
    if monitored then
      if h == NotCached then
        record("uncached-types")
        record(s"uncached: $clazz")
      else
        record("cached-types")
        record(s"cached: $clazz")

  def unique[T <: Type](tp: T)(using Context): T =
    recordCaching(tp)
    if tp.hash == NotCached then tp
    else ctx.uniques.put(tp).asInstanceOf[T]

  class NamedTypeUniques extends WeakHashSet[NamedType](Config.initialUniquesCapacity * 4) with Hashable:
    override def hash(x: NamedType): Int = x.hash

    def enterIfNew(prefix: Type, designator: Designator, isTerm: Boolean)(using Context): NamedType =
      val h = doHash(null, designator, prefix)
      if monitored then recordCaching(h, classOf[NamedType])
      def newType =
        try
          if isTerm then new CachedTermRef(prefix, designator, h)
          else new CachedTypeRef(prefix, designator, h)
        catch case ex: InvalidPrefix => badPrefix(prefix, designator)
      if h == NotCached then newType
      else
        // Inlined from WeakHashSet#put
        Stats.record(statsItem("put"))
        removeStaleEntries()
        val bucket = index(h)
        val oldHead = table(bucket)

        @tailrec
        def linkedListLoop(entry: Entry[NamedType] | Null): NamedType = entry match
          case null                    => addEntryAt(bucket, newType, h, oldHead)
          case _                       =>
            if entry.hash == h then
              val e = entry.get
              if e != null && (e.prefix eq prefix) && (e.designator eq designator) && (e.isTerm == isTerm) then e
              else linkedListLoop(entry.tail)
            else linkedListLoop(entry.tail)

        linkedListLoop(oldHead)
      end if
    end enterIfNew

    private def badPrefix(prefix: Type, desig: Designator)(using Context): Nothing =
      def name = desig match
        case desig: Name => desig
        case desig: Symbol => desig.name
      throw TypeError(em"invalid prefix $prefix when trying to form $prefix . $name")

  end NamedTypeUniques

  /** `NamedTypeUniques` read without a lock (see `ConcurrentUniques`). */
  final class ConcurrentNamedTypeUniques extends NamedTypeUniques, ConcurrentWeakHashSet[NamedType]:
    override def enterIfNew(prefix: Type, designator: Designator, isTerm: Boolean)(using Context): NamedType =
      val h = doHash(null, designator, prefix)
      val found =
        if h == NotCached then null
        else findPublished(h)(e => (e.prefix eq prefix) && (e.designator eq designator) && (e.isTerm == isTerm))
      if found != null then found
      else if h == NotCached then super.enterIfNew(prefix, designator, isTerm)
      else synchronized(super.enterIfNew(prefix, designator, isTerm))
    override def put(elem: NamedType): NamedType =
      val found = findPublished(elem.hash)(isEqual(elem, _))
      if found != null then found else synchronized(super.put(elem))
    override def lookup(elem: NamedType): NamedType | Null =
      val found = findPublished(elem.hash)(isEqual(elem, _))
      if found != null then found else synchronized(super.lookup(elem))
    override def -=(elem: NamedType): Unit = synchronized(super.-=(elem))
    override def clear(resetToInitial: Boolean): Unit = synchronized(super.clear(resetToInitial))
    override def size: Int = synchronized(super.size)

  class AppliedUniques extends WeakHashSet[AppliedType](Config.initialUniquesCapacity * 2) with Hashable:
    override def hash(x: AppliedType): Int = x.hash

    def enterIfNew(tycon: Type, args: List[Type]): AppliedType =
      val h = doHash(null, tycon, args)
      def newType = new CachedAppliedType(tycon, args, h)
      if monitored then recordCaching(h, classOf[CachedAppliedType])
      if h == NotCached then newType
      else
        // Inlined from WeakHashSet#put
        Stats.record(statsItem("put"))
        removeStaleEntries()
        val bucket = index(h)
        val oldHead = table(bucket)

        @tailrec
        def linkedListLoop(entry: Entry[AppliedType] | Null): AppliedType = entry match
          case null                    => addEntryAt(bucket, newType, h, oldHead)
          case _                       =>
            if entry.hash == h then
              val e = entry.get
              if e != null && (e.tycon eq tycon) && e.args.eqElements(args) then e
              else linkedListLoop(entry.tail)
            else linkedListLoop(entry.tail)

        linkedListLoop(oldHead)
      end if
  end AppliedUniques

  /** `AppliedUniques` read without a lock (see `ConcurrentUniques`). */
  final class ConcurrentAppliedUniques extends AppliedUniques, ConcurrentWeakHashSet[AppliedType]:
    override def enterIfNew(tycon: Type, args: List[Type]): AppliedType =
      val h = doHash(null, tycon, args)
      val found =
        if h == NotCached then null
        else findPublished(h)(e => (e.tycon eq tycon) && e.args.eqElements(args))
      if found != null then found
      else if h == NotCached then super.enterIfNew(tycon, args)
      else synchronized(super.enterIfNew(tycon, args))
    override def put(elem: AppliedType): AppliedType =
      val found = findPublished(elem.hash)(isEqual(elem, _))
      if found != null then found else synchronized(super.put(elem))
    override def lookup(elem: AppliedType): AppliedType | Null =
      val found = findPublished(elem.hash)(isEqual(elem, _))
      if found != null then found else synchronized(super.lookup(elem))
    override def -=(elem: AppliedType): Unit = synchronized(super.-=(elem))
    override def clear(resetToInitial: Boolean): Unit = synchronized(super.clear(resetToInitial))
    override def size: Int = synchronized(super.size)
end Uniques
