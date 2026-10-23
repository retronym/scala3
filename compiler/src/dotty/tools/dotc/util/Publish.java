package dotty.tools.dotc.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Release/acquire access to the period fields that publish a cached
 *  denotation (`Symbol.checkedPeriod`, `NamedType.checkedPeriod`): the writer
 *  stores the denotation, then the period with release semantics; a reader
 *  that loads the period with acquire semantics and finds it current then
 *  sees the denotation that was stored before it. Plain stores and loads let
 *  a reader on another thread (interactive mode, several readers) see the
 *  new period and the old denotation. On x86 an acquire load is a plain
 *  load; on Apple silicon it is `ldar`, measured with perflab before it was
 *  kept (docs/_docs/internals/interactive-multi-reader.md, step 3).
 *
 *  A Java class, so that the handles are `static final` constants the JIT
 *  folds: a `VarHandle` held in a Scala object's field is not a constant and
 *  its calls would not be intrinsified.
 */
public final class Publish {
  private Publish() {}

  private static VarHandle intField(String className, String field) {
    try {
      Class<?> c = Class.forName(className);
      return MethodHandles.privateLookupIn(c, MethodHandles.lookup()).findVarHandle(c, field, int.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final VarHandle SYMBOL_CHECKED_PERIOD =
    intField("dotty.tools.dotc.core.Symbols$Symbol", "checkedPeriod");
  private static final VarHandle NAMED_TYPE_CHECKED_PERIOD =
    intField("dotty.tools.dotc.core.Types$NamedType", "checkedPeriod");

  public static int symbolCheckedPeriod(Object symbol) {
    return (int) SYMBOL_CHECKED_PERIOD.getAcquire(symbol);
  }

  public static void setSymbolCheckedPeriod(Object symbol, int period) {
    SYMBOL_CHECKED_PERIOD.setRelease(symbol, period);
  }

  public static int namedTypeCheckedPeriod(Object tp) {
    return (int) NAMED_TYPE_CHECKED_PERIOD.getAcquire(tp);
  }

  public static void setNamedTypeCheckedPeriod(Object tp, int period) {
    NAMED_TYPE_CHECKED_PERIOD.setRelease(tp, period);
  }
}
