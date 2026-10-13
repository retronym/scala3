package dotty.tools.dotc.core

import Contexts.*, Types.*
import dotty.tools.dotc.{Driver, Compiler}
import java.lang.ref.WeakReference
import java.nio.file.*
import scala.jdk.CollectionConverters.*
import scala.collection.mutable

/** Compiles a corpus with `Uniques.probe` installed. Every hit/miss is counted; one in `SampleEvery` gets a stack walk
 *  for its call site and phase, and sampled misses are tracked weakly to see whether the new unique type outlives the run.
 */
object ProbeMain:
  val SampleEvery = 16

  final case class Key(table: String, phase: String, site: String, shape: String)

  final class Counts:
    var hits, hitsFresh, misses = 0L

  object P extends Uniques.Probe:
    val totals = mutable.HashMap[String, Counts]()
    val sampled = mutable.HashMap[Key, Counts]()
    val tracked = mutable.ArrayBuffer[(Key, WeakReference[Type])]()
    var n = 0L
    def totalsFor(t: String) = totals.getOrElseUpdate(t, Counts())

    def hit(table: String, tp: Type, freshParts: Boolean): Unit =
      val c = totalsFor(table); c.hits += 1; if freshParts then c.hitsFresh += 1
      if Lambdas.enabled then Lambdas.hit(table, tp)
      n += 1
      if n % SampleEvery == 0 then
        val s = sampled.getOrElseUpdate(key(table, tp), Counts()); s.hits += 1; if freshParts then s.hitsFresh += 1

    def miss(table: String, tp: Type): Unit =
      totalsFor(table).misses += 1
      if Lambdas.enabled then Lambdas.miss(table, tp)
      n += 1
      if n % SampleEvery == 0 then
        val k = key(table, tp)
        sampled.getOrElseUpdate(k, Counts()).misses += 1
        tracked += ((k, WeakReference(tp)))

    override def unique(candidate: Type, result: Type): Unit =
      if Lambdas.enabled then Lambdas.unique(candidate, result)

    def key(table: String, tp: Type): Key =
      val (phase, site) = where()
      Key(table, phase, site, shape(tp))

  /** With `-Dprobe.lambdas`: attributes each miss to the lambdas whose params it mentions, then, when the lambda is
   *  offered to `unique`, to whether the lambda was a hit (so everything built while constructing it is garbage) or a
   *  miss. Holds every lambda strongly, so survival figures are meaningless in this mode.
   */
  object Lambdas:
    val enabled = sys.props.contains("probe.lambdas")
    private val tables = Vector("applied", "named", "uniques")
    private val pending = java.util.IdentityHashMap[LambdaType, Array[Long]]()
    private val finalized = java.util.IdentityHashMap[LambdaType, java.lang.Boolean]()
    // (lambda class, lambda hit?) -> (#lambdas, inner misses per table)
    val stats = mutable.HashMap[(String, Boolean), Array[Long]]()
    val afterConstruction = new Array[Long](3)
    val noBinder = new Array[Long](3)

    // identity set: a binder under construction must not be hashed
    def binders(tp: Type): Iterable[LambdaType] =
      val found = java.util.Collections.newSetFromMap(java.util.IdentityHashMap[LambdaType, java.lang.Boolean]())
      def visit(t: Type, depth: Int): Unit = if depth < 6 then t match
        case t: ParamRef => found.add(t.binder)
        case t: AppliedType => visit(t.tycon, depth + 1); t.args.foreach(visit(_, depth + 1))
        case t: TypeBounds => visit(t.lo, depth + 1); visit(t.hi, depth + 1)
        case t: AndOrType => visit(t.tp1, depth + 1); visit(t.tp2, depth + 1)
        case t: RefinedType => visit(t.parent, depth + 1); visit(t.refinedInfo, depth + 1)
        case t: AnnotatedType => visit(t.parent, depth + 1)
        case t: NamedType => visit(t.prefix, depth + 1)
        case t: ExprType => visit(t.resType, depth + 1)
        case t: LambdaType =>
          if t.paramInfos != null then t.paramInfos.foreach(visit(_, depth + 1))
          if t.resType != null then visit(t.resType, depth + 1)
        case _ =>
      visit(tp, 0)
      found.asScala.toList

    // (table, kind of binders mentioned) -> (hits, misses)
    val byBinderKind = mutable.HashMap[(String, String), Array[Long]]()
    def binderKind(bs: Iterable[LambdaType]): String =
      if bs.isEmpty then "no params"
      else if bs.exists(_.isInstanceOf[MethodOrPoly]) then "params of method/poly (never uniqued)"
      else if bs.exists(b => !finalized.containsKey(b)) then "params of HK lambda under construction"
      else "params of finished HK lambda"
    def hit(table: String, tp: Type): Unit =
      byBinderKind.getOrElseUpdate((table, binderKind(binders(tp))), new Array[Long](2))(0) += 1

    def miss(table: String, tp: Type): Unit =
      val i = tables.indexOf(table)
      val bs = binders(tp)
      byBinderKind.getOrElseUpdate((table, binderKind(bs)), new Array[Long](2))(1) += 1
      if bs.isEmpty then noBinder(i) += 1
      // attribute to the innermost lambda still under construction, if any
      val open = bs.filterNot(finalized.containsKey)
      if open.isEmpty then { if bs.nonEmpty then afterConstruction(i) += 1 }
      else for b <- open do pending.computeIfAbsent(b, _ => new Array[Long](3))(i) += 1

    def unique(candidate: Type, result: Type): Unit = candidate match
      case l: LambdaType =>
        val inner = Option(pending.remove(l)).getOrElse(new Array[Long](3))
        val hit = result ne candidate
        val s = stats.getOrElseUpdate((l.getClass.getSimpleName, hit), new Array[Long](4))
        s(0) += 1; for i <- 0 until 3 do s(i + 1) += inner(i)
        finalized.put(l, hit)
      case _ =>

    def report(): Unit =
      println(s"\n== lambda construction (inner misses attributed to every open binder they mention, so nested lambdas double count)")
      println(f"${"lambda"}%-22s ${"hit?"}%5s ${"#lambdas"}%10s ${"applied"}%11s ${"named"}%9s ${"uniques"}%9s")
      for ((cls, hit), s) <- stats.toList.sortBy(-_._2(1)) do
        println(f"$cls%-22s ${hit.toString}%5s ${s(0)}%,10d ${s(1)}%,11d ${s(2)}%,9d ${s(3)}%,9d")
      println(f"never offered to unique: ${pending.size}%,d lambdas, inner misses ${pending.values.asScala.map(_(0)).sum}%,d applied")
      println(f"misses over params of finalized lambdas: applied=${afterConstruction(0)}%,d named=${afterConstruction(1)}%,d uniques=${afterConstruction(2)}%,d")
      println("\n== lookups by the binders of the params they mention: hits | misses | hit%")
      for ((t, k), Array(h, m)) <- byBinderKind.toList.sortBy(x => (x._1._1, -x._2(1))) do
        println(f"$t%-8s $h%,11d $m%,11d ${100.0 * h / (h + m)}%5.1f%%  $k")
      println(f"misses mentioning no params: applied=${noBinder(0)}%,d named=${noBinder(1)}%,d uniques=${noBinder(2)}%,d")

  // frames that are plumbing between the interesting caller and the unique table
  private val plumbing =
    """^(scala\.|dotty\.tools\.dotc\.core\.(Uniques|Hashable|TypeApplications|Substituters|Types\$(AppliedType|TypeMap|DeepTypeMap|ApproximatingTypeMap|AsSeenFromMap|.*Map\b|NamedType|TypeRef|TermRef|Type\b|CachedProxyType|TypeProxy|ThisType|SuperType|ExprType|TypeBounds|AndType|OrType|RefinedType|AnnotatedType|LambdaType|HKTypeLambda|MethodType|PolyType|TypeLambdaCompanion|.*Companion)|Decorators|NamedType)|dotty\.tools\.dotc\.util\.|java\.)""".r
  private val areas = List(
    "TypeComparer" -> "subtype", "Implicits" -> "implicits", "Inferencing" -> "inference", "ProtoTypes" -> "prototypes",
    "Applications" -> "applications", "TypeOps" -> "typeops", "Denotations" -> "denotations", "SymDenotations" -> "denotations",
    "TypeAssigner" -> "typeassigner", "Checking" -> "checking", "Namer" -> "namer", "Unpickler" -> "unpickle", "TreeUnpickler" -> "unpickle",
    "ClassfileParser" -> "classfile", "TypeErasure" -> "erasure-fn")
  private val phases = List(
    "typer.TyperPhase" -> "typer", "transform.PostTyper" -> "posttyper", "transform.Pickler" -> "pickler", "sbt.ExtractAPI" -> "extractapi",
    "transform.Erasure" -> "erasure", "backend." -> "backend", "transform.MegaPhase" -> "megaphase", "inlines." -> "inlining")
  private val walker = StackWalker.getInstance()

  def where(): (String, String) =
    val frames = walker.walk(_.iterator.asScala.map(f => f.getClassName + "." + f.getMethodName).toVector)
    val site = frames.iterator.drop(3).find(f => plumbing.findFirstIn(f).isEmpty && !f.contains("ProbeMain")).getOrElse("?")
      .replace("dotty.tools.dotc.", "")
    val area = frames.iterator.flatMap(f => areas.collectFirst { case (k, v) if f.contains("." + k + ".") || f.contains("." + k + "$") => v }).nextOption().getOrElse("-")
    val phase = frames.reverseIterator.flatMap(f => phases.collectFirst { case (k, v) if f.contains(k) => v }).nextOption().getOrElse("other")
    (phase, s"$area | $site")

  // what kinds of "temporary" types occur in the type's parts (shallow, no Context needed)
  def shape(tp: Type): String =
    val found = mutable.SortedSet[String]()
    def visit(t: Type, depth: Int): Unit = if depth < 4 then t match
      case t: TypeVar => found += "TypeVar"
      case t: WildcardType => found += "Wildcard"
      case t: SkolemType => found += "Skolem"
      case t: TypeParamRef => found += "TypeParamRef"
      case t: TermParamRef => found += "TermParamRef"
      case t: RecThis => found += "RecThis"
      case t: LazyRef => found += "LazyRef"
      case t: ProtoType => found += "Proto"
      case t: AppliedType => visit(t.tycon, depth + 1); t.args.foreach(visit(_, depth + 1))
      case t: TypeBounds => visit(t.lo, depth + 1); visit(t.hi, depth + 1)
      case t: AndOrType => visit(t.tp1, depth + 1); visit(t.tp2, depth + 1)
      case t: RefinedType => visit(t.parent, depth + 1); visit(t.refinedInfo, depth + 1)
      case t: AnnotatedType => visit(t.parent, depth + 1)
      case t: NamedType => visit(t.prefix, depth + 1)
      case t: ExprType => visit(t.resType, depth + 1)
      case _ =>
    visit(tp, 0)
    if found.isEmpty then "plain" else found.mkString("+")

  def main(args: Array[String]): Unit =
    val dir = Path.of(args(0))
    val files = Files.walk(dir).iterator.asScala.map(_.toString).filter(_.endsWith(".scala")).toList.sorted
    val cp = System.getProperty("java.class.path")
    val opts = List("-classpath", cp, "-d", Files.createTempDirectory("probe").toString, "-nowarn", "-source", "3.0", "-Xkind-projector", "-language:implicitConversions") ++ args.drop(1)
    var ctxOut: Context = null
    val driver = new Driver:
      override protected def doCompile(compiler: Compiler, files: List[dotty.tools.io.AbstractFile])(using Context) =
        ctxOut = ctx
        super.doCompile(compiler, files)
    Uniques.probe = P
    val t0 = System.nanoTime
    val rep = driver.process((opts ++ files).toArray)
    Uniques.probe = null
    println(f"errors: ${rep.errorCount}, ${(System.nanoTime - t0) / 1e9}%.1f s")
    System.gc(); System.gc()

    val alive = mutable.HashMap[Key, Long]().withDefaultValue(0L)
    for (k, r) <- P.tracked if r.get != null do alive(k) += 1
    println("\n== totals (all events)")
    for (t, c) <- P.totals.toList.sortBy(_._1) do
      val all = c.hits + c.misses
      println(f"$t%-8s lookups=$all%,12d hits=${c.hits}%,12d (${100.0 * c.hits / all}%.1f%%)  misses=${c.misses}%,10d  hits with fresh parts=${c.hitsFresh}%,12d (${100.0 * c.hitsFresh / c.hits.max(1)}%.1f%% of hits)")

    def report(title: String, group: Key => String, top: Int) =
      println(s"\n== $title (sampled 1/$SampleEvery; ×$SampleEvery for estimates). misses | survived% | hits | hit% | freshHits")
      val g = P.sampled.toList.groupMapReduce((k, _) => group(k))((k, c) => (c.misses, alive(k), c.hits, c.hitsFresh))((a, b) => (a._1 + b._1, a._2 + b._2, a._3 + b._3, a._4 + b._4))
      for (name, (m, a, h, hf)) <- g.toList.sortBy(-_._2._1).take(top) do
        println(f"${m * SampleEvery}%,11d ${if m == 0 then "    -" else f"${100.0 * a / m}%5.1f"}%% ${h * SampleEvery}%,12d ${100.0 * h / (h + m).max(1)}%5.1f%% ${hf * SampleEvery}%,12d  $name")
    if Lambdas.enabled then Lambdas.report()
    report("by table", _.table, 10)
    report("by table/phase", k => s"${k.table} ${k.phase}", 30)
    report("by table/shape", k => s"${k.table} ${k.shape}", 30)
    report("applied: by phase/site", k => if k.table == "applied" then s"${k.phase} ${k.site}" else "(other tables)", 40)
    report("applied: by shape/area", k => if k.table == "applied" then s"${k.shape} ${k.site.takeWhile(_ != '|')}" else "(other tables)", 40)
