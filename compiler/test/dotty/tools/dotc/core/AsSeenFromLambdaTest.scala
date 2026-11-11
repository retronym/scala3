package dotty.tools.dotc.core

import dotty.tools.DottyTest
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.TreeOps
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.TypeOps.AsSeenFromMap
import dotty.tools.dotc.core.Types.*

import org.junit.Test
import org.junit.Assert.{assertFalse, assertSame, assertTrue, fail}

/** `AsSeenFromMap` rebinds the parameters of the lambda types it maps as it goes (see `mapOverLambda`). Compare it
 *  with the two-pass mapping (map, then `newLikeThis`'s substitution of the new binder for the old one).
 */
class AsSeenFromLambdaTest extends DottyTest:

  override protected def initializeCtx(fc: FreshContext): Unit =
    super.initializeCtx(fc)
    fc.setSetting(fc.settings.YfuseLambdaRebind, "eager")

  private def twoPass(pre: Type, cls: Symbol)(using Context): AsSeenFromMap =
    new AsSeenFromMap(pre, cls):
      fuseRebinding = false

  private class FusedMap(pre: Type, cls: Symbol)(using Context) extends AsSeenFromMap(pre, cls):
    def gaveUp = !fuseRebinding

  /** Lambdas the fused map gave up on, mapping them the two-pass way */
  private var gaveUp = 0

  private def isLambda(tp: Type)(using Context): Boolean = tp.existsPart(_.isInstanceOf[LambdaType], StopAt.None)

  /** Checks `info` as seen from `pre` and `cls`; returns whether the result was `info` itself. */
  private def check(info: Type, pre: Type, cls: Symbol, what: => String)(using Context): Boolean =
    val expectedMap = twoPass(pre, cls)
    val expected = expectedMap(info)
    val actualMap = FusedMap(pre, cls)
    val actual = actualMap(info)
    if actualMap.gaveUp then gaveUp += 1
    // `==` compares lambdas structurally, but not types (match types) that compare their parts with `==` without the
    // binders seen so far; for them, the printed structure and `=:=`
    def same = actual == expected || actual.toString == expected.toString && actual.frozen_=:=(expected)
    if !same then
      fail(i"$what: as seen from $pre\n  two-pass: $expected\n  fused:    $actual\n  (${expected.toString}\n   ${actual.toString})")
    if expected eq info then assertSame(i"$what: unchanged", info, actual)
    else assertTrue(i"$what: changed, but fused returned the original", actual ne info)
    actual eq info

  private val source = """
    |package p
    |
    |trait Base[T] {
    |  type Alias = T
    |  type HK[X <: T] = Map[X, T]
    |  type HKBounded[X] <: Map[X, T]
    |  type HKNested[X] = [Y] =>> Map[X, (Y, T)]
    |  type HKUnchanged[X] = List[X]
    |  def unchanged[A](x: A): A
    |  def unchangedMono(x: Int): String
    |  def poly[A <: T](x: A): A
    |  def bounds[A <: B, B <: T](a: A): B
    |  def dependent(x: T)(y: x.type): y.type
    |  def dependentPath(x: Base[T])(y: x.Alias): x.Alias
    |  def onlyInner[A](x: A)(y: T): A
    |  def onlyOuter[A <: T](x: A)(y: Int): A
    |  def curried[A](x: A)[B <: A](y: B)(z: T): (A, B)
    |  def polyFun[A](f: [B <: A] => B => T): T
    |  def depFun(f: (x: T) => x.type): T
    |  def higherKinded[F[_ <: T], A](fa: F[A & T]): F[T]
    |  def ctx[A](using a: A, t: T): T
    |  def byName[A](x: => A, y: => T): T
    |  def repeated[A](xs: A*)(ts: T*): T
    |  def thisType[A](x: A): Base.this.type
    |  def matchType[A](x: A): A match { case Int => T; case _ => A }
    |  def refined[A](x: { def f(b: Int): (A, T) }): A
    |  def recursive[A](x: { type X <: List[A]; def f: (X, T) }): A
    |  def annotated[A](x: A @annotation.unchecked.uncheckedVariance, y: T @annotation.unchecked.uncheckedVariance): A
    |  def annotatedUnchanged[A](x: A @annotation.unchecked.uncheckedVariance): A
    |  def unionInter[A](x: A | T, y: A & T): A
    |  def static[A](x: Predef.type, y: x.String): A
    |  def wildcard[A](x: List[? <: T], y: List[? >: A]): A
    |}
    |
    |trait Mid[U] extends Base[List[U]] {
    |  def mid[A](x: A, u: U)(using Base[U]): A
    |}
    |
    |abstract class Sub extends Mid[Int]
    |
    |trait Ap[F[_]] {
    |  def pure[A](a: A): F[A]
    |  def self: Ap[F] = new Ap[F] { def pure[A](a: A): F[A] = Ap.this.pure(a) }
    |}
    |
    |class SubSeq[A] extends scala.collection.immutable.AbstractSeq[A] {
    |  def apply(i: Int): A = ???
    |  def length: Int = 0
    |  def iterator: Iterator[A] = Iterator.empty
    |}
    |class SubMap[K, V] extends scala.collection.immutable.AbstractMap[K, V] {
    |  def get(k: K): Option[V] = None
    |  def iterator: Iterator[(K, V)] = Iterator.empty
    |  def removed(k: K): Map[K, V] = this
    |  def updated[V1 >: V](k: K, v: V1): Map[K, V1] = this
    |}
    |""".stripMargin

  private def snippetClasses(tree: tpd.Tree)(using Context): List[ClassSymbol] =
    val classes = List.newBuilder[ClassSymbol]
    tree.foreachSubTree:
      case td: tpd.TypeDef if td.symbol.isClass => classes += td.symbol.asClass
      case _ =>
    classes.result()

  /** Every lambda in the declarations of every base class of `cls`, as seen from `cls.thisType`. */
  private def checkBaseClasses(cls: ClassSymbol)(using Context): (Int, Int) =
    var checked, same = 0
    for bc <- cls.baseClasses.tail; d <- bc.info.decls.toList if isLambda(d.info) do
      checked += 1
      if check(d.info, cls.thisType, bc, i"$bc.$d in $cls") then same += 1
    (checked, same)

  @Test def handPicked(): Unit =
    checkCompile("typer", source) { (tree, c) =>
      given Context = c
      assertFalse(c.reporter.hasErrors)
      val base = requiredClass("p.Base")
      val sub = requiredClass("p.Sub")
      def info(name: String) = base.info.decl(name.toTermName).orElse(base.info.decl(name.toTypeName)).info
      def seen(name: String) = check(info(name), sub.thisType, base, s"Base.$name")
      // A map that changes nothing returns the lambda it was given
      assertTrue(seen("unchanged"))
      assertTrue(seen("unchangedMono"))
      assertTrue(seen("HKUnchanged"))
      assertTrue(seen("annotatedUnchanged"))
      for name <- List("unchanged", "poly", "dependent", "curried", "HK", "HKNested") do
        assertTrue(check(info(name), base.thisType, base, s"Base.$name from Base.this"))
      // Changes inside the outer lambda, only in an inner one, or under nested lambdas
      for name <- List("poly", "bounds", "dependent", "dependentPath", "onlyInner", "onlyOuter", "curried", "polyFun",
          "depFun", "higherKinded", "ctx", "byName", "repeated", "thisType", "matchType", "refined", "recursive",
          "annotated", "unionInter", "wildcard", "Alias", "HK", "HKBounded", "HKNested") do
        assertTrue(s"Base.$name should change", !seen(name))
      // `static` selects a static alias from a parameter, and changes nothing else
      assertTrue(seen("static"))
      // `Ap.this.F` as seen from the anonymous subclass is `Ap.this.F` again
      val ap = requiredClass("p.Ap")
      val anon = snippetClasses(tree).find(_.owner.owner == ap).get
      assertTrue(check(ap.info.decl("pure".toTermName).info, anon.thisType, ap, "Ap.pure from the anonymous Ap"))
      val (checked, _) = checkBaseClasses(sub)
      assertTrue(checked > 20)
    }

  /** Every lambda type among the members of the snippet's classes and of library classes, with all their base
   *  classes, as seen from the class.
   */
  @Test def libraryAndSnippet(): Unit =
    checkCompile("typer", source) { (tree, c) =>
      given Context = c
      assertFalse(c.reporter.hasErrors)
      val classes = snippetClasses(tree) ++ List(
        "scala.collection.immutable.List", "scala.collection.immutable.Vector", "scala.collection.immutable.LazyList",
        "scala.collection.immutable.HashMap", "scala.collection.immutable.TreeMap", "scala.collection.mutable.ArrayBuffer",
        "scala.collection.mutable.HashMap", "scala.collection.immutable.Range", "scala.collection.immutable.WrappedString",
        "scala.collection.ArrayOps", "scala.collection.StringOps", "scala.Option", "scala.util.Either",
        "scala.concurrent.Future", "scala.math.BigDecimal", "java.util.ArrayList", "java.util.HashMap",
        "java.util.concurrent.ConcurrentHashMap", "java.lang.String").map(requiredClass)
      var checked, same = 0
      for cls <- classes do
        val (n, s) = checkBaseClasses(cls)
        checked += n
        same += s
      assertTrue(s"checked $checked", checked > 3000)
      assertTrue(s"unchanged $same of $checked", same > 0 && same < checked)
      assertTrue(s"gave up on $gaveUp of $checked", gaveUp < checked / 10)
    }
