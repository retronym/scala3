# Fusion-dependent miniphase behaviour: local bug reports

Not filed. Written with LLM assistance (Claude), see `LLM_POLICY.md`.

Fusing miniphases into a MegaPhase is presented as an optimisation: `docs/_docs/internals/overall-structure.md` says grouping lets phases stay small "without requiring an excessive number of tree traversals". The ordering constraints in the API go the other way: `runsAfter` / `runsAfterGroupsOf` say what must have run *before*, so running a miniphase in its own group always satisfies them. `transformFollowing` is how a miniphase makes the later miniphases in its group see trees it synthesises; when unfused, those later miniphases see the trees on their own pass anyway. So the intended rule is that the output must not depend on fusion. Nothing checks it: `usePhases(..., fuse = true)` is the only mode `Run` uses, and there is no unfused test configuration.

Compiling the replay corpus with every miniphase in its own group (`../plugin`, `-P:replay-record:keepFused=none`) and diffing class files against a normal compile found two places that rely on fusion order. Both are in the `{..., mixin, lazyVals, memoize, nonLocalReturns, capturedVars}` group:

| | Pair | Effect of fusion | Report |
|---|---|---|---|
| 1 | `mixin` → `memoize` | Fused output is **wrong**: the class-side trait setter writes the overriding val's field. Unfused gives the Scala 2 behaviour. This is the cause of scala/scala3#25108 (`ClassCastException`). | `01-memoize-trait-setter-overridden-val.md` |
| 2 | `lazyVals` → `capturedVars` | Fused output is right, but only by accident; unfused boxes local lazy holders in `ObjectRef`. | `02-capturedvars-boxes-local-lazy-holder.md` |

`-Ycheck` failing after `flatten` when unfused is not a bug: `RestoreScopes`, later in the same group, repairs the scopes `Flatten` leaves inconsistent, and the checker normally only runs at group boundaries. The output from that group is the same fused and unfused.

Reproducers: `MixinMemoize.scala`, `i25108.scala`, `LocalLazy.scala` in this directory. Running them:

```bash
local/replay/plugin/build.sh
```

```bash
source local/replay/plugin/env.sh && dotc -Xplugin:local/replay/plugin/classes -P:replay-record:norecord -P:replay-record:keepFused=none -d /tmp/unfused local/replay/bugs/MixinMemoize.scala
```

Scope: five small corpus files plus these reductions. A broader sweep (e.g. `tests/run` compiled unfused and run) would likely find more.
