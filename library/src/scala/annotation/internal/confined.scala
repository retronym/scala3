package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field of an object that only one thread reaches: a `Reader`'s, a traversal's, a thread local's. As JCIP's
 *  `@ThreadConfined`.
 */
final class confined extends StaticAnnotation
