package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field filled while its owner is hidden and frozen once `event` publishes it: `"completion"`, the completion of
 *  the symbol whose state it is (a class's scope, `MutableScope.published`). Before that only the completer writes it;
 *  after, only a write action of the interactive driver or the owner's one writer (`MutableScope.writing`) changes it,
 *  and readers read it with no lock. Checked when the program runs, not by the state map: `-Ycheck-frozen-scopes` fails
 *  a change to a published scope from anywhere else. On a class, it applies to each field that does not say otherwise.
 */
final class frozenAfter(event: String) extends StaticAnnotation
