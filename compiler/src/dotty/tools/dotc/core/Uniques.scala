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

class Uniques extends WeakHashSet[Type](Config.initialUniquesCapacity * Uniques.capacityFactor):
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
    /** Every call of `unique`, with the type offered and the one returned */
    def unique(candidate: Type, result: Type): Unit = ()
  @sharable var probe: Probe | Null = null

  // DO NOT MERGE: experiment switch, see ParamRef.computeHash
  @sharable var uncacheMethodParamRefs: Boolean = sys.props.contains("dotty.uncacheMethodParamRefs")

  // DO NOT MERGE: experiment switch, multiplies the initial capacity of the unique tables
  @sharable val capacityFactor: Int = sys.props.get("dotty.uniquesCapacityFactor").fold(1)(_.toInt)

  // DO NOT MERGE: experiment switch, see `localBinderOf`
  @sharable var localMethodParamUniques: Boolean = sys.props.contains("dotty.localMethodParamUniques")

  /** The method or poly type whose params `tycon` or one of `args` mentions, either directly or as the
   *  `localBinder` of an applied type, or null. Shallow, so it only routes some of the types over method params;
   *  the rest stay in the global table. Deterministic in the components, which is all that identity needs.
   */
  def localBinderOf(tycon: Type, args: List[Type]): MethodOrPoly | Null =
    def of(tp: Type): MethodOrPoly | Null = tp match
      case tp: ParamRef => tp.binder match
        case b: MethodOrPoly => b
        case _ => null
      case tp: CachedAppliedType => tp.localBinder
      case _ => null
    var b = of(tycon)
    var xs = args
    while b == null && !xs.isEmpty do
      b = of(xs.head)
      xs = xs.tail
    b

  /** An open-addressing hash-consing table of applied types, owned by one method or poly type. Holds its types
   *  strongly: they mention the owner's params, so they cannot outlive it anyway, but they live as long as it does.
   */
  final class LocalAppliedUniques:
    private var table = new Array[CachedAppliedType | Null](8)
    private var count = 0

    def enterIfNew(tycon: Type, args: List[Type], h: Int): AppliedType =
      val mask = table.length - 1
      var i = h & mask
      var e = table(i)
      while e != null do
        if e.hash == h && (e.tycon eq tycon) && e.args.eqElements(args) then
          val p = probe
          if p != null then p.hit("applied-local", e, e.args ne args)
          return e
        i = (i + 1) & mask
        e = table(i)
      val tp = new CachedAppliedType(tycon, args, h)
      table(i) = tp
      count += 1
      if count * 2 > table.length then grow()
      val p = probe
      if p != null then p.miss("applied-local", tp)
      tp

    private def grow(): Unit =
      val old = table
      table = new Array[CachedAppliedType | Null](old.length * 2)
      val mask = table.length - 1
      for e <- old do
        if e != null then
          var i = e.hash & mask
          while table(i) != null do i = (i + 1) & mask
          table(i) = e
  end LocalAppliedUniques

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
        p.unique(tp, r)
      r

  final class NamedTypeUniques extends WeakHashSet[NamedType](Config.initialUniquesCapacity * 4 * capacityFactor) with Hashable:
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

  final class AppliedUniques extends WeakHashSet[AppliedType](Config.initialUniquesCapacity * 2 * capacityFactor) with Hashable:
    override def hash(x: AppliedType): Int = x.hash

    def enterIfNew(tycon: Type, args: List[Type]): AppliedType =
      val h = doHash(null, tycon, args)
      def newType = new CachedAppliedType(tycon, args, h)
      if monitored then recordCaching(h, classOf[CachedAppliedType])
      if h == NotCached then newType
      else
        val local = if localMethodParamUniques then localBinderOf(tycon, args) else null
        if local != null then local.localApplied.enterIfNew(tycon, args, h)
        else enterGlobal(tycon, args, h)
      end if

    private def enterGlobal(tycon: Type, args: List[Type], h: Int): AppliedType =
        def newType = new CachedAppliedType(tycon, args, h)
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
    end enterGlobal
  end AppliedUniques
end Uniques
