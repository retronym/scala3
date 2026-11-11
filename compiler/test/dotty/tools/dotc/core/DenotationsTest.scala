package dotty.tools.dotc.core

import dotty.tools.DottyTest
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.SymDenotations.NoDenotation
import dotty.tools.dotc.core.Types.*

import scala.collection.mutable.ListBuffer

import org.junit.Test
import org.junit.Assert.{assertEquals, assertTrue}

class DenotationsTest extends DottyTest:

  private def syms(n: Int): List[SingleDenotation] =
    List.tabulate(n)(i =>
      newSymbol(defn.RootClass, Names.termName(s"alt$i"), Flags.EmptyFlags, defn.IntType).denot)

  /** The order of `alternatives` and `altsWith`, and the order in which the predicate is evaluated,
   *  follow the alternatives from left to right, for both left-deep and right-deep trees.
   */
  @Test def altsWithOrder: Unit =
    val alts = syms(5)
    val leftDeep = alts.reduceLeft[Denotation](MultiDenotation(_, _))
    val rightDeep = alts.reduceRight[Denotation](MultiDenotation(_, _))
    val balanced = MultiDenotation(MultiDenotation(alts(0), alts(1)), MultiDenotation(alts(2), MultiDenotation(alts(3), alts(4))))
    for denot <- List(leftDeep, rightDeep, balanced) do
      assertEquals(alts, denot.alternatives)
      val visited = ListBuffer[Symbol]()
      val odd = denot.altsWith { s => visited += s; alts.indexWhere(_.symbol eq s) % 2 == 1 }
      assertEquals(alts.map(_.symbol), visited.toList)
      assertEquals(List(alts(1), alts(3)), odd)
      assertEquals(Nil, denot.altsWith(_ => false))

  @Test def altsWithSingle: Unit =
    val alt = syms(1).head
    assertEquals(List(alt), alt.alternatives)
    assertEquals(Nil, alt.altsWith(_ => false))
    assertTrue(NoDenotation.alternatives.isEmpty)
