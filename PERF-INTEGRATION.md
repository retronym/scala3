# scala3-perf-integration

An integration branch for performance measurement: `upstream/main` plus retronym's open performance PRs that are upstream-ready (measured, merge cleanly). Not for merging upstream; each PR goes upstream on its own.

Base: upstream/main at 74dd858355 (Bring denotations forward in one loop, skipping identity transformers, #27357), 2026-10-28.

## Included (merge order)

| PR | Title | Merge |
|---|---|---|
| scala/scala3#27359 | Don't pickle positions of type annotations from another source file | clean |
| scala/scala3#27343 | Find ClassBTypes for classes referenced only by inlined classpath code | clean |
| retronym/scala3#15 | Let TreeCopier skip the envelope of the copies it makes; adds `-Yno-parallel-pickling`, `-Yno-prime-spans`, `-Yno-check-positions` | clean |
| retronym/scala3#33 | Memoize capturesImpliedByFields per class | clean |
| retronym/scala3#35 | Collect overload alternatives without quadratic list copying | clean |
| retronym/scala3#45 | Pickle equal types the same, and compactify TASTy without searching | clean |
| retronym/scala3#55 | Memoise the last instantiation of a lambda type | clean |
| retronym/scala3#56 + #61 | RefChecks: only check the member types of merged denotations; skip member lookups for names declared once | clean |
| retronym/scala3#57 | Record two-operand recursion details without a closure | clean |
| retronym/scala3#59 | Cache JVM descriptors of BTypes | clean |
| retronym/scala3#64 | Cache the overloaded denotation of a member set that cannot merge | trivial conflict in `Config.scala` (two new `inline val`s side by side) |

`-Yno-parallel-pickling` (from #15) keeps TASTy pickling on the compiler thread, so single-thread profiles and counters see it.

## Excluded

| PR | Why |
|---|---|
| retronym/scala3#29, #30, #43 | superseded by #45 |
| retronym/scala3#58 + #60 | small TASTy buffers / buffer pool: conflict with #45's `CompactableTastyBuffer`; measured as an allocation saving with no compiler-thread speedup, so not worth porting for this branch |
| retronym/scala3#62 | parked by its author |
| retronym/scala3#63 | sketch on #61, untimed, conflicts with main |
| retronym/scala3#34 (nestmates), #40, #41, #37, #47, #36, #42 | semantics, Zinc or diagnostics, not performance |
| scala/scala3#27264 | Java parser parity, not performance |
| scala/scala3#27357 | merged upstream (now in the base) |
