package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field of tree state: a tree's type or attachments, or a session's typed tree (kind E of the compiler's state
 *  map, `tools/state-map`).
 */
final class treeState extends StaticAnnotation
