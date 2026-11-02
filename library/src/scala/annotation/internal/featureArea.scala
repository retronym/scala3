package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** The feature area of a field whose package and class do not say it (`cc`, `match-types`, `inline-macros`,
 *  `explicit-nulls`, `tasty-classfile`, `scala2`, `interactive`), for the compiler's state map, `tools/state-map`.
 */
final class featureArea(area: String) extends StaticAnnotation
