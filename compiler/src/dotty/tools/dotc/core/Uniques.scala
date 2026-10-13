package dotty.tools.dotc
package core

import Types.*, Contexts.*, util.Stats.*, Hashable.*, Names.*
import config.Config
import Symbols.Symbol
import Decorators.*
import util.{WeakHashSet, Stats}
import WeakHashSet.Entry
import scala.annotation.tailrec
import scala.annotation.internal.sharable

class Uniques extends WeakHashSet[Type](Config.initialUniquesCapacity):
  override def hash(x: Type): Int = x.hash
  override def isEqual(x: Type, y: Type) = x.equals(y)

/** Defines operation `unique` for hash-consing types.
 *  Also defines specialized hash sets for hash consing uniques of a specific type.
 *  All sets offer a `enterIfNew` method which checks whether a type
 *  with the given parts exists already and creates a new one if not.
 */
object Uniques:

  // DO NOT MERGE: hook for probing hash-consing traffic from a test driver
  trait Probe:
    /** `freshParts`: the caller allocated parts (a probe type, an args list) that the hit makes garbage */
    def hit(table: String, tp: Type, freshParts: Boolean): Unit
    def miss(table: String, tp: Type): Unit
  @sharable var probe: Probe | Null = null

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
    else
      val r = ctx.uniques.put(tp).asInstanceOf[T]
      val p = probe
      if p != null then
        if r eq tp then p.miss("uniques", r) else p.hit("uniques", r, true)
      r

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
          case null                    =>
            Stats.record(statsItem("miss"))
            val r = addEntryAt(bucket, newType, h, oldHead)
            val p = probe
            if p != null then p.miss("named", r)
            r
          case _                       =>
            if entry.hash == h then
              val e = entry.get
              if e != null && (e.prefix eq prefix) && (e.designator eq designator) && (e.isTerm == isTerm) then
                Stats.record(statsItem("hit"))
                val p = probe
                if p != null then p.hit("named", e, false)
                e
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
      if h == NotCached then newType
      else
        // Inlined from WeakHashSet#put
        Stats.record(statsItem("put"))
        removeStaleEntries()
        val bucket = index(h)
        val oldHead = table(bucket)

        @tailrec
        def linkedListLoop(entry: Entry[AppliedType] | Null): AppliedType = entry match
          case null                    =>
            Stats.record(statsItem("miss"))
            val r = addEntryAt(bucket, newType, h, oldHead)
            val p = probe
            if p != null then p.miss("applied", r)
            r
          case _                       =>
            if entry.hash == h then
              val e = entry.get
              if e != null && (e.tycon eq tycon) && e.args.eqElements(args) then
                Stats.record(statsItem("hit"))
                val p = probe
                if p != null then p.hit("applied", e, e.args ne args)
                e
              else linkedListLoop(entry.tail)
            else linkedListLoop(entry.tail)

        linkedListLoop(oldHead)
      end if
  end AppliedUniques
end Uniques
