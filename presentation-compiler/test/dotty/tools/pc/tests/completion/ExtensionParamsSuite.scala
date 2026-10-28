package dotty.tools.pc.tests.completion

import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import scala.language.unsafeNulls

import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** Finding I86 (docs/_docs/internals/interactive-multi-reader.md §8): the
 *  methods of one `extension` share its parameter trees (`desugar.extMethod`
 *  prepends the same clauses to each), and completing a method indexes and
 *  types ahead its parameters, writing `SymOfTree` and `TypedAhead` on those
 *  trees, which typing the method's definition consumes. Two readers
 *  completing or typing sibling methods wrote and removed each other's
 *  attachments: "N <: ... does not have a symbol" from `symbolOfTree`, or
 *  `NoDenotation.owner` from a parameter typed without its symbol.
 *
 *  Here an extension group of many methods, and k files that each call a
 *  share of them, are opened in a fresh run and typed by k + 1 readers at
 *  once, over and over; every file must type as it does alone.
 */
class ExtensionParamsSuite:

  private val settings =
    List("-color:never", "-Ximport-suggestion-timeout", "0", "-Yinteractive-parallel",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))

  private val k = 6
  private val methods = 60
  private val rounds = 30

  private val extSource =
    (0 until methods).map(i => s"    def m$i(y: Int)(using CharSequence): N = if y > $i then x else x\n")
      .mkString("object E:\n  extension [N <: AnyRef](x: N)\n", "", "")

  private def userSource(t: Int) =
    (0 until methods).filter(_ % k == t).map(i => s"  def f$i(s: String)(using CharSequence): String = E.m$i(s)($i)\n")
      .mkString(s"object U$t:\n", "", "")

  private val files: List[(URI, SourceFile)] =
    def file(name: String, text: String) =
      val uri = URI.create(s"file:///$name.scala")
      uri -> SourceFile.virtual(uri, text)
    file("E", extSource) :: (0 until k).toList.map(t => file(s"U$t", userSource(t)))

  @Test def siblingExtensionMethodsTypedAtOnce(): Unit =
    val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
    // one reader: the files type without errors
    driver.openAll(files)
    for (uri, source) <- files do
      val ds = driver.withCancelChecker(() => false)(driver.readAll(uri, source)((_, _, ds) => ds.filter(_.level >= 2).map(_.message)))
      assertEquals(s"$uri alone", Nil, ds)
    for round <- 1 to rounds do
      driver.openAll(files)
      val start = new CountDownLatch(1)
      val failure = new AtomicReference[Throwable]()
      val errors = new java.util.concurrent.ConcurrentHashMap[String, String]()
      // the users first, the extension group last, so that its typing meets
      // the users' completions of its methods
      val threads = (files.tail :+ files.head).map { (uri, source) =>
        new Thread(null, (() => {
          try
            start.await()
            driver.withCancelChecker(() => false) {
              val ds = driver.readAll(uri, source)((_, _, ds) => ds.filter(_.level >= 2).map(_.message))
              if ds.nonEmpty then errors.put(uri.toString, ds.mkString("; "))
            }
          catch case ex: (Exception | AssertionError) => failure.compareAndSet(null, ex)
        }): Runnable, s"ext-reader-${uri.getPath}", 64L << 20)
      }
      threads.foreach(_.start())
      start.countDown()
      threads.foreach(_.join(120000))
      if failure.get != null then throw AssertionError(s"round $round", failure.get)
      assertEquals(s"round $round: errors", "{}", errors.toString)
