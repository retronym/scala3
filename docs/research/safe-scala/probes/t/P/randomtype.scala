package test
import language.experimental.captureChecking
import language.experimental.separationChecking
object Probe uses scala.util.Random:
  val x: Nothing = scala.util.Random
  val y: Nothing = scala.util.Random.self
