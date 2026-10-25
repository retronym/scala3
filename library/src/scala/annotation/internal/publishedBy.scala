package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field whose writes are published to other readers by `how`: `"Caching"` (a stamp and a value through the
 *  `core.Caching` helpers), `"Publish"` (release/acquire through `util.Publish`), `"volatile"` (one volatile value) ,
 *  `"packed"` (several values packed into one immutable value in a volatile field), or `"init"` (written only before
 *  the owner is shared with other readers, read only after). On a class, it applies to each field that does not say
 *  otherwise.
 */
final class publishedBy(how: String) extends StaticAnnotation
