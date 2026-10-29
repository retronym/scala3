package dotty.tools.dotc
package core

import Types.*, Contexts.*, util.Stats.*, Hashable.*, Names.*
import config.Config
import Symbols.Symbol
import Decorators.*
import util.{ConcurrentWeakHashSet, WeakHashSet, Stats}
import WeakHashSet.Entry
import scala.annotation.tailrec

class Uniques(initialCapacity: Int = Config.initialUniquesCapacity) extends WeakHashSet[Type](initialCapacity):
  override def hash(x: Type): Int = x.hash
  override def isEqual(x: Type, y: Type) = x.equals(y)

/** `Uniques` for a `ContextBase` read by several threads (interactive mode,
 *  see `ContextState.sharing`): a hit is found without a lock
 *  (`ConcurrentWeakHashSet`), every other operation is under the set's
 *  monitor, and `put` looks again there before it inserts. Interning is on
 *  the hottest path of type creation, so the batch compiler keeps the
 *  unsynchronised set; this class is loaded only when an interactive base
 *  chooses it.
 */
class ConcurrentUniques(initialCapacity: Int = Config.initialUniquesCapacity) extends Uniques(initialCapacity), ConcurrentWeakHashSet[Type]:
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

  class NamedTypeUniques(initialCapacity: Int = Config.initialUniquesCapacity * 4) extends WeakHashSet[NamedType](initialCapacity) with Hashable:
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
  final class ConcurrentNamedTypeUniques(initialCapacity: Int = Config.initialUniquesCapacity * 4) extends NamedTypeUniques(initialCapacity), ConcurrentWeakHashSet[NamedType]:
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

  class AppliedUniques(initialCapacity: Int = Config.initialUniquesCapacity * 2) extends WeakHashSet[AppliedType](initialCapacity) with Hashable:
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
  final class ConcurrentAppliedUniques(initialCapacity: Int = Config.initialUniquesCapacity * 2) extends AppliedUniques(initialCapacity), ConcurrentWeakHashSet[AppliedType]:
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

  // ---- striped: several concurrent sets, one chosen by hash, each with its own monitor
  //
  // A miss in a concurrent set (every new type) takes the set's monitor, and with parallel readers that monitor was
  // the most contended in the compiler (`-Yparallel-typer`: 2.0 s blocked on each of the named and applied sets, over
  // three runs of the compiler's sources). A type is interned in exactly one stripe, fixed by its hash, so equal
  // types meet in the same one and interning stays unique.

  /** The number of stripes of a striped set: a power of two, `-Ddotc.uniques.stripes=<n>` (1 for one set). */
  val stripes: Int =
    val n = sys.props.get("dotc.uniques.stripes").flatMap(_.toIntOption).getOrElse(64).max(1)
    Integer.highestOneBit(n)
  private val stripeShift = 32 - Integer.numberOfTrailingZeros(stripes)

  /** The stripe of a hash: the high bits of a multiplicative mix, so that it does not follow the low bits that
   *  choose a bucket within the stripe. */
  inline def stripeOf(h: Int): Int = if stripes == 1 then 0 else (h * 0x9E3779B9) >>> stripeShift

  final class StripedUniques extends Uniques(8):
    private val sets = Array.fill(stripes)(ConcurrentUniques(Config.initialUniquesCapacity / stripes max 8))
    private def of(h: Int) = sets(stripeOf(h))
    override def put(elem: Type): Type = of(elem.hash).put(elem)
    override def lookup(elem: Type): Type | Null = of(elem.hash).lookup(elem)
    override def -=(elem: Type): Unit = of(elem.hash) -= elem
    override def clear(resetToInitial: Boolean): Unit = sets.foreach(_.clear(resetToInitial))
    override def size: Int = sets.iterator.map(_.size).sum
    override def iterator: Iterator[Type] = sets.iterator.flatMap(_.iterator)

  final class StripedNamedTypeUniques extends NamedTypeUniques(8):
    private val sets = Array.fill(stripes)(ConcurrentNamedTypeUniques(Config.initialUniquesCapacity * 4 / stripes max 8))
    private def of(h: Int) = sets(stripeOf(h))
    override def enterIfNew(prefix: Type, designator: Designator, isTerm: Boolean)(using Context): NamedType =
      of(doHash(null, designator, prefix)).enterIfNew(prefix, designator, isTerm)
    override def put(elem: NamedType): NamedType = of(elem.hash).put(elem)
    override def lookup(elem: NamedType): NamedType | Null = of(elem.hash).lookup(elem)
    override def -=(elem: NamedType): Unit = of(elem.hash) -= elem
    override def clear(resetToInitial: Boolean): Unit = sets.foreach(_.clear(resetToInitial))
    override def size: Int = sets.iterator.map(_.size).sum
    override def iterator: Iterator[NamedType] = sets.iterator.flatMap(_.iterator)

  final class StripedAppliedUniques extends AppliedUniques(8):
    private val sets = Array.fill(stripes)(ConcurrentAppliedUniques(Config.initialUniquesCapacity * 2 / stripes max 8))
    private def of(h: Int) = sets(stripeOf(h))
    override def enterIfNew(tycon: Type, args: List[Type]): AppliedType =
      of(doHash(null, tycon, args)).enterIfNew(tycon, args)
    override def put(elem: AppliedType): AppliedType = of(elem.hash).put(elem)
    override def lookup(elem: AppliedType): AppliedType | Null = of(elem.hash).lookup(elem)
    override def -=(elem: AppliedType): Unit = of(elem.hash) -= elem
    override def clear(resetToInitial: Boolean): Unit = sets.foreach(_.clear(resetToInitial))
    override def size: Int = sets.iterator.map(_.size).sum
    override def iterator: Iterator[AppliedType] = sets.iterator.flatMap(_.iterator)
end Uniques
