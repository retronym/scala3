package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field read and written only while `lock` is held: `"this"` or another field's monitor, `"completionLock"` (the
 *  base's completion lock, or the root's claim with parallel readers: `Sharing.exclusive`), `"rootClaim"` (a claim in
 *  `CompletionRoots`: `Sharing.claimed`), or `"writer"` (a write action of the interactive driver, which runs with no
 *  reader). On a class, it applies to each field that does not say otherwise. As JCIP's `@GuardedBy`.
 */
final class guardedBy(lock: String) extends StaticAnnotation
