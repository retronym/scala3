package dotty.tools
package dotc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, LongAdder}

import scala.jdk.CollectionConverters.*

/** `-Ddotc.interactive.lockStats`: the waits of every reader on the completion lock or on a root's claim
 *  (`Reader.recordLockWait`), summed over the JVM, by what the waiter wanted to do (a completion, a wait for another
 *  reader's completion before its own, a peek, a bring-forward, ...) and by what the holder was doing. Printed to
 *  stderr when the JVM exits, or by `summary`; `reset` starts a new window (a harness reads one per cell).
 *
 *  Kept in both lock granularities (`-Yinteractive-completion-lock:roots|global`) and in the serialised mode, so that
 *  they are compared on the same numbers. Off, each wait costs one static field read.
 */
object LockStats:
  val on: Boolean = System.getProperty("dotc.interactive.lockStats") != null

  final class Tally:
    val nanos = new LongAdder
    val count = new LongAdder
    val max = new AtomicLong
    def add(n: Long): Unit =
      nanos.add(n); count.increment(); max.accumulateAndGet(n, math.max)
    def render: String = f"${count.sum}%d waits, ${nanos.sum / 1e6}%.1f ms, longest ${max.get / 1e6}%.1f ms"

  private val total = new Tally
  private val byWaiter = new ConcurrentHashMap[String, Tally]()
  private val byHolder = new ConcurrentHashMap[String, Tally]()

  /** The kind of a holder's work from its rendered `What` (`kind subject`): the kind, by the kinds `CompletionLock`
   *  names, else the first word. */
  private val kinds = List("Namer signature", "load source", "load classpath", "unpickle TASTy", "complete (", "bring forward",
    "inherited cache", "lazy TASTy read", "new version of a denotation", "commit typer state", "cleanup",
    "wait for a completion (isCompleting)", "wait for a completion (force)", "read action", "lazy reference", "lazy annotation",
    "package objects", "change to a published scope", "pass types the statement", "implicit scope")
  private def holderKind(root: String): String =
    kinds.find(root.startsWith).getOrElse(root.takeWhile(_ != ' '))

  def waited(nanos: Long, waiterKind: String, holderWhat: String): Unit =
    total.add(nanos)
    byWaiter.computeIfAbsent(waiterKind, _ => new Tally).nn.add(nanos)
    byHolder.computeIfAbsent(holderKind(holderWhat), _ => new Tally).nn.add(nanos)

  def reset(): Unit =
    total.nanos.reset(); total.count.reset(); total.max.set(0)
    byWaiter.clear(); byHolder.clear()

  def totalNanos: Long = total.nanos.sum
  def totalCount: Long = total.count.sum
  def maxNanos: Long = total.max.get

  def summary: String =
    def table(m: ConcurrentHashMap[String, Tally]) =
      m.asScala.toList.sortBy(-_._2.nanos.sum).map((k, t) => s"    $k: ${t.render}").mkString("\n")
    s"""[lock-stats] total: ${total.render}
       |  by what the waiter wanted:
       |${table(byWaiter)}
       |  by what the holder was doing:
       |${table(byHolder)}""".stripMargin

  if on then
    Runtime.getRuntime.nn.addShutdownHook(new Thread(() => if total.count.sum > 0 then System.err.nn.println(summary)))
