package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field whose writes are published to other readers by `how`: `"Publish"` (release/acquire, a `Published` field
 *  and its payloads, or `sharing.Publish`'s array slots), `"volatile"` (one volatile value),
 *  `"holder"` (an immutable holder: several values as one immutable object in a volatile field, JCIP 3.4.2), or `"init"` (written only before
 *  the owner is shared with other readers, read only after). On a class, it applies to each field that does not say
 *  otherwise.
 */
final class publishedBy(how: String) extends StaticAnnotation
