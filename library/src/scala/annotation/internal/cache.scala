package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field that caches a value derivable from other state: dropping it changes performance, not results (kind C of
 *  the compiler's state map, `tools/state-map`).
 */
final class cache extends StaticAnnotation
