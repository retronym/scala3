package dotty.tools.dotc
package core

import Contexts.*
import config.Printers.{default, typr}
import dotty.tools.dotc.util.NoSourcePosition

import scala.annotation.internal.{benignRace, cache, guardedBy}
import scala.compiletime.uninitialized

trait ConstraintRunInfo { self: Run =>
  // A statistic for `printMaxConstraint`. Every constraint made reports its size, from every reader: the size is
  // tested without the monitor (it only grows, so a stale value costs a lock taken for nothing), and the pair is
  // stored, and printed, with the monitor of a concurrent base.
  @cache @benignRace("only grows; a stale read costs a lock, and the pair is rechecked and stored under the monitor")
  private var maxSize = 0
  @cache @guardedBy("this")
  private var maxConstraint: Constraint | Null = null
  def recordConstraintSize(c: Constraint, size: Int)(using Context): Unit =
    if (size > maxSize) {
      Sharing.serialised[this.type, Unit](this) {
        if size > maxSize then
          maxSize = size
          maxConstraint = c
      }
    }
  def printMaxConstraint()(using Context): Unit =
    val (size, constraint) = Sharing.serialised[this.type, (Int, Constraint | Null)](this)((maxSize, maxConstraint))
    if size > 0 then
      val printer = if ctx.settings.YdetailedStats.value then default else typr
      printer.println(s"max constraint size: $size")
      ctx.handleRecursive("printing max constraint of size", () => size.toString):
        printer.println(s"max constraint = ${constraint.nn.show}")

  protected def reset(): Unit = Sharing.locking[this.type, Unit](this) { maxConstraint = null }
}
