package dotty.tools.dotc.core.tasty

import dotty.tools.DottyTest
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.TypeError
import dotty.tools.dotc.reporting.{Message, StoreReporter}
import dotty.tools.dotc.core.Decorators.em
import dotty.tools.tasty.besteffort.BestEffortTastyFormat.ERRORtype
import dotty.tools.dotc.core.Symbols.defn

import org.junit.Test
import org.junit.Assert.*

class TreePicklerTest extends DottyTest:

  /** A `TypeError` while a type is pickled is reported, and the tree it is in is pickled as an error */
  @Test def recoversFromATypeError(): Unit =
    val reporter = new StoreReporter(null)
    given Context = ctx.fresh.setReporter(reporter)
    class Boom extends TypeError:
      def toMessage(using Context): Message = em"boom"
    // a type with a length field, whose pickling fails inside it
    val tpe = AppliedType(defn.ListClass.typeRef, List(LazyRef.of(throw new Boom)))
    val pickler = new TastyPickler(defn.ObjectClass, isBestEffortTasty = false)
    val treePkl = new TreePickler(pickler, Attributes(
      sourceFile = "Test.scala", scala2StandardLibrary = false, explicitNulls = false,
      captureChecked = false, withPureFuns = false, isJava = false, isOutline = false))
    treePkl.pickle(List(tpd.TypeTree(tpe)))
    assertEquals(1, reporter.errorCount)
    treePkl.compactify()
    assertEquals(List(ERRORtype), (0 until treePkl.buf.length).map(i => treePkl.buf.bytes(i) & 0xff).toList)
