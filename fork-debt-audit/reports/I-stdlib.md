# I-stdlib: Scala 3 fork of the 2.13 standard library

S3 = /Users/jzaugg-virtus/code/.worktrees/scala3/scala3-untracked-fork-debt-803909 ; S2 = /Users/jzaugg-virtus/code/scala/scala (origin/2.13.x, head a0ebc676ba 2026-10-20)

## Fork base and process

- Import: scala3 13fd5af95d 2025-07-15 "[stdlib] embed the history of the scala 2 stdlib into dotty's history (#23522)". Second parent 7a75a09b13 = scala/scala merge of PR #11041 (2025-04-16); in the scala/scala clone that is **0e9026785e** (2.13.x, after v2.13.16, before v2.13.17-M1/v2.13.17). Full per-file scala 2 history is therefore present in scala3 (`git log -- library/src/scala/collection/Iterator.scala` goes back to 2005). Then f5bb1c72a1 added `import scala.language.`2.13`` everywhere, 1f13619265 (#23769) ported capture-checked collections.
- A sync process exists and is partly followed: `project/scripts/syncStdLib.scala` (b8ba827474, 2025-12-21; usage `scala run syncStdLib.scala -- <scala2-repo> <from-tag> <to-tag>`, rewrites patches with `Upstream: scala/scala@<sha>` footers) and `Versions.scala` `scala2Version = "2.13.18"` (used for scalajs-scalalib and as "version we synced with"). The script was evidently used once (4 commits with the `Upstream:` footer: 29e126b968, 11631da9d6, 1a41833efb, 6fc91e597e, Oct-Nov 2025). Nothing after v2.13.18 (2025-11-15) was synced with the script, although `scala2Version` was not moved. 31 non-merge commits touch src/library after v2.13.18, 49 after the fork base.
- Instead, individual contributors have manually ported most of the later fixes (PRs #25205, #26274, #26845, #26886, #26100, #26568 ...), and several s3 fixes flowed the other way (see F8-F10). No documented ownership of the sync; CI file `.github/workflows/stdlib.yaml` runs the ported scala 2 unit tests (#25021).
- Net result: the fork is in much better sync than the compiler areas. Of 49 s2 commits since base, about 25 are docs/style/refactor, 17 bug/perf fixes are already ported, and the real misses are listed below.

## Pairs

| s3 file | s2 file | port date | later s2 commits (count since base 0e9026785e) | notes |
|---|---|---|---|---|
| library/src/** (574 files common with src/library; 137 s3-only: annotation/internal, runtime/function, quoted, compiletime, caps, ...) | src/library/** | 2025-07-15 (base 2025-04-16) | 49 non-merge | only s2 file missing in s3 is rootdoc.txt |
| library/src/scala/collection/immutable/LazyList.scala + LazyListBase.scala | same | 2025-07-15 | 7 (73d5879c21, 68ff53340e, 724c19096e, 522889aa51, 470e510c17, + docs) | s3 ported lock-free initState (#26100) but not 73d5879c21 |
| library/src/scala/collection/immutable/LazyListIterable.scala + LazyListIterableBase.scala | (s3-only CC copy of LazyList) | 2025-08-20 (#23769) | tracks LazyList | body identical to LazyList modulo capture annotations; fixes were applied to both |
| library/src/scala/collection/Seq.scala | same | 2025-07-15 | 11 | indexOfSlice/lastIndexOfSlice series ported in #26886 |
| library/src/scala/concurrent/impl/Promise.scala | same | | 2 | 93e8493854 not ported (F6) |
| library/src/scala/collection/ArrayOps.scala | same | | 1 functional | fd63e80f4a not ported (F5) |
| library/src/scala/sys/SystemProperties.scala | same | | 2 | f87a7db7f0 not ported (F4) |
| library-js/src/** (24 override files, copies of scala-js scalalib overrides-2.13) | no scala/scala origin; origin is scala-js/scala-js (not checked out locally) | | n/a | s3 fixes in library/ were not applied to the JS copies (F9) |
| library/src/scala/Char,Int,Long,Short,Byte,Float,Double,Boolean,Unit.scala | generated in s2 (genprod/ src/library/scala/Int.scala etc.) | | none functional | re-written for Scala 3 (`extension`/`self`, deprecations of Rich*), not comparable line by line |

## Findings

### F1: Promise.Transformation.run now catches only NonFatal, so an InterruptedException thrown by a Future callback no longer fails the future
- severity: high
- confidence: medium (code reading only; could not run: `scala-cli -S 3.nightly` timed out offline)
- s3: library/src/scala/concurrent/impl/Promise.scala:568 (`case NonFatal(t) => handleFailure(t, ec)`), also :493
- s2: src/library/scala/concurrent/impl/Promise.scala still `case t: Throwable => handleFailure(t, ec)`; this is a scala 3 -> scala 2 divergence introduced by scala3 7f16e81220 2026-09-17 "Warn when catching `Error`s (#26794)"
- what: `handleFailure` is written to complete the promise for `wasInterrupted || NonFatal(t)` (an InterruptedException completes the future with Failure(InterruptedException) and re-sets the thread interrupt flag) and to rethrow everything else. `NonFatal` does not match InterruptedException, so after #26794 the `catch` guard filters it out before `handleFailure` runs: `Future.successful(1).map(_ => throw new InterruptedException)` (or any `Future { blockingCall() }` interrupted during `Thread.sleep`/`await`) propagates out of `run()` into the executor and the derived promise is never completed. Callers waiting on it hang forever. The `wasInterrupted` branch in `handleFailure` is now dead code, which is the tell.
- verified: read both versions; confirmed handleFailure at Promise.scala:503-513; confirmed `7f16e81220` diff touches exactly these two catches; grep for a library/test Future+InterruptedException test found none.

### F2: LazyList/LazyListIterable.initState only catches Exception; an Error thrown by the thunk leaves the list in the "being evaluated" state forever
- severity: high
- confidence: medium (code reading; repro could not be run)
- s3: library/src/scala/collection/immutable/LazyList.scala:325-334 and LazyListIterable.scala:327-334 (`var ex: Exception | Null`, `case e: Exception =>`)
- s2: src/library/scala/collection/immutable/LazyList.scala (`case t: Throwable => ex = t; null`); s3 divergence from 7f16e81220 (#26794)
- what: initState CASes `_tail` from the thunk to `Thread.currentThread` as an "evaluating" marker, runs the thunk, and in the failure path restores `_tail = fun` (retry later) and counts down an InRace latch before rethrowing. With `catch Exception`, any `Error` from the thunk (StackOverflowError on deep lazy chains, OOM, `NotImplementedError` from `???`, AssertionError, LinkageError, ControlThrowable subclasses of Throwable) skips the restore. The list stays marked as being evaluated by the current thread: the next access on that thread throws the misleading "LazyList evaluation depends on its own result" RuntimeException and any other thread creates an InRace and waits for a `countDown` that never comes (hang). In 2.13 the list simply re-evaluates the thunk on the next access.
- verified: compared the catch clauses in both files; s2 head still has `Throwable`; `NotImplementedError extends Error` (library/src/scala/NotImplementedError.scala).

### F3: BatchingExecutor AsyncBatch.apply/resubmit changed to NonFatal, so queued tasks are dropped when a task throws a fatal-by-NonFatal-definition throwable
- severity: medium
- confidence: medium
- s3: library/src/scala/concurrent/BatchingExecutor.scala:175 (`case NonFatal(t) => t`), :189 (`case NonFatal(inner)`)
- s2: src/library/scala/concurrent/BatchingExecutor.scala (`case t: Throwable => t`, `case inner: Throwable => if (NonFatal(inner)) ... else inner`); s3 7f16e81220
- what: `AsyncBatch.apply` used to return any Throwable so that `run()` could first `resubmit` the remaining queued Runnables (`if (this.size > 0) submitForExecution(this)`) and then rethrow the cause. Now an InterruptedException/ControlThrowable/VirtualMachineError escapes `apply` directly; `resubmit` is skipped and the rest of the batch (`size > 0`) is silently lost, with the batch's runnables (Future callbacks) never run. Same class of change as F1/F2 but in the scheduler core.
- verified: diffed the three catch sites against base and head of s2; read AsyncBatch.run lines ~160-170.

### F4: SystemProperties still catches the deprecated-for-removal AccessControlException
- severity: low
- confidence: high
- s3: library/src/scala/sys/SystemProperties.scala:19,56-58
- s2: src/library/scala/sys/SystemProperties.scala ; fix commit: f87a7db7f0 2026-01-22 "Don't catch the deprecated-for-removal AccessControlException." (catch `SecurityException`)
- what: AccessControlException is deprecated-for-removal since JDK 17; s2 switched to its parent SecurityException so that SystemProperties keeps linking when the class is eventually removed. s3 keeps the import and an `@nowarn("cat=deprecation")`.
- verified: grep shows import and catch; no s3 commit mentions it.

### F5: ArrayOps.transpose is still the old broken implementation
- severity: medium
- confidence: high
- s3: library/src/scala/collection/ArrayOps.scala:1380
- s2: src/library/scala/collection/ArrayOps.scala ; fix commit: fd63e80f4a 2026-04-29 "fix(array-ops): partially fix transpose" (scala/bug#13178)
- what: the old code derives the row/result classes from `xs.getClass.getComponentType` (A) instead of from the actual rows, so a type-changing `asArray` yields ClassCastException/ArrayStoreException; 2.13.x builds with the first row's class and documents the remaining empty-array limitation. s3 still has the old body.
- verified: viewed s3 body (uses `aClass = xs.getClass.getComponentType`, no `first`/`isFirst`); grep -F 'first.getClass' returns nothing.

### F6: Await.result/ready timeout or interrupt leaves the CompletionLatch registered on the future; unregisterCallback does not follow Links
- severity: medium (leak of callbacks on long-lived futures, repeated polling with timeouts)
- confidence: high
- s3: library/src/scala/concurrent/impl/Promise.scala:265-272 (tryAwait0), :355-362 (unregisterCallback)
- s2: src/library/scala/concurrent/impl/Promise.scala ; fix commit: 93e8493854 2026-09-30 "Unregister Future Await latches on timeout or interrupt" (scala/bug#13197)
- what: s2 now registers an explicit Transformation for the latch, wraps the acquire in try/finally and calls `unregisterCallback(t)` when `l.result eq null`; and `unregisterCallback` now forwards to the linked root promise (`Link` case) so callbacks moved by flatMap are removed there too (also fixes the same leak for firstCompletedOf from b2dcd69aec which s3 has). s3 still uses `onComplete(l)` and has no Link branch.
- verified: read s3 tryAwait0 and unregisterCallback; no `Link` case in unregisterCallback.

### F7: LazyList tail updater created eagerly at LazyList class init
- severity: low (startup cost, one AtomicReferenceFieldUpdater per JVM; not a correctness issue)
- confidence: high
- s3: library/src/scala/collection/immutable/LazyList.scala:1230 (`private lazy val _tailUpdater`), LazyListIterable.scala:1254
- s2: fix commit 73d5879c21 2026-05-20 "Create LazyList's tail updater on first use" (holder object instead of a lazy val, also reparents LazyListBase on immutable.AbstractSeq; s3 already has the AbstractSeq parent)
- what: s3's variant is a lazy val (with a cycle-breaking comment), so every access pays the lazy-val check; s2 uses a private holder object.
- verified: grep.

### F8 (reverse, s3 -> s2): Enumeration.ValueSet.iteratorFrom wrong for enumerations with negative ids
- severity: medium (correctness bug, still present in scala 2.13.x and in the s3 JS override copy)
- confidence: high
- s3 fix: 4a3ecde9cb 2026-07-31 "Fix Enumeration.ValueSet.iteratorFrom for negative IDs (#26672)" changes library/src/scala/Enumeration.scala only
- s2: src/library/scala/Enumeration.scala:301 still `nnIds iteratorFrom start.id map ...` (missing `- bottomId`); s3 library-js/src/scala/Enumeration.scala:296 also still has the unfixed line.
- what: ids are stored offset by bottomId, but iteratorFrom passes the raw id, so values below `start` can be returned.
- verified: grep in s2 head and in library-js.

### F9 (s3 internal): library-js override copies were not updated with fixes made to library/
- severity: medium
- confidence: high
- s3: library-js/src/scala/collection/mutable/ArrayBuilder.scala:295,337,379,... (equals overrides on ofRef/ofByte/...) vs 639fd849c8 (#25205, 2026-02-09, removed them from library/ only; s2 backported as 945f7b12c9); library-js/src/scala/Enumeration.scala:296 (F8). Also library-js/src/scala/Array.scala:647 still uses the old range loop (functionally fine).
- what: library-js overrides are full-file copies. Changes to the corresponding file in library/ must be duplicated by hand; two of them were not. Result on Scala.js: ArrayBuilder equals returns true for distinct builders with same elems reference/size (the bug fixed on the JVM), Enumeration.iteratorFrom bug.
- verified: git show --stat 639fd849c8 / 4a3ecde9cb touch only library/; grep in library-js.

### F10 (reverse, s3 -> s2): perf/NPE fixes made in scala3 that scala 2.13.x still lacks
- severity: low (perf) / low (BoxesRunTime null)
- confidence: high
- items (all verified absent in S2 head):
  - 270f3f274b HashSet.diff computes `rootNode.diff` twice (src/library/scala/collection/immutable/HashSet.scala:225)
  - 8b7e2cec53 HashMap/HashSet node concat recomputes `bm.getHash(rightDataIdx)` (HashMap.scala:1517, HashSet.scala:1598)
  - e3406b0fb1 `HashMap.from` for knownSize == 0 allocates a builder
  - f36ea50842 ArrayDeque.clear() is O(n) removal loop (s2 src/library/scala/collection/mutable/ArrayDeque.scala `clear`)
  - 7bec039ff4 ArrayOps.WithFilter.flatMap calls `f(xs(i))` re-reading the array (ArrayOps.scala:110)
  - 035a05af5b BitSet.isEmpty uses `0 until nwords forall` (allocation)
  - 0c8c581143 / b264c1965c BoxesRunTime.equalsCharObject(null Character, ...) NPE ordering and `xn.equals(yc)` -> Objects.equals (BoxesRunTime.java:166-195 in s2)
  - fad92c001a: s3-only bug, not relevant to s2 (`.nn` on `next` in HashMap stepper NPE-d at end of a chain; fixed in s3).

## Already ported / not applicable

Ported to scala3 (checked in library/src): 0308ce1292 `\s` escape (#26845, ae0be24606); a5d3955ced LongMap mask (#26274, e2d1809e00 independent fix); 457aa47d5a ArraySeq.ofFloat/ofDouble equals (immutable and mutable); bd6bdf8a62 + 7ffa564733 Range unsigned arithmetic (SerialVersionUID 4L); 8ff51d58fb Array.range (4cb2511c21); 1af76c7725 ScalaRunTime.wrapXArray null checks (29e126b968, then mapNull); e0235b0205 HashMap node toString; b280028af0, d80eadeba8, 987cb54279, 6273c6ac0c, 3efa02444f, 9210485113, 8bf38f300d, 880bf8e52c, c5e297e746, f9864feb27 indexOfSlice/lastIndexOfSlice/kmpSearch series (#26886, 25f66d9170); 68ff53340e + 724c19096e + 522889aa51 + 470e510c17 lock-free LazyList initState with InRace latch (#26100, bcfe5f1ef6 for LazyListIterable); 945f7b12c9 ArrayBuilder equals (origin was scala3 #25205); 1800167b50 copyToArray/elemsToCopyToArray; fef0d42c55 Await perf helpers (Future.timeoutError / waitUndefinedError); 76d23c153b Regex Replacement single StringBuffer; 6b9224ea57/7152f6716d ListBuffer Predecessor; af61c6d2c2 String.format removal (#26568); 7dcf3a6007 @uncheckedOverride (6fc91e597e); 7bb0b855b6, 80589cccb2 doc fixes (1a41833efb, 11631da9d6); 516ea3283f/1ddafac558 Ordering.min/max U <: T: s2 head net state equals s3 (revert applied on 2.12.x only; both have `U <: T` + @uncheckedOverride).
Not applicable: 9ff1cd92a2 Statics.releaseFence invokeExact (scala3 Statics.java calls `VarHandle.releaseFence()` directly); 341e062686/6077da2e59/5eb6b78f73/eb401fa384/afd0c3bf88/bdce2137cd/e676905616/1292645774/f3ad9d7656/294fca1e18 doc, style, copyright, stale @nowarn, local refactors.
Intentional divergences (explained by scala3 commits): Predef (assert/assume/@elidable removed in 99fdcc1d12, ArrowAssoc, Rich* wrappers deprecated, `nn`/`eq`/`ne` extensions, mapNull); numeric value classes (Char/Int/...) rewritten with extension methods; Duration.require lost unreachable `case _` (49b4241ee5); immutable.RedBlackTree drop/take/slice lost the unused `[A: Ordering]` context bound.

## Method notes

- Pairing was by path (574 files identical path). Because the base is known precisely (0e9026785e in the scala/scala clone; the sha 7a75a09b13 recorded in scala3 does not exist in the clone, history was rewritten) I exported the base tree to .../scratchpad/work/I-stdlib/base and diffed s3 against *base* (not head), so s3 changes are separated from upstream drift. Tools: comment-stripped diff (`nc.py`), identifier-bag comparison (`bag.py`) over all common files, normalising `.nn`, `| Null`, `private[this]`, `[_]`, capture annotations.
- Divergence categories (bag diff, top files): (a) explicit-nulls (`.nn`, `| Null`, `@stableNull`, `compiletime.uninitialized`) in RedBlackTree, CollisionProofHashMap, LinkedHashMap, Vector, Iterator, HashMap; (b) capture checking annotations (`^`, `uses`, `.only[Control]`, `-> `) in Try, Iterator, Factory, Map, IsSeq, BuildFrom, LazyListIterable; (c) Scala 3 syntax (`?`, `using`, `then/do`, `private`) everywhere; (d) `scala.language.`2.13`` header; (e) deprecations/removals specific to Scala 3 (Predef, Rich*, any2stringadd, strictfp); (f) rewritten numeric classes; (g) real logic divergences all trace to a scala3 commit: Range/Array.range/ArraySeq/Seq (ports), 7f16e81220 (F1-F3), 49b4241ee5 warnings cleanup, fad92c001a (HashMap stepper `.nn` NPE fix), and the s3-originated fixes listed in F8/F10. No unexplained logic divergence found in the spot-checked files (Predef, LazyListIterable vs LazyList, immutable/mutable RedBlackTree, Map, Try, Array, Enumeration, Either, Duration, Iterator, BatchingExecutor, LinkedHashMap).
- Scala.js overrides (library-js/src) originate from scala-js/scalajs-scalalib overrides-2.13, which is not available locally; only checked against s2 base/head for the specific fixes above.
- F1/F2/F3 are semantic regressions introduced inside scala3 relative to both base and head of 2.13.x; I could not execute a reproducer (no local build of the s3 library; `scala-cli -S 3.nightly` timed out). Repro sketches: `Future.successful(1).map(_ => throw new InterruptedException)` never completes; `val ll = LazyList.empty.lazyAppendedAll({ throw new StackOverflowError }); ll.isEmpty` twice, second call reports the self-reference error; same from a second thread hangs.
- Time boxed; the other ~25 s2 commits are doc/refactor and were not diffed hunk-by-hunk.
