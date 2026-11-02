package dotty.tools.dotc
package core

import Types.*, Contexts.*, util.Stats.*, Hashable.*, Names.*
import config.Config
import Symbols.Symbol
import Decorators.*
import util.{WeakHashSet, Stats}
import WeakHashSet.Entry
import scala.annotation.tailrec

class Uniques extends WeakHashSet[Type](Config.initialUniquesCapacity):
  override def hash(x: Type): Int = x.hash
  override def isEqual(x: Type, y: Type) = x.equals(y)

/** Defines operation `unique` for hash-consing types.
 *  Also defines specialized hash sets for hash consing uniques of a specific type.
 *  All sets offer a `enterIfNew` method which checks whether a type
 *  with the given parts exists already and creates a new one if not.
 */
object Uniques:

  /** Whether types over the params of a method or poly type are left out of the interners, see `ParamRef.computeHash`.
   *  On by default; `-Ddotty.uniques.uncacheMethodParams=false` interns them as before.
   */
  @scala.annotation.static final val uncacheMethodParams: Boolean =
    !"false".equals(System.getProperty("dotty.uniques.uncacheMethodParams"))

  /** The number of slots of `AppliedUniques`' cache of uncached applied types, a power of two, or 0 for no cache:
   *  `-Ddotty.uniques.uncachedAppliedCache=<slots>`, 4096 by default.
   */
  @scala.annotation.static final val uncachedAppliedCacheSize: Int =
    val n = Integer.getInteger("dotty.uniques.uncachedAppliedCache", 4096).nn.intValue
    if n <= 0 then 0 else Integer.highestOneBit(n)

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

  final class NamedTypeUniques extends WeakHashSet[NamedType](Config.initialUniquesCapacity * 4) with Hashable:
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

  final class AppliedUniques extends WeakHashSet[AppliedType](Config.initialUniquesCapacity * 2) with Hashable:
    override def hash(x: AppliedType): Int = x.hash

    def enterIfNew(tycon: Type, args: List[Type]): AppliedType =
      val h = doHash(null, tycon, args)
      def newType = new CachedAppliedType(tycon, args, h)
      if monitored then recordCaching(h, classOf[CachedAppliedType])
      if h == NotCached then enterUncached(tycon, args)
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

    // A direct-mapped cache of uncached applied types, keyed by their parts: one built recently is shared again, an
    // older one is replaced by a new instance. Their hash stays NotCached, so caches gated on it still skip them.
    // Held strongly, at most `uncachedAppliedCacheSize` types until they are replaced or the interners are cleared.
    //
    // It needs no lock when threads share it: slots are read and written plainly, and a race only loses a write or
    // builds a second instance, which is harmless since nothing relies on these types being unique. An entry read
    // through a race is complete, as `tycon` and `args` are final fields (and so is everything reachable from them);
    // its `myHash` may read as unknown, which is recomputed as NotCached.
    private val uncachedCache: Array[AppliedType | Null] | Null =
      if uncachedAppliedCacheSize > 0 then new Array[AppliedType | Null](uncachedAppliedCacheSize) else null

    // the part's hash when it is hash-consed (already computed), otherwise its identity hash
    private def partKey(tp: Type): Int =
      val h = tp.hash
      if h != NotCached then h else System.identityHashCode(tp)

    override def clear(resetToInitial: Boolean): Unit =
      super.clear(resetToInitial)
      val cache = uncachedCache
      if cache != null then java.util.Arrays.fill(cache.asInstanceOf[Array[AnyRef | Null]], null)

    private def enterUncached(tycon: Type, args: List[Type]): AppliedType =
      val cache = uncachedCache
      if cache == null then new CachedAppliedType(tycon, args, NotCached)
      else
        var k = partKey(tycon)
        var xs = args
        while !xs.isEmpty do
          k = k * 31 + partKey(xs.head)
          xs = xs.tail
        val i = (k ^ (k >>> 16)) & (cache.length - 1)
        val e = cache(i)
        if e != null && (e.tycon eq tycon) && e.args.eqElements(args) then e
        else
          val tp = new CachedAppliedType(tycon, args, NotCached)
          cache(i) = tp
          tp
  end AppliedUniques
end Uniques
