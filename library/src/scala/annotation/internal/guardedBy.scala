package scala.annotation.internal

import language.experimental.captureChecking

import scala.annotation.StaticAnnotation

/** A field read and written only while `lock` is held: `"this"` or another field's monitor, `"completionLock"` (the
 *  base's completion lock, or the root's claim with parallel readers: `Sharing.exclusive`), or `"rootClaim"` (a claim in
 *  `CompletionRoots`: `Sharing.claimed`). As JCIP's `@GuardedBy`.
 */
final class guardedBy(lock: String) extends StaticAnnotation
