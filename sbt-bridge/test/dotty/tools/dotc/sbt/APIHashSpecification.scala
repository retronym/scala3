package dotty.tools.dotc
package sbt

import xsbti.api.*
import xsbt.ScalaCompilerForUnitTesting
import xsbt.api.HashAPI

import org.junit.Test
import org.junit.Assert.*

/** Tests `APIHash` against Zinc's `HashAPI`.
 *
 *  Each variant declares a member `m` in its own object. If `APIHash` hashes
 *  two of them alike, so must `HashAPI`, or a marker hash would miss a change
 *  that Zinc sees. The converse need not hold: `APIHash` hashes members in
 *  declaration order, `HashAPI` regardless of it.
 */
class APIHashSpecification:
  import APIHashSpecification.*

  @Test
  def equalAPIHashImpliesEqualZincHash(): Unit =
    val ms = extractMembers()
    val failures =
      for
        (i, a) <- ms
        (j, b) <- ms
        if i < j && apiHash(a) == apiHash(b) && zincHash(a) != zincHash(b)
      yield s"${variants(i)}\n  vs\n${variants(j)}"
    assertTrue(failures.mkString("APIHash misses differences HashAPI sees:\n", "\n\n", ""), failures.isEmpty)

  /** The variants differ in ways their clients can observe, so `APIHash`
   *  must tell them apart, including through the marker hash of an inline
   *  method that calls one with a refinement in its signature.
   */
  @Test
  def distinguishesTheVariants(): Unit =
    val ms = extractMembers()
    val collisions =
      for
        (i, a) <- ms
        (j, b) <- ms
        if i < j && apiHash(a) == apiHash(b)
      yield s"${variants(i)}\n  vs\n${variants(j)}"
    assertTrue(collisions.mkString("Variants hash alike:\n", "\n\n", ""), collisions.isEmpty)

  /** Two extractions of the same source yield distinct `Structure`s and
   *  `Definition`s, so this catches hashes that depend on their identity.
   */
  @Test
  def stableAcrossExtractions(): Unit =
    val first = extractMembers()
    val second = extractMembers()
    val unstable =
      for
        ((i, a), (_, b)) <- first.zip(second)
        if apiHash(a) != apiHash(b) || markers(a) != markers(b)
      yield variants(i)
    assertTrue(unstable.mkString("Hashes differ between extractions:\n", "\n\n", ""), unstable.isEmpty)

object APIHashSpecification:
  val variants: IndexedSeq[String] = IndexedSeq(
    "def m(x: Int): Int = x",
    "def m(y: Int): Int = y",
    "def m(x: Long): Int = 1",
    "def m(x: Int, y: Int): Int = x",
    "def m(x: Int)(y: Int): Int = x",
    "def m(x: Int)(using Ctx): Int = x",
    "def m(x: Int)(implicit c: Ctx): Int = x",
    "def m(x: => Int): Int = x",
    "def m(x: Int*): Int = 1",
    "def m(x: Int = 1): Int = x",
    "def m[T](x: T): T = x",
    "def m[T <: AnyVal](x: T): T = x",
    "def m[T >: Null](x: T): T = x",
    "def m(x: Int)[T](y: T): T = y",
    "def m(x: Int)[T <: Int](y: T): T = y",
    "final def m(x: Int): Int = x",
    "protected def m(x: Int): Int = x",
    "@deprecated def m(x: Int): Int = x",
    "def m(x: Ctx): x.type = x",
    "def m(x: Ctx { type T = Int }): Int = 1",
    "def m(x: Ctx { type T = String }): Int = 1",
    "def m(x: Ctx { type T <: AnyRef }): Int = 1",
    "def m(x: Ctx { type T = Int; type U = Int }): Int = 1",
    "def m(x: Ctx { type U = Int; type T = Int }): Int = 1",
    "def m(x: Ctx { type T; type U = T }): Int = 1",
    "def m(x: Ctx { type T = Int })(y: Ctx { type T = Int }): Int = 1",
    "val m: Int = 1",
    "var m: Int = 1",
    "lazy val m: Int = 1",
    "final val m = 1",
    "final val m = 2",
    "type m = Int",
    "type m[T] = List[T]",
    "class m[T]",
    "type m[-T] = T => Unit",
    "type m <: AnyRef",
    "type m >: Null <: AnyRef",
    "class m",
    "trait m",
    "object m",
    "class m[+T]",
    "class m[-T]",
    "inline def m(x: Int): Int = x",
    "inline def m(x: Int): Int = x + 1",
    "inline def r(x: Ctx { type T = Int }): Int = 1\n  inline def m: Int = r(null)",
    "inline def r(x: Ctx { type T = String }): Int = 1\n  inline def m: Int = r(null)",
    "inline def r(using c: Ctx): Ctx = c\n  inline def m(using Ctx): Ctx = r",
    "inline def r(c: Ctx): Ctx = c\n  inline def m(using c: Ctx): Ctx = r(c)",
    "inline def r[T](x: T): T = x\n  inline def m: Int = r(1)",
    "inline def r[T](x: List[T]): Int = 1\n  inline def m: Int = r(Nil)",
    "inline def m: Class[?] = classOf[java.util.List[? <: Ctx]]",
    "inline def m: Class[?] = classOf[java.util.List[? >: Ctx]]",
    "inline def m[T](x: T)[U <: T](y: U): T = x",
    "inline def m[T](x: T)[U >: T](y: U): T = x",
  )

  private val source =
    variants.zipWithIndex
      .map((decl, i) => s"object V$i {\n  $decl\n}\n")
      .mkString("class Ctx\n", "", "")

  def extractMembers(): IndexedSeq[(Int, Definition)] =
    val apis = new ScalaCompilerForUnitTesting().extractApiFromSrc(source)
    variants.indices.map: i =>
      val v = apis.find(_.name == s"V$i").getOrElse(sys.error(s"no V$i in ${apis.map(_.name)}"))
      i -> v.structure.declared.find(d => d.name == "m" || d.name == s"V$i$$.m").getOrElse(sys.error(s"no m in V$i"))

  def apiHash(d: Definition): Int = APIHash.definition(d, 0)

  def zincHash(d: Definition): Int = HashAPI(_.hashDefinition(d), includePrivate = true)

  def markers(d: Definition): Seq[String] =
    d.annotations.toSeq.map(_.base).collect { case c: Constant => c.value }
