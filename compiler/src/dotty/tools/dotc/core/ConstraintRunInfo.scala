package dotty.tools.dotc
package core

import Contexts.*
import config.Printers.{default, typr}
import dotty.tools.dotc.util.NoSourcePosition

import scala.annotation.internal.{benignRace, cache}
import scala.compiletime.uninitialized

trait ConstraintRunInfo { self: Run =>
  // A statistic for `printMaxConstraint`, reported by every constraint made, from every reader: the pair is stored
  // in two fields, so racing readers can pair one's size with another's constraint (finding; it only misprints)
  @cache @benignRace("a statistic printed under -Ydetailed-stats; a race can pair a size with another constraint")
  private var maxSize = 0
  @cache @benignRace("a statistic printed under -Ydetailed-stats; a race can pair a size with another constraint")
  private var maxConstraint: Constraint | Null = null
  def recordConstraintSize(c: Constraint, size: Int): Unit =
    if (size > maxSize) {
      maxSize = size
      maxConstraint = c
    }
  def printMaxConstraint()(using Context): Unit =
    if maxSize > 0 then
      val printer = if ctx.settings.YdetailedStats.value then default else typr
      printer.println(s"max constraint size: $maxSize")
      ctx.handleRecursive("printing max constraint of size", () => maxSize.toString):
        printer.println(s"max constraint = ${maxConstraint.nn.show}")

  protected def reset(): Unit = maxConstraint = null
}
