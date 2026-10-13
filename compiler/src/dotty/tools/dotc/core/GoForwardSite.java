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

    // ---- -Ddotty.goForward=mh: transform(k, transformer, denot, ctx) through a tableSwitch whose arm k calls
    // transform on the k-th transformer's class, statically bound; the receiver is an argument, so the handle depends
    // only on the transformers' classes and is reused (cached by class vector) across runs with new instances.

    private static final MethodType DISPATCH = MethodType.methodType(Object.class, int.class, Object.class, Object.class, Object.class);
    private static final MutableCallSite DISPATCH_SITE = new MutableCallSite(DISPATCH);
    private static final MethodHandle DISPATCH_INVOKER = DISPATCH_SITE.dynamicInvoker();
    private static final int CACHE_SLOTS = 4;
    private static final Class<?>[][] cachedShapes = new Class<?>[CACHE_SLOTS][];
    private static final MethodHandle[] cachedDispatchers = new MethodHandle[CACHE_SLOTS];
    private static int nextSlot = 0;
    private static Class<?>[] linkedShape = null;
    public static int dispatchBuilds = 0;

    /** {@code transformer.transform(denot)(using ctx)}, dispatched on {@code k}, the transformer's index in the plan */
    public static Object transform(int k, Object transformer, Object denot, Object ctx) throws Throwable {
        return (Object) DISPATCH_INVOKER.invokeExact(k, transformer, denot, ctx);
    }

    public static synchronized void linkDispatch(Object[] transformers) {
        Class<?>[] shape = new Class<?>[transformers.length];
        for (int i = 0; i < shape.length; i++) shape[i] = transformers[i].getClass();
        if (Arrays.equals(shape, linkedShape)) return; // the usual case: same plan, new instances; keep the compiled site
        MethodHandle dispatcher = null;
        for (int i = 0; i < CACHE_SLOTS; i++)
            if (Arrays.equals(shape, cachedShapes[i])) dispatcher = cachedDispatchers[i];
        if (dispatcher == null) {
            dispatcher = buildDispatcher(shape);
            cachedShapes[nextSlot] = shape;
            cachedDispatchers[nextSlot] = dispatcher;
            nextSlot = (nextSlot + 1) % CACHE_SLOTS;
            dispatchBuilds++;
        }
        DISPATCH_SITE.setTarget(dispatcher);
        linkedShape = shape;
    }

    private static MethodHandle buildDispatcher(Class<?>[] shape) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> denotation = Class.forName("dotty.tools.dotc.core.Denotations$SingleDenotation");
            Class<?> context = Class.forName("dotty.tools.dotc.core.Contexts$Context");
            Class<?> transformerClass = Class.forName("dotty.tools.dotc.core.DenotTransformers$DenotTransformer");
            MethodType erased = MethodType.methodType(denotation, denotation, context);
            MethodType generic = MethodType.methodType(Object.class, Object.class, Object.class, Object.class);
            MethodHandle fallback = MethodHandles.dropArguments(
                    lookup.findVirtual(transformerClass, "transform", erased).asType(generic), 0, int.class);
            MethodHandle[] cases = new MethodHandle[shape.length];
            for (int k = 0; k < shape.length; k++) {
                try {
                    MethodHandle exact = lookup.findVirtual(shape[k], "transform", erased).asType(generic);
                    cases[k] = MethodHandles.dropArguments(exact, 0, int.class);
                } catch (IllegalAccessException | NoSuchMethodException e) {
                    cases[k] = fallback; // e.g. a non-public class: keep the generic call
                }
            }
            return MethodHandles.tableSwitch(fallback, cases);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static synchronized void link(Object[] transformers, int[] transformerIndex, int[] nextIds) {
        try {
            Class<?> pe = Class.forName("unroller.pe.PartialEvaluator");
            Object dynamic = pe.getField("DYNAMIC").get(null);
            Method loop = Arrays.stream(Class.forName("dotty.tools.dotc.core.Denotations$SingleDenotation").getMethods())
                    .filter(m -> m.getName().equals("goForwardLoop")).findFirst().orElseThrow();
            Method specialize = pe.getMethod("specialize", MethodHandles.Lookup.class, Method.class, Object[].class);
            MethodHandle mh = (MethodHandle) specialize.invoke(null, MethodHandles.lookup(), loop,
                    new Object[]{dynamic, transformers, transformerIndex, nextIds, false, dynamic});
            SITE.setTarget(mh.asType(TYPE));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("-Ddotty.goForward=pe needs jvm-loop-unroller (JDK 25+) on the class path", e);
        }
    }
}
