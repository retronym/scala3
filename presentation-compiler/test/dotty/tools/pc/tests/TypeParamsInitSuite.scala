package dotty.tools.pc.tests

import java.io.File
import java.util.concurrent.{CountDownLatch, ConcurrentLinkedQueue}

import scala.language.unsafeNulls

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.SymDenotations.TypeParamsCompleter
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.parsing.Parsers.Parser
import dotty.tools.dotc.typer.Typer
import dotty.tools.dotc.util.SourceFile
import dotty.tools.pc.base.TestResources

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

/** Finding I91 (docs/_docs/internals/interactive-multi-reader.md §8): the
 *  first request for the type parameters of a type member's completer, on two
 *  readers at once, indexed the parameters twice (a check-then-set), so the two
 *  readers got different parameter symbols and the loser's were never entered
 *  in the scope that typing the member uses.
 */
class TypeParamsInitSuite:
  @Test def `first-request-from-several-threads-indexes-once`(): Unit =
    val settings = List("-color:never", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    given ctx0: Context = driver.currentCtx
    val threads = 8
    var distinct = 0
    var completed = 0
    for i <- 0 until 1500 do
      val source = SourceFile.virtual(s"<tp$i>", s"class C$i { type T[X, Y] = Map[X, Y] }")
      val stats = new Parser(source).parse() match
        case untpd.PackageDef(_, stats) => stats
        case t => List(t)
      val typer = new Typer
      val ctx = ctx0.fresh.setSource(source).setOwner(defn.EmptyPackageClass).setScope(defn.EmptyPackageClass.info.decls)
      // indexing enters into the empty package's published scope: a write action, as the driver's own runs are
      driver.asWriter(typer.index(stats)(using ctx))
      val cls = defn.EmptyPackageClass.info.decls.lookup(typeName(s"C$i"))
      val tsym = cls.info.decls.lookup(typeName("T"))
      tsym.infoOrCompleter match
        case c: TypeParamsCompleter =>
          completed += 1
          val start = new CountDownLatch(1)
          val seen = new ConcurrentLinkedQueue[Int]()
          val failed = new ConcurrentLinkedQueue[Exception]()
          val ts = (0 until threads).map { _ =>
            val t = new Thread((() =>
              start.await()
              try seen.add(System.identityHashCode(c.completerTypeParams(tsym)(using ctx.fresh).head))
              catch case e: Exception => failed.add(e)): Runnable)
            t.start(); t
          }
          start.countDown()
          ts.foreach(_.join())
          if seen.stream.distinct.count != 1 || !failed.isEmpty then distinct += 1
        case _ => ()
    assertTrue("no type member had a type parameter completer", completed > 0)
    assertEquals("type members whose threads saw different type parameter symbols or failed", 0, distinct)
