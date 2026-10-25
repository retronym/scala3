package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field whose writes are published to other readers by `how`: `"Caching"` (a stamp and a value through the
 *  `core.Caching` helpers), `"Publish"` (release/acquire through `util.Publish`), `"volatile"` (one volatile value) or
 *  `"packed"` (several values packed into one immutable value in a volatile field).
 */
final class publishedBy(how: String) extends StaticAnnotation
