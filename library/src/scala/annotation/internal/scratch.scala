package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field of per-request scratch: one reader writes and reads it, and it is thrown away with the request (kind A of
 *  the compiler's state map, `tools/state-map`).
 *
 *  @see scala.annotation.internal.confined
 */
final class scratch extends StaticAnnotation
