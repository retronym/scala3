package dotty.tools.dotc
package core

import Types.*, Contexts.*, util.Stats.*, Hashable.*, Names.*
import config.Config
import Symbols.Symbol
import Decorators.*
import util.{WeakHashSet, Stats}
import WeakHashSet.Entry
import profile.DefCosts
import scala.annotation.tailrec
import scala.annotation.internal.sharable

class Uniques extends WeakHashSet[Type](Config.initialUniquesCapacity) with Uniques.CostedResize:
  override def hash(x: Type): Int = x.hash
  override def isEqual(x: Type, y: Type) = x.equals(y)
  protected def tableName = "Uniques"

/** Defines operation `unique` for hash-consing types.
 *  Also defines specialized hash sets for hash consing uniques of a specific type.
 *  All sets offer a `enterIfNew` method which checks whether a type
 *  with the given parts exists already and creates a new one if not.
 */
object Uniques:

  /** The capacity of each table (`Uniques`, `AppliedUniques`, `NamedTypeUniques`) at the end of the last run in
   *  this JVM, for `-Yuniques-presize`. Racy between concurrent compilers, which is harmless: it is a size hint. */
  @sharable private[dotc] val lastCapacity: Array[Int] = new Array[Int](3)

  /** A table whose growth is a cost frame of its own (`CostKind.Rehash`) with the cost per definition on, rather
   *  than part of whichever definition's lookup happened to cross the threshold. */
  trait CostedResize extends WeakHashSet[?]:
    /** The run's cost tracker, or null; set when the run starts */
    private[dotc] var costs: DefCosts | Null = null
    /** The frame's key and label: a constant, so a resize needs no new string */
    protected def tableName: String
    override protected def resize(): Unit =
      val cs = costs
      val frame = if cs == null then null else cs.enterRehash(tableName)
      try super.resize()
      finally if frame != null then cs.nn.exit(frame)

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

  final class NamedTypeUniques extends WeakHashSet[NamedType](Config.initialUniquesCapacity * 4) with Hashable with CostedResize:
    override def hash(x: NamedType): Int = x.hash
    protected def tableName = "NamedTypeUniques"

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

  final class AppliedUniques extends WeakHashSet[AppliedType](Config.initialUniquesCapacity * 2) with Hashable with CostedResize:
    override def hash(x: AppliedType): Int = x.hash
    protected def tableName = "AppliedUniques"

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
end Uniques
