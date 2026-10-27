package dotty.tools.dotc.util

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceArray}

/** Tests: `-Ddotc.interactive.chaos=<ms>` makes the parallel readers pause for
 *  a random time, up to `<ms>` milliseconds, at the points where their
 *  interleaving matters (a claim taken, a statement abandoned, a completion
 *  unwound, a typing checkpoint), so that a soak on an idle machine reaches the
 *  interleavings that only a loaded one did: noise injection, or schedule
 *  fuzzing, in the manner of IBM's ConTest. Off, every site is one volatile
 *  read.
 *
 *  With chaos, or with `-Ddotc.interactive.trace`, it is also a flight
 *  recorder: each site, and each `note`, is kept in a ring of the last
 *  events across threads, which a failure the soaks see reports with
 *  `recent` (the interleaving that led to it, which no one stack shows).
 */
object Chaos:

  /** The longest pause in milliseconds, or 0 when off */
  val maxMillis: Int =
    System.getProperty("dotc.interactive.chaos") match
      case null => 0
      case "" | "true" => 3
      case s => s.toIntOption.getOrElse(3)

  val enabled: Boolean = maxMillis > 0

  @volatile private var pauses = 0L

  /** How many pauses have been taken */
  def count: Long = pauses

  /** Is the flight recorder on? */
  val recording: Boolean = enabled || System.getProperty("dotc.interactive.trace") != null

  private val RingSize = 4096
  private val ring = new AtomicReferenceArray[String](RingSize)
  private val next = new AtomicLong(0)
  private val t0 = System.nanoTime()

  /** Pause with probability `1 / oneIn`, for up to `maxMillis`; recorded */
  inline def at(site: String, oneIn: Int = 2): Unit =
    if recording then pause(site, oneIn)

  /** Record an event in the flight recorder, when it is on */
  inline def note(inline event: String): Unit =
    if recording then record(event)

  private def record(event: String): Unit =
    val i = next.getAndIncrement()
    ring.set((i % RingSize).toInt, f"${(System.nanoTime() - t0) / 1000}%10d us ${Thread.currentThread.getName}%-22s $event")

  /** The last `n` events recorded, oldest first, or a line saying the recorder is off */
  def recent(n: Int = 200): String =
    if !recording then "<no flight recorder: run with -Ddotc.interactive.trace or -Ddotc.interactive.chaos>"
    else
      val end = next.get()
      val from = math.max(0L, math.max(end - n, end - RingSize))
      (from until end).iterator.map(i => ring.get((i % RingSize).toInt)).filter(_ != null).mkString("\n")

  private def pause(site: String, oneIn: Int): Unit =
    record(site)
    val rnd = ThreadLocalRandom.current()
    if enabled && rnd.nextInt(oneIn) == 0 then
      pauses += 1
      val micros = rnd.nextLong(maxMillis.toLong * 1000L)
      if micros == 0 then Thread.`yield`()
      else Thread.sleep(micros / 1000, ((micros % 1000) * 1000).toInt)
