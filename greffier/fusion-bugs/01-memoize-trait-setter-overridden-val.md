# Memoize: class-side trait setter writes the overriding val's field (only because of fusion order)

Draft comment for scala/scala3#25108 ("Overriding concrete trait val can cause `ClassCastException`"), which is the same bug. Written with LLM assistance (Claude), see `LLM_POLICY.md`.

## Compiler version

`3.10.2-RC1-bin-SNAPSHOT-nonbootstrapped-git-ae374e9` (main as of 2026-10-18); 3.3.6 behaves the same.

## Minimized code

```scala
trait Logger:
  val prefix: String = "[log]"
  val seenInInit: String = prefix

class Service extends Logger:
  override val prefix: String = "[svc]"

@main def Test = println(new Service().seenInInit)
```

## Output

```
[log]
```

## Expectation

`null`, as Scala 2.13.16 prints, and as Scala 3 prints if `trait Logger` is made an `abstract class`. `Service.prefix` is not initialised yet when `Logger`'s initialiser reads it; the trait's `"[log]"` should not land in `Service`'s field.

The #25108 reproducer is the same mechanism with a narrowed type, so the stray write becomes a `ClassCastException`:

```scala
trait Trait:
  val optionValue: Option[Unit] = None
object Obj extends Trait:
  override val optionValue: Some[Unit] = Some(())
@main def Test = println(Obj.optionValue)
```

## Mechanism

`Mixin` gives `Service` an implementation of the trait setter `Logger$_setter_$prefix_$eq` with body `()`, and puts it in front of the existing body (`superCalls ::: mixInits ::: impl.body`, where `mixInits` includes `setters(mixin)`, `Mixin.scala:344-395`). It runs `transformFollowing` on it, so the miniphases after `mixin` in the same MegaPhase, `memoize` among them, see the new setter straight away.

`Memoize.transformDefDef` decides what a setter does by looking up a field by name: `sym.field` = `owner.info.decl(fieldName)` (`SymUtils.scala:291`). If there is none, the setter keeps `()` (`Memoize.scala:157-161`). Otherwise it assigns the field, and if the field isn't `Mutable` it makes it mutable and adds a `releaseFence` to the constructor.

The trait setter's field name is `prefix`, so it resolves to whatever field `Memoize` already made for the class's own getter `prefix`, which here is the *overriding* val. Whether that field exists yet depends on traversal order:

- Fused (what ships): MegaPhase transforms children before their parent, so `Service`'s own getter `prefix` in `impl.body` has already been memoized when `Mixin.transformTemplate` runs and calls `transformFollowing` on the setter. `sym.field` finds `Service`'s field, and the setter assigns it.
- Each miniphase in its own group: `Memoize` walks the finished template body in order. The mixed-in setter comes before `impl.body`, so `sym.field` is still `NoSymbol` and the setter stays `()`. That matches Scala 2: `Service` prints `null`, #25108 prints `Some(())`.

So the fused result comes from an ordering accident, not from anything `Memoize` decided. The `field.getter.is(ParamAccessor, butNot = Mutable)` case right after (`Memoize.scala:162-167`, `tests/run/traitValOverriddenByParamAccessor.scala`) already handles this for a trait val overridden by a class *parameter*. The general case, a trait val overridden by a class `val`, is missing.

`javap -c -p Service.class`, fused vs unfused:

```
<   private java.lang.String prefix;
>   private final java.lang.String prefix;
    // <init>
<       18: invokestatic  scala/runtime/Statics.releaseFence:()V
    // Logger$_setter_$prefix_$eq(String)
<        0: aload_0
<        1: aload_1
<        2: putfield      prefix:Ljava/lang/String;
<        5: return
>        0: return
```

## Suggested fix

In `Memoize`'s setter case, for a trait setter (`sym.name.is(TraitSetterName)`), keep the `()` body when the class's getter for that name is not the mixed-in forwarder `Mixin` made, i.e. the class overrides the val. Decide this from the getter symbol rather than from whether the field exists yet, so the result doesn't depend on traversal order. This generalises the existing `ParamAccessor` case. Tests: the two snippets above as `tests/run` with checkfiles `null` and `Some(())`.

## How this was found

A research plugin that puts every MiniPhase in its own phase group (`local/replay/plugin`, `-P:replay-record:norecord -P:replay-record:keepFused=none`), compiled the corpus fused and unfused, and diffed the class files.
