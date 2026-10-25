package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field of a lazy fact with a black hole: computed once on first need by one owner, which others wait for (a
 *  denotation's info, a completer's state, an inherited cache; kind B of the compiler's state map, `tools/state-map`).
 */
final class lazyFact extends StaticAnnotation
