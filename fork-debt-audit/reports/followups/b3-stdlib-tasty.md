Note: gh search was rate-limited partway (HTTP 403); "none found" for search-based checks means the searches I could run returned nothing, plus git log greps on both repos (fetched 2026-10-22).

### I1
- verdict: caveat
- refs read:
  - https://github.com/scala/scala3/pull/26794 (merged 2026-09-17, SolalPirelli, approved by som-snytt). Whole thread: sjrd (2026-08-13) said the warning is "way too strong to be a default", only VirtualMachineError/ThreadDeath are really bad, suggested a lint flag. Reviewers discussed NonFatal only in the compiler (som-snytt on TypeComparer: NonFatal silently excludes InterruptedException/ControlThrowable, so each use needs analysis) and in FutureConvertersImpl (som-snytt: "I'd need an hour to look at concurrent code"; SolalPirelli: "undone", i.e. reverted that one hunk). The review approval noted "Possible question about nonfatal in concurrent". Nobody reviewed the Promise.scala / LazyList / BatchingExecutor hunks in detail.
  - retronym comment 2026-10-22T05:47Z with reproducer gist https://gist.github.com/retronym/993b47e02724d5f2ef1872a89415bf08: Promise and LazyList changes "seem dangerous"; java.util.concurrent applies compensating cleanup after any Throwable even at the risk of re-triggering SOE/OOME; InterruptedException is a real-world case; asks lrytz about LazyList. As of this check there is NO maintainer reply (the comment is the newest on the PR, written today).
- follow-ups: no fix/revert PR in scala/scala3 (git log origin/main on library/src/scala/concurrent and LazyList*.scala: only 7f16e81220 since 2026-09-17; PR searches for NonFatal/Promise/InterruptedException found nothing). The only cross-reference on #26794 is retronym/scala3 PR 69 (this audit). The PR closed scala/scala3#25799 and #11019.
- porting note: 2.13.x still catches Throwable here, so nothing to port; this is a Scala 3-only regression to fix in scala3 (restore `Throwable` in Promise.Transformation.run, or catch NonFatal plus InterruptedException/ControlThrowable and complete the future). Do not forward-port NonFatal to scala/scala. Also relevant: 3.10.1-RC1 is the first release with it.

### I2
- verdict: caveat
- refs read: https://github.com/scala/scala3/pull/26794 (see I1). No reviewer commented on the LazyList/LazyListIterable hunks; retronym's 2026-10-22 comment asks whether lrytz (author of the lock-free initState, scala/scala#11242, scala3 2e81d9a2e7/bcfe5f1ef6) has an opinion. No answer yet.
- follow-ups: none found in scala3. Context: lock-free initState was introduced in scala/scala#11242 (merged; review there covered cycles and exceptions: lrytz "if initialization throws, the exception is thrown on that thread; a different thread may then enter initialization" - which only holds for the Exception branch, not Errors, in scala3's narrowed catch) and refined in scala/scala#11249 (charpov, merged 2026-06-19, custom one-time latch replacing CountDownLatch inside InRace; scala3 LazyListBase has a "one-time latch" too). The narrowing to Exception exists only in scala3.
- porting note: 2.13.x LazyList.initState catches Throwable; fix belongs in scala3 (restore Throwable so _tail is restored and the latch counted down). Keep the scala3 and 2.13 copies in sync when doing so (LazyList.scala and LazyListIterable.scala both).

### I3
- verdict: caveat
- refs read: https://github.com/scala/scala3/pull/26794: BatchingExecutor hunk not discussed in review; same retronym comment (Promise/LazyList focus, InterruptedException named as real-world).
- follow-ups: none found (no scala3 issue/PR for BatchingExecutor NonFatal; git log shows only 7f16e81220 on the file).
- porting note: 2.13.x BatchingExecutor still catches Throwable in AsyncBatch.apply/resubmit; fix in scala3 only. Restoring Throwable (or at least not dropping the rest of the batch on InterruptedException/ControlThrowable) is what the reproducer needs.

### I6
- verdict: caveat
- refs read:
  - https://github.com/scala/scala/pull/11286 (lrytz, merged 2026-10-12 as 3ab962e190, approved by retronym). retronym's review (2026-10-02) found a remaining race: linkRootOf installs the Link before moving callbacks, so an unregisterCallback in that window misses the callback which then leaks at the root (3-4 leaks per 200k in a lockstep stress test), and proposed a Dekker-style fix (a35e125) plus stronger tests (0529819, "7 of these fail without the fix"). lrytz (2026-10-09) chose NOT to take the complex fix, only added a comment: a missed latch is rare, bounded because a promise links once, freed when root completes; retronym agreed but said the test improvements would still be useful. SethTisue suggested release-noting since it is a delicate area.
  - https://github.com/scala/bug/issues/13197 (closed): reports Await.result leaking CompletionLatches (100k timeouts on a pending future); related to scala/bug#13058 (firstCompletedOf, fixed earlier via onCompleteOrUnregister, 9814e05e5e).
- follow-ups: none after merge (git log origin/2.13.x on Promise.scala: only 93e8493854 since 2025-12). Unmerged: retronym:review/11286 commits 0529819b53 (deterministic tests) and a35e125 (race fix).
- porting note: port both the cancel-on-timeout/interrupt change and the unregisterCallback-follows-Link change together (PR says both Await and firstCompletedOf are affected); the Link-window leak is a known accepted limitation. Check it composes with scala3's I1 NonFatal edits in the same file (Promise.scala:265,355 region).

### I5
- verdict: caveat
- refs read:
  - https://github.com/scala/scala/pull/11243 (bluelhf, merged 2026-05-18, approved Ichoran/lrytz/SethTisue). Explicitly partial: still ClassCastException for a type-changing asArray on an EMPTY array (no ClassTag, cannot be fixed without a signature change); comment left in the method body. Ichoran showed `Array[String]().transpose(using _.toCharArray)` still throws. Also fixes double evaluation of asArray(xs(0)). Jasper-M/lrytz suggested the real fix is a new method or a `[B: ClassTag]` requirement.
  - https://github.com/scala/bug/issues/13178 (closed by the PR). Note the issue title/symptom is NPE (ClassTag(null) from the row class), not the ArrayStoreException in the audit text; same root cause.
  - PR thread: He-Pin asked "fix in Scala 3 too?"; bluelhf (2026-10-22) found that in Scala 3, ArrayOps is in project/ScalaLibraryFilesToCopy.scala (added by scala3#25033), i.e. its BYTECODE is overlaid from the Scala 2 library, so editing library/src/scala/collection/ArrayOps.scala has no effect; the fix arrives by bumping the Scala 2 library version used for the overlay. I confirmed "scala/collection/ArrayOps" is at line 12 of project/ScalaLibraryFilesToCopy.scala in the worktree.
- follow-ups: none in scala/scala after merge. No scala3 issue/PR for transpose (searched).
- porting note: do NOT patch scala3's ArrayOps.scala source (it is not compiled into the shipped class); the fix comes with the next 2.13 stdlib bump of the overlay. Keep the source copy in sync anyway to avoid drift, and expect the empty-array case to stay broken.

### I8
- verdict: clean
- refs read: https://github.com/scala/scala3/pull/26672 (merged 2026-07-31; LLM-assisted, approved by SolalPirelli, cheeseng ran the full tests; adds a regression test for an enumeration starting at -2; tested on scala-library-nonbootstrapped only). Nothing about Scala.js or the 2.13 copy in the thread.
- follow-ups: none found. library-js/src/scala/Enumeration.scala:296 still has the old `nnIds iteratorFrom start.id map (id => thisenum.apply(bottomId + id))`; scala/scala origin/2.13.x Enumeration.scala:301 has the same unfixed code; no scala/scala PR or scala/bug issue found for it (search for "Enumeration iteratorFrom" returned nothing). git log of library-js/src since the fix shows no touch of Enumeration.
- porting note: apply the same offset (`start.id - bottomId`) in library-js/src/scala/Enumeration.scala and carry over the EnumerationTest negative-id case; the test lives in library/test so it only runs against the JVM library unless Scala.js tests also run it. Could be forward-ported to scala/scala 2.13.x as well.

### I9
- verdict: clean
- refs read:
  - https://github.com/scala/scala3/pull/25205 (merged 2026-02-09, fixes #25139): removed ArrayBuilder equals overrides; som-snytt noted the serialization test had to be fixed (it was). No mention of library-js.
  - https://github.com/scala/scala/pull/11211 (merged 2026-02-20): backport of #25205; natsukagami asked if removing overrides is ABI-breaking; sjrd/lrytz: no, the method is inherited from Object.
- follow-ups: none found for library-js. library-js/src/scala/collection/mutable/ArrayBuilder.scala still has `override def equals` at lines 295, 337, 379, ... (10 hits). library-js/src has seen unrelated edits since (Range d168df2a76 #27310, boxToByte ea38aa0873, Wasm 72d15f5d8d, explicit-nulls 91e5be69aa, Scaladoc tag sweeps) so it is maintained by hand, no sync process.
- porting note: delete the equals overrides from library-js ArrayBuilder (same diff as 639fd849c8, all 10 builders) and fix Enumeration (I8); the safer long-term fix is a check that diffs library-js/src files against library/src for the files they shadow.

### I4
- verdict: caveat
- refs read: https://github.com/scala/scala/pull/11203 (sjrd, merged 2026-01-23, approved lrytz). Rationale: AccessControlException deprecated-for-removal since JDK 17, security manager disabled in JDK 24. som-snytt noted SecurityException itself is on "deprecation watch" (JDK doc: "may be deprecated in a future release"), maybe SystemProperties is not worth keeping; sjrd: hopefully the try/catch can then go entirely. The only other known SecurityException subclass is RMISecurityException, assumed irrelevant.
- follow-ups: scala/scala 294fca1e18 (2026-02-23, "remove stale '@nowarn's") dropped the `@nowarn("cat=deprecation")` on wrapAccess, which scala3's copy still carries at line 55. No other follow-up.
- porting note: replace the catch with SecurityException, drop the java.security.AccessControlException import and the @nowarn together. Scala 3's own build warns on deprecation-for-removal on newer JDKs, so check -Werror on the library build. Scala 3's tuple type `(String, String | Null)` differs from 2.13, so port by hand, not by patch.

### I7
- verdict: amended
- refs read: https://github.com/scala/scala/pull/11248 (lrytz, merged 2026-10-19 as 7cb1542f34). Origin: scala3's initialization checker flagged a cycle (Predef -> scala.package -> LazyList -> makeTailUpdater -> Predef.classOf), false positive. sjrd (2026-05-20) first said classOf is a primitive and asked to teach the checker instead; then warned a lazy val could add per-access cost in initState() and asked for benchmarks, suggesting "a separate private object". lrytz (2026-10-19) switched to the holder object. So the lazy val in scala3 (with comment "lazy val to break cycle") is the superseded shape, which sjrd flagged as a possible perf penalty.
- follow-ups: scala/scala 73d5879c21 (2026-05-20, the cited commit; the final form is the private object `TailUpdater { val updater }` in LazyList.scala:1086, used as `TailUpdater.updater.compareAndSet`). Related: scala/scala 724c19096e (2026-05-18, concurrency code moved to LazyListBase.scala) and 522889aa51/470e510c17 (PR #11249, custom one-time latch in InRace). scala3 has LazyListBase.scala with a latch already (from the 2e81d9a2e7 sync); I did not diff the latch against #11249.
- porting note: port as a private holder object (not a lazy val) in both LazyList.scala and LazyListIterable.scala; also check scala3 got #11249's latch and the `collection.AbstractSeq` parent fix that the same commit mentions.

### I10
- verdict: caveat (per item below; nothing reverted, no later amendments found)
- refs read (all scala3 PRs merged; none has a scala/scala counterpart: searched scala/scala PRs/issues for HashSet.diff, ArrayDeque.clear, equalsCharObject, BitSet isEmpty, HashMap.from; origin/2.13.x source still has every unfixed form, confirmed by grep):
  - #26275 HashSet.diff double compute (270f3f274b): approved by SolalPirelli "good with this as a cleanup PR regardless of perf". 2.13.x HashSet.scala:225 still calls rootNode.diff twice.
  - #26278 HashMap/HashSet concat getHash (8b7e2cec53): same approval wording. 2.13.x HashMap.scala:1517,1598 still call bm.getHash(rightDataIdx) again (HashSet 2 places likewise by file layout).
  - #26280 HashMap.from empty (e3406b0fb1): approved because it matches HashSet.from. 2.13.x HashMap.from:2206 has no knownSize == 0 case.
  - #26288 ArrayDeque.clear (f36ea50842): CHANGES_REQUESTED first (needless intermediate vars), then merged. Title claims O(n) to O(1) but it is still O(n) Arrays.fill, only without per-element calls. 2.13.x clear():447 still loops removeHeadAssumingNonEmpty.
  - #26294 ArrayOps.WithFilter.flatMap (7bec039ff4): reviewer noted it is technically observable (array mutated inside the filter predicate) but "not a case we have to take seriously". 2.13.x ArrayOps.scala:110 still `f(xs(i))`. NOTE: ArrayOps is an overlay file in scala3 (see I5) so the scala3 source change has no effect on shipped bytecode.
  - #27077 (035a05af5b, BitSet.isEmpty part of a big "Micro-optimizations, mostly focused on allocations" PR, jchyb approved, benchmarks run, no caveats on BitSet). 2.13.x BitSet.scala:153 still `0 until nwords forall (...)`. The scala3 version uses `while ... do`/`return` Scala 3 syntax, must be re-expressed.
  - #25958 (0c8c581143) and #25962 (b264c1965c) BoxesRunTime: found by GitHub Code Quality, author "Probably dead code anyway?" (25958); sjrd approved 25962 with a shrug emoji; 25962 changes equalsNumChar default branch to Objects.equals. 2.13.x BoxesRunTime.java:169-178 still has the null check after the instanceof tests (NPE on xc==null path via xc.charValue()).
- follow-ups: none found. SolalPirelli reproved the author of #26275-#26294 batch (He-Pin) in #26294 for LLM-generated perf PRs without benchmarks and mixing cleanups with perf; the PRs were merged as cleanups, not as measured perf wins.
- porting note: forward-port as cleanups and say so; no benchmarks exist for any of them. Disclose LLM involvement (scala/scala has LLM_POLICY.md; #26275-#26294 declare heavy LLM use). BoxesRunTime and ArrayOps changes are the ones with real behavior value (NPE, double evaluation); the rest are micro.

### H4
- verdict: unknown
- refs read: https://github.com/scala/scala3/pull/27205 (merged 2026-10-07, SolalPirelli, "Stop checking tasty UUIDs in class files"; body and review comments only discuss removing the UUID check and an unrelated Run.scala executor/memory-model thread by bishabosha). The `if false` is not mentioned anywhere in the PR, reviews or comments, and the diff shows it was introduced in this commit (`catch case e: RuntimeException =>` became `... if false =>`), next to deleted checkTastyUUID code, so a debugging remnant is the likely reading.
- follow-ups: none. git log origin/main -- SymbolLoaders.scala since 2026-10-07 only shows ff88d25f2b; origin/main still contains the `if false` (grep count 1 at a98b104aaa). No scala3 issue or PR found for it (searched handleUnpicklingExceptions, SymbolLoaders if false). No test in compiler/test or tests greps for the "could not be read, failing with" / "is broken, reading aborted" messages, which is why CI did not catch it.
- porting note: Scala 3-only; fix is deleting `if false` (and adding a test for a corrupt or too-new .tasty reporting the IOException with the file path). Not a fork-debt item; file as a scala3 issue/PR.

### H1
- verdict: caveat
- refs read:
  - https://github.com/scala/scala/pull/11274 (lrytz, merged 2026-08-20; SethTisue approved, nitpicks only): "TASTy reader: 3.7 is the last supported version"; bumps supported TASTy to 28.7, also fixes the disabled header check (see H3), adds doc/internal/tastyreader.md text and a TastyHeaderUnpicklerTest (LLM-written synthetic TASTy, lrytz says not reviewed in detail).
  - https://github.com/scala/bug/issues/13152 (closed 2026-08-20): SethTisue's rationale for the freeze: since Scala 3.8 the Scala 3 stdlib is compiled by Scala 3 itself (no longer the 2.13 jar), and the Scala 2 TASTy reader can't handle union types (explicit nulls) or transparent inline methods; Predef.assert handling triggered the discussion, explicit nulls sealed it. Decision: reader is a migration aid, migrate via 3.7 or 3.3 LTS first; "honestly the TASTy reader isn't something we wanted to maintain forever". Contributors-forum thread 7376 is the earlier source. Planned work was only: early clean error, 2.13.19 release-note, a doc page.
- follow-ups: no later bump planned or opened (searched PRs "TASTy reader" in scala/scala: newest is #11274 itself; git log on src/compiler/scala/tools/tasty and nsc/tasty after 2026-08-20 shows nothing). 34c9fb97e9 is contained in tag v2.13.19-M1 (tag date 2026-10-17), so it IS released in the milestone, not in 2.13.18.
- porting note: do not "sync" 28.8+ into scala/scala; the freeze is deliberate and documented. Anyone changing the format layer would need the doc page and test to move together. Treat as policy, not drift; downgrade this finding.

### H3
- verdict: amended
- refs read:
  - https://github.com/scala/scala/pull/10670 (bishabosha, merged 2024-02-12, d3e7895798 is its first commit "Upgrade tasty format, copy new tasty config"): the toolOverrides mechanism came in to allow reading experimental/RC TASTy. bishabosha said removing the toolOverride (to support the 3.4 RC and 3.3.x nightlies) was a "good to have" follow-up; the PR thread never discusses validOverride making the gate a no-op.
  - https://github.com/scala/scala/pull/11274: states "The header version check was effectively disabled, because toolOverrides is always empty, so validOverride was always true."; adds TastyHeaderUnpicklerTest; fix is in TastyHeaderUnpickler.scala.
- follow-ups: scala/scala 34c9fb97e9 (2026-08-18, merged 2026-08-20) is the fix, contained in v2.13.19-M1 (2026-10-17); correct the audit statement "unreleased" to "in 2.13.19-M1, not in 2.13.18". 2.13.13-2.13.18 remain affected. Also related 2.13 TASTy PRs from that era: #10689, #10694 (named by SethTisue as follow-up work after #10670; not read).
- porting note: nothing to port to scala3 (it is the Scala 2 copy of the header logic). If anyone backports to 2.13.x maintenance, take the TastyHeaderUnpickler change together with the 28.7 bump in TastyFormat/ScalaSettings/TastyUnpickler, otherwise the fixed gate rejects every valid header.

### H2
- verdict: caveat
- refs read: https://github.com/scala/scala3/pull/25676 (SolalPirelli, merged 2026-04-17; sjrd CHANGES_REQUESTED then re-reviewed). Review shaped the final design: sjrd required all four read{,Long}{Nat,Int}() to check overflow in their own domains (not a separate readNonNegNat); nat > Int.MaxValue and int overflow throw UnpickleException; the 9*7=63-bit case became an assert (UnpickleException = invalid input, AssertionError = reader bug). The same commit also changes the WRITE side: TastyBuffer.writeNat/LongNat refuse negative args, PositionPickler/PositionUnpickler lineSizes handling (first-line size written as content.length when lastIndex == -1; the unpickler uses -1 vs Int.MaxValue was debated, -1 kept), TreeUnpickler readNat call sites (191, 244), TastyPrinter, plus MiMa fallout (TastyReader is public), and a test in TastyUnpickerTest.
- follow-ups: none to the fix itself (git log origin/main -- tasty/src after 2026-04-17: nothing). Later commit 140a0dda86 (#27341, 2026-10-20) fixes TastyPrinter crashes but not the reader.
- porting note: the fix to scala/scala is only the reader half (TastyReader.readNat/readInt checks throwing scala.tools.tasty.UnpickleException; 2.13's current code is `readLongNat().toInt` at TastyReader.scala:74,79). The writer/pickler/unpickler pieces do not exist on the Scala 2 side (reader only) so skip them; take the new test. Safe because 2.13's reader is frozen at 28.7, so a straightforward patch applies.
