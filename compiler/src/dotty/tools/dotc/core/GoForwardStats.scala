package dotty.tools.dotc.core

import scala.annotation.internal.sharable

/** DO NOT MERGE: with `-Ddotty.goForwardStats`, a histogram of how many denotation transforms each outermost
 *  `SingleDenotation.current` that enters `goForward` runs (counting the `cur.current` recursion), and how often each
 *  denotation transformer index runs, printed at exit. Single-threaded use only.
 */
@sharable object GoForwardStats:
  final val enabled = sys.props.contains("dotty.goForwardStats")
  private var depth = 0
  private var transforms = 0
  private val perCall = new Array[Long](64)
  private val perIndex = new Array[Long](256)
  private val classOf = new Array[String](256)
  private var extendOnly = 0L

  def enter(): Unit = depth += 1
  def exit(): Unit =
    depth -= 1
    if depth == 0 then
      perCall(transforms min 63) += 1
      transforms = 0
  def transformed(index: Int, transformer: AnyRef): Unit =
    transforms += 1
    perIndex(index) += 1
    if classOf(index) == null then classOf(index) = transformer.getClass.getName
  def extended(): Unit = extendOnly += 1

  if enabled then
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      val calls = perCall.sum
      val total = perIndex.sum
      println(s"[goForwardStats] outermost goForward calls: $calls, transforms: $total, validFor extensions without a transform: $extendOnly")
      println("[goForwardStats] transforms per call: " +
        perCall.zipWithIndex.collect { case (n, k) if n > 0 => f"$k:$n (${100.0 * n / calls}%.1f%%)" }.mkString(" "))
      println(s"[goForwardStats] distinct transformer classes: ${classOf.filter(_ != null).distinct.length}")
      for i <- perIndex.indices if perIndex(i) > 0 do
        println(f"[goForwardStats]   index $i%3d ${100.0 * perIndex(i) / total}%5.1f%% ${perIndex(i)}%10d ${classOf(i)}")
    }))
end GoForwardStats
