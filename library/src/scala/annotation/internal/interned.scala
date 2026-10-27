package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field of an interning table or counter: shared by every reader, whose entries or values are identities (kind D
 *  of the compiler's state map, `tools/state-map`).
 */
final class interned extends StaticAnnotation
