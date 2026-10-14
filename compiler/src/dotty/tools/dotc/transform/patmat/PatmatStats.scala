package dotty.tools
package dotc
package transform
package patmat

import core.*, Contexts.*, Decorators.*, Symbols.*, Types.*
import ast.tpd.*

import scala.annotation.internal.sharable
import scala.collection.mutable
import scala.language.unsafeNulls

/** DO NOT MERGE: -Ddotty.patmatStats[=<file>], counts and times of the space engine's operations per `checkMatch`,
 *  reported at JVM exit (to `<file>` if given, else stderr). Single-threaded use only.
 */
@sharable object PatmatStats:
  final val enabled: Boolean = System.getProperty("dotty.patmatStats") != null

  // counters
  final val SimplifyCalls = 0
  final val SimplifyTyp = 1
  final val SimplifyProd = 2
  final val SimplifyOr = 3
  final val SimplifyOrElems = 4
  final val IsSubspaceCalls = 5
  final val IsSubspaceHit = 6
  final val IsSubspaceComputed = 7
  final val DecomposeType = 8
  final val TypDecomposeHit = 9
  final val TypDecomposeComputed = 10
  final val ExpandCaseClass = 11
  final val Signature = 12
  final val SignatureInfer = 13
  final val Minus = 14
  final val Intersect = 15
  final val IsSubType = 16
  final val Covers = 17
  final val Project = 18
  final val Flatten = 19
  final val DecomposeDistinct = 20
  final val DecomposeCacheHit = 21
  final val DecomposeProvisional = 22
  final val NCounters = 23
  val counterNames = Array("simplify", "simp.Typ", "simp.Prod", "simp.Or", "simp.OrElems", "isSubspace", "isSub.hit",
    "isSub.comp", "decomp(Type)", "Typ.decomp.hit", "Typ.decomp.comp", "expandCC", "signature", "sig.infer", "minus",
    "intersect", "isSubType", "covers", "project", "flatten", "decomp.distinct", "decomp.cacheHit", "decomp.provis")

  // inclusive timers (outermost call only)
  final val TDecompose = 0
  final val TSignature = 1
  final val TIsSubType = 2
  final val TSimplifyTyp = 3
  final val TSimplifyProd = 4
  final val TSimplifyOr = 5
  final val TProject = 6
  final val NTimers = 7
  val timerNames = Array("decomp(Type)", "signature", "isSubType", "simplify Typ", "simplify Prod", "simplify Or", "project")

  val counts = new Array[Long](NCounters)
  val times = new Array[Long](NTimers)
  private val depth = new Array[Int](NTimers)

  inline def count(i: Int): Unit = if enabled then counts(i) += 1
  inline def add(i: Int, n: Int): Unit = if enabled then counts(i) += n

  inline def timed[T](i: Int)(inline body: T): T =
    if enabled then
      val d = depth(i)
      depth(i) = d + 1
      val t0 = if d == 0 then System.nanoTime else 0L
      try body
      finally
        depth(i) = d
        if d == 0 then times(i) += System.nanoTime - t0
    else body

  final class Phase(val ns: Long, val counts: Array[Long], val times: Array[Long])

  final class MatchRecord(val pos: String, val selector: String, val selClass: String, val children: Int, val cases: Int,
      val exh: Phase | Null, val reach: Phase | Null):
    def ns: Long = (if exh == null then 0L else exh.ns) + (if reach == null then 0L else reach.ns)

  val records = mutable.ArrayBuffer[MatchRecord]()

  // types decomposed in the current checkMatch
  private val decomposed = mutable.HashSet[Type]()
  def decomposing(tp: Type): Unit = if enabled && decomposed.add(tp) then counts(DecomposeDistinct) += 1

  def measure(body: => Unit): Phase =
    val c0 = counts.clone
    val t0s = times.clone
    val t0 = System.nanoTime
    body
    val ns = System.nanoTime - t0
    Phase(ns, Array.tabulate(NCounters)(i => counts(i) - c0(i)), Array.tabulate(NTimers)(i => times(i) - t0s(i)))

  def record(m: Match, exh: Phase | Null, reach: Phase | Null)(using Context): Unit =
    val selTp = m.selector.tpe.widen.dealias
    val cls = selTp.classSymbol
    val children = if cls.exists then cls.children.size else 0
    val pos = s"${ctx.source.file.name}:${m.srcPos.line + 1}"
    decomposed.clear()
    records += MatchRecord(pos, selTp.show.take(80), cls.showFullName, children, m.cases.size, exh, reach)

  if enabled then Runtime.getRuntime.addShutdownHook(Thread(() => report()))

  private def ms(ns: Long) = f"${ns / 1e6}%9.1f"

  def report(): Unit =
    val out = new java.io.StringWriter
    val pw = new java.io.PrintWriter(out)
    import pw.println
    val n = records.size
    val exhNs = records.iterator.map(r => if r.exh == null then 0L else r.exh.ns).sum
    val reachNs = records.iterator.map(r => if r.reach == null then 0L else r.reach.ns).sum
    println(s"== patmat stats: $n checkMatch calls, exhaustivity ${ms(exhNs)} ms, reachability ${ms(reachNs)} ms")
    def phaseTotals(f: MatchRecord => Phase | Null): (Array[Long], Array[Long]) =
      val c = new Array[Long](NCounters); val t = new Array[Long](NTimers)
      for r <- records; p = f(r) if p != null do
        for i <- 0 until NCounters do c(i) += p.counts(i)
        for i <- 0 until NTimers do t(i) += p.times(i)
      (c, t)
    val (ec, et) = phaseTotals(_.exh)
    val (rc, rt) = phaseTotals(_.reach)
    println(f"${"counter"}%-16s ${"exhaust"}%12s ${"reach"}%12s")
    for i <- 0 until NCounters do println(f"${counterNames(i)}%-16s ${ec(i)}%12d ${rc(i)}%12d")
    println(f"${"timer (ms)"}%-16s ${"exhaust"}%12s ${"reach"}%12s")
    for i <- 0 until NTimers do println(f"${timerNames(i)}%-16s ${ms(et(i))}%12s ${ms(rt(i))}%12s")

    def hist(title: String, key: MatchRecord => Int, buckets: List[Int]): Unit =
      println(s"\n== by $title: bucket  matches  exh ms  reach ms  isSubspace(comp)  simplify(comp)  reach us/match")
      for (lo, hi) <- buckets.zip(buckets.tail :+ Int.MaxValue) do
        val rs = records.filter(r => key(r) >= lo && key(r) < hi)
        if rs.nonEmpty then
          val e = rs.iterator.map(r => if r.exh == null then 0L else r.exh.ns).sum
          val re = rs.iterator.map(r => if r.reach == null then 0L else r.reach.ns).sum
          def c(i: Int) = rs.iterator.map(r => (if r.exh == null then 0L else r.exh.counts(i)) + (if r.reach == null then 0L else r.reach.counts(i))).sum
          val simpComp = c(SimplifyTyp) + c(SimplifyProd) + c(SimplifyOr)
          println(f"  [$lo%3d,${if hi == Int.MaxValue then "inf" else hi.toString}%4s) ${rs.size}%6d ${ms(e)} ${ms(re)} ${c(IsSubspaceComputed)}%10d ${simpComp}%10d ${re / 1e3 / rs.size}%10.1f")
    hist("cases", _.cases, List(0, 2, 3, 4, 6, 9, 13, 20, 30, 50))
    hist("sealed children of selector class", _.children, List(0, 1, 2, 3, 5, 9, 17, 33, 65))

    println("\n== top 40 matches by time: ms(exh+reach) exh reach cases children isSub(comp/hit) simp(Typ/Prod/Or/OrElems) decomp(Type)(distinct) sig(infer) minus intersect | pos | selector")
    for r <- records.sortBy(-_.ns).take(40) do
      def c(i: Int) = (if r.exh == null then 0L else r.exh.counts(i)) + (if r.reach == null then 0L else r.reach.counts(i))
      println(f"${ms(r.ns)} ${ms(if r.exh == null then 0 else r.exh.ns)} ${ms(if r.reach == null then 0 else r.reach.ns)} ${r.cases}%4d ${r.children}%4d ${c(IsSubspaceComputed)}%6d/${c(IsSubspaceHit)}%-6d ${c(SimplifyTyp)}/${c(SimplifyProd)}/${c(SimplifyOr)}/${c(SimplifyOrElems)} ${c(DecomposeType)}(${c(DecomposeDistinct)}) ${c(Signature)}(${c(SignatureInfer)}) ${c(Minus)} ${c(Intersect)} | ${r.pos} | ${r.selector}")

    println("\n== by selector class (top 25 by time): ms matches cases(sum) children")
    for (cls, rs) <- records.groupBy(_.selClass).toList.sortBy(-_._2.map(_.ns).sum).take(25) do
      println(f"${ms(rs.map(_.ns).sum)} ${rs.size}%6d ${rs.map(_.cases).sum}%6d ${rs.head.children}%4d $cls")
    pw.flush()
    System.getProperty("dotty.patmatStats") match
      case "" | "true" => System.err.print(out.toString)
      case file => java.nio.file.Files.writeString(java.nio.file.Path.of(file), out.toString)
end PatmatStats

/** DO NOT MERGE: experiments in the space engine, each behind its own flag, to be A/B'd together. */
object PatmatFlags:
  private def flag(name: String): Boolean = java.lang.Boolean.getBoolean(s"dotty.patmat$name")
  /** Spaces cache their (structural) hash code: `isSubspace` cache keys hash whole space trees. */
  final val hashMemo: Boolean = flag("HashMemo")
  /** The result of `simplify` is its own simplification, so simplifying it again is a memo hit. */
  final val simplifyFixpoint: Boolean = flag("SimplifyFixpoint")
  /** Reachability keeps the union of the previous cases as it goes, instead of re-simplifying `Or(prevs)` per case. */
  final val prevUnion: Boolean = flag("PrevUnion")
  /** `signature` results are cached per run (when nothing involved is provisional). */
  final val signatureCache: Boolean = flag("SignatureCache")
  /** `isSubType` results are cached per `checkMatch` (when neither type is provisional). */
  final val subTypeCache: Boolean = flag("SubTypeCache")
  /** `minus(a, Or(ss))` simplifies after each step of its fold, so emptied parts drop out early. */
  final val minusFoldSimplify: Boolean = flag("MinusFoldSimplify")
  /** `isSameUnapply` is false at once for extractors with different symbols (not Java records). */
  final val sameUnapplySym: Boolean = flag("SameUnapplySym")
  /** A constant is not a subspace of a different constant: skipped without `isSubspace` (and its cache). */
  final val constantSkip: Boolean = flag("ConstantSkip")
  /** `isSubType` of two class type refs is false at once when the first class does not derive from the second. */
  final val classFastFail: Boolean = flag("ClassFastFail")
