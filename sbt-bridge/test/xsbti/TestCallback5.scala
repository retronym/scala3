package xsbti

import scala.collection.mutable.ArrayBuffer
import xsbti.api.ClassLike

/** A `TestCallback` that asks the bridge to hash APIs itself, and records what it sends. */
class TestCallback5(mode: AnalysisCallback5.ApiMode, optimizedSealed: Boolean, materialise: Boolean = true)
    extends TestCallback with AnalysisCallback5 {
  /** `(full, thin, hashes)` under `CHECK`; `full` is null under `HASHES`. */
  val sent = new ArrayBuffer[(ClassLike | Null, ClassLike, ClassHashes)]

  override def apiMode(): AnalysisCallback5.ApiMode = mode
  override def useOptimizedSealed(): Boolean = optimizedSealed
  override def materialiseLibraryMembers(binaryClassName: String): Boolean = materialise

  override def api(source: VirtualFileRef, thinClass: ClassLike, hashes: ClassHashes): Unit =
    sent += ((null, thinClass, hashes))

  override def apiCheck(source: VirtualFileRef, fullClass: ClassLike, thinClass: ClassLike, hashes: ClassHashes): Unit =
    sent += ((fullClass, thinClass, hashes))
    api(source, fullClass)
}
