# Local lazy val holder is `Mutable`; CapturedVars boxes it in an `ObjectRef` unless fused with LazyVals

Draft issue. Written with LLM assistance (Claude), see `LLM_POLICY.md`. Low severity: codegen differs depending on phase fusion; no wrong behaviour seen.

## Compiler version

`3.10.2-RC1-bin-SNAPSHOT-nonbootstrapped-git-ae374e9` (main as of 2026-10-18).

## Minimized code

```scala
object Test:
  def f(n: Int): Int =
    lazy val x = n + 1
    x
```

The same happens with a local `given Show[Int] with ...`, which is a local lazy object.

## Output

Shipping (fused) pipeline:

```
private final int x$lzyINIT1$1(scala.runtime.LazyInt, int);
private final int x$1(scala.runtime.LazyInt, int);
```

With each MiniPhase in its own group:

```
private final int x$lzyINIT1$1(scala.runtime.ObjectRef, int);
private final int x$1(scala.runtime.ObjectRef, int);
```

Every access then does `getfield ObjectRef.elem; checkcast LazyInt`, and `f` allocates an extra `ObjectRef`.

## Expectation

The same code either way: fusing miniphases is meant to save traversals, not change what they produce. The holder is never reassigned, so it shouldn't be boxed.

## Mechanism

`LazyVals.transformLocalDef` creates the holder (`x$lzy1: LazyInt`) with `containerFlags = Synthetic | Mutable | Lazy` (`LazyVals.scala:46,170`), even though it's a `val` initialised once to `new LazyInt`. The lifted `x$lzyINIT1`/`x$1` methods refer to it, so after `lambdaLift` it's a captured local.

`CapturedVars.prepareForUnit` collects captured local mutable vars by walking the unit *as it is before the group runs* (`CapturedVars.scala:50-52`, `sym.isMutableVar && sym.owner.isTerm`).

- Fused (`{..., lazyVals, memoize, nonLocalReturns, capturedVars}`): the holder doesn't exist when `prepareForUnit` runs, so it is never collected.
- Unfused: `capturedVars`' input already has the holder; it's `Mutable`, local and captured, so it's boxed.

The fused result is the right one, but only by accident.

## Suggested fix

Leave `Mutable` off the local holder's flags (it's only `Mutable` because `containerFlags` is shared with the field/flag cases), or have `CapturedVars.collect` skip `Lazy` holders. Either makes the result independent of fusion. Check: `-Ycheck` and the local-lazy tests in `tests/run`.

## How this was found

Same as `01-memoize-trait-setter-overridden-val.md`: compiling with every miniphase unfused (`local/replay/plugin`) and diffing class files against the normal compile.
