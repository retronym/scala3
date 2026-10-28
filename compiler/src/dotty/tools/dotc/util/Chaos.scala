package dotty.tools.dotc.util

import java.util.concurrent.ThreadLocalRandom

/** Tests: `-Ddotc.interactive.chaos=<ms>` makes the parallel readers pause for
 *  a random time, up to `<ms>` milliseconds, at the points where their
 *  interleaving matters (a claim taken, a statement abandoned, a completion
 *  unwound, a typing checkpoint), so that a soak on an idle machine reaches the
 *  interleavings that only a loaded one did: noise injection, or schedule
 *  fuzzing, in the manner of IBM's ConTest. Off, every site is one volatile
 *  read.
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

  /** Pause with probability `1 / oneIn`, for up to `maxMillis` */
  inline def at(site: String, oneIn: Int = 2): Unit =
    if enabled then pause(site, oneIn)

  private def pause(site: String, oneIn: Int): Unit =
    val rnd = ThreadLocalRandom.current()
    if rnd.nextInt(oneIn) == 0 then
      pauses += 1
      val micros = rnd.nextLong(maxMillis.toLong * 1000L)
      if micros == 0 then Thread.`yield`()
      else Thread.sleep(micros / 1000, ((micros % 1000) * 1000).toInt)
