package dotty.tools.dotc.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * DO NOT MERGE: experiment. {@code SingleDenotation.goForwardLoop} specialised by the jvm-loop-unroller partial
 * evaluator (github.com/retronym/jvm-loop-unroller, JDK 25+, loaded reflectively from the runtime class path) on the
 * phase plan, behind a call site that C2 treats as a constant.
 *
 * <p>{@code -Ddotty.goForward=off} (default) runs {@code goForward}, {@code plain} runs {@code goForwardLoop}
 * unspecialised, {@code pe} runs it specialised. The site is global, relinked whenever a phase plan is set up
 * ({@code Phases.usePhases}), so only one run may be active at a time. {@code -Dunroller.pe.dump=<dir>} writes the
 * residual class.
 */
public final class GoForwardSite {
    private GoForwardSite() {}

    public static final String MODE = System.getProperty("dotty.goForward", "off");

    private static final MethodType TYPE = MethodType.methodType(Object.class, Object.class, Object.class);
    private static final MutableCallSite SITE = new MutableCallSite(TYPE);
    private static final MethodHandle INVOKER = SITE.dynamicInvoker();

    /** {@code goForwardLoop(d, ctx)} as specialised for the current phase plan; {@code d}'s result */
    public static Object invoke(Object denot, Object ctx) throws Throwable {
        return (Object) INVOKER.invokeExact(denot, ctx);
    }

    public static synchronized void link(Object[] transformers, int[] transformerIndex, int[] nextIds) {
        try {
            Class<?> pe = Class.forName("unroller.pe.PartialEvaluator");
            Object dynamic = pe.getField("DYNAMIC").get(null);
            Method loop = Arrays.stream(Class.forName("dotty.tools.dotc.core.Denotations$SingleDenotation").getMethods())
                    .filter(m -> m.getName().equals("goForwardLoop")).findFirst().orElseThrow();
            Method specialize = pe.getMethod("specialize", MethodHandles.Lookup.class, Method.class, Object[].class);
            MethodHandle mh = (MethodHandle) specialize.invoke(null, MethodHandles.lookup(), loop,
                    new Object[]{dynamic, transformers, transformerIndex, nextIds, dynamic});
            SITE.setTarget(mh.asType(TYPE));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("-Ddotty.goForward=pe needs jvm-loop-unroller (JDK 25+) on the class path", e);
        }
    }
}
