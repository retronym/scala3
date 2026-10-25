package dotty.tools.pc.tests.completion

import java.io.File
import java.net.URI
import java.util.concurrent.{CountDownLatch, CyclicBarrier, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.language.unsafeNulls

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.pc.base.TestResources

import org.junit.Assert.assertEquals
import org.junit.Test

/** Finding I84 (docs/_docs/internals/interactive-multi-reader.md §8): a
 *  `LazyRef` that a `TypeMap` made (`mapOver`) and that is first forced in a
 *  later run moves the map's context to that run. The map is shared by every
 *  `LazyRef` it made, and these live in types that outlast the run (the
 *  infos of classpath symbols), so several readers of the new run can force
 *  them at once. The move used to be a swap of the map's `mapCtx` undone in
 *  a `finally`: the first reader to finish put back the old run's context
 *  while another was still mapping, which then brought a denotation forward
 *  in the old run ("denotation class Enum invalid in run 4. ValidFor:
 *  Period(83.1-2)").
 *
 *  Here k readers force k such refs of one map in a new run, all inside the
 *  map's `apply` together; one leaves first, and the others then look at the
 *  run of the map's context.
 */
class LazyRefRunSpliceSuite:

  private val settings =
    List("-color:never", "-Yinteractive-readers",
      "-classpath", TestResources.classpath.map(_.toString).mkString(File.pathSeparator))

  private val k = 4

  @Test def concurrentForcesInANewRunSeeTheNewRun(): Unit =
    for round <- 1 to 20 do
      val driver = new InteractiveDriver(settings, CachedLogicalPackage.none)
      driver.run(URI.create("file:///A.scala"), "object A")
      val oldCtx = driver.currentCtx

      val inside = new CyclicBarrier(k)
      val firstOut = new CountDownLatch(1)
      val seen = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
      val map = new TypeMap(using oldCtx):
        def apply(tp: Type): Type =
          if tp.isInstanceOf[ConstantType] then
            val first = inside.await(10, TimeUnit.SECONDS) == 0
            if !first then
              firstOut.await(10, TimeUnit.SECONDS)
              seen.add(mapCtx.runId)
            tp
          else mapOver(tp)
      val refs = (0 until k).map { i =>
        given Context = oldCtx
        map(LazyRef(_ => ConstantType(dotty.tools.dotc.core.Constants.Constant(i)))).asInstanceOf[LazyRef]
      }

      driver.run(URI.create("file:///B.scala"), "object B")
      val newCtx = driver.currentCtx
      assert(newCtx.runId != oldCtx.runId)

      val failure = new AtomicReference[Throwable]()
      val threads = refs.map { ref =>
        new Thread((() => {
          try
            driver.withCancelChecker(() => false) {
              ref.ref(using newCtx)
            }
          catch case ex: (Exception | AssertionError) => failure.compareAndSet(null, ex)
          // the first one out: its `apply` returned, and its force with it
          finally firstOut.countDown()
        }): Runnable)
      }
      threads.foreach(_.start())
      threads.foreach(_.join(30000))
      if failure.get != null then throw failure.get
      assertEquals(s"round $round: the runs the map's context was in", List.fill(k - 1)(newCtx.runId), seen.toArray.toList)
