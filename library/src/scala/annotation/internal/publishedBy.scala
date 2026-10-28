package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field whose writes are published to other readers by `how`: `"Publish"` (release/acquire, a `Published` field
 *  and its payloads, or `sharing.Publish`'s array slots), `"volatile"` (one volatile value),
 *  `"packed"` (several values packed into one immutable value in a volatile field), or `"init"` (written only before
 *  the owner is shared with other readers, read only after). On a class, it applies to each field that does not say
 *  otherwise.
 */
final class publishedBy(how: String) extends StaticAnnotation
