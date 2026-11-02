package dotty.tools.sharing;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;

/** Whether any shared state in this JVM is concurrent, as an almost-final value (scalac's `AlmostFinalValue`): a
 *  `MutableCallSite` whose constant target the JIT folds into the code that reads it, and deoptimises that code when
 *  the target changes. It is false until the first concurrent owner calls `seen()`, so until then a test of it, and
 *  the concurrent arm it guards, drop out of compiled code: in particular a possible call in that arm, which once
 *  inlined costs the batch compiler spills on its common path, and the acquire/release ordering of `Published`.
 *  The value only ever goes from false to true. */
public final class AnyConcurrent {
  private AnyConcurrent() {}

  private static final MethodHandle K_FALSE = MethodHandles.constant(boolean.class, false);
  private static final MethodHandle K_TRUE = MethodHandles.constant(boolean.class, true);
  private static final MutableCallSite SITE = new MutableCallSite(K_FALSE);
  private static final MethodHandle VALUE = SITE.dynamicInvoker();

  public static boolean get() {
    try {
      return (boolean) VALUE.invokeExact();
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
  }

  /** Before the first concurrent state is shared: from then on `get()` is true, in every thread. */
  public static synchronized void seen() {
    if (SITE.getTarget() == K_TRUE) return;
    SITE.setTarget(K_TRUE);
    MutableCallSite.syncAll(new MutableCallSite[] { SITE });
  }
}
