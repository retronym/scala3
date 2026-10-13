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

    /** goForward reads lastPhaseId from ContextBase.denotTransformerLastPhaseId instead of calling it (-Ddotty.goForward.cacheLastPhaseId) */
    public static final boolean CACHE_LAST_PHASE_ID = Boolean.getBoolean("dotty.goForward.cacheLastPhaseId");
    /** goForward skips the call for IdentityDenotTransformers, extending validity instead (-Ddotty.goForward.skipIdentity) */
    public static final boolean SKIP_IDENTITY = Boolean.getBoolean("dotty.goForward.skipIdentity");

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
            dispatcher = MODE.equals("spun") ? spinDispatcher(shape) : buildDispatcher(shape);
            cachedShapes[nextSlot] = shape;
            cachedDispatchers[nextSlot] = dispatcher;
            nextSlot = (nextSlot + 1) % CACHE_SLOTS;
            dispatchBuilds++;
        }
        DISPATCH_SITE.setTarget(dispatcher);
        linkedShape = shape;
    }

    /**
     * {@code -Ddotty.goForward=spun}: a hidden class whose static {@code transform(k, t, d, c)} is a {@code tableswitch}
     * on {@code k}; arm k is {@code checkcast Ck; invokevirtual Ck.transform}, the default arm (and arms for classes
     * we cannot name) the generic {@code invokeinterface}. About 12 bytes per arm, so C2 compiles it as a unit with
     * one monomorphic, statically bound call per arm, unlike MethodHandles.tableSwitch's LambdaForm.
     */
    private static MethodHandle spinDispatcher(Class<?>[] shape) {
        try {
            String sd = "dotty/tools/dotc/core/Denotations$SingleDenotation";
            String cx = "dotty/tools/dotc/core/Contexts$Context";
            String dt = "dotty/tools/dotc/core/DenotTransformers$DenotTransformer";
            String erased = "(L" + sd + ";L" + cx + ";)L" + sd + ";";
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
            // version 50: no stack map frames needed
            cw.visit(org.objectweb.asm.Opcodes.V1_6, org.objectweb.asm.Opcodes.ACC_FINAL | org.objectweb.asm.Opcodes.ACC_SUPER,
                    "dotty/tools/dotc/core/GoForwardDispatch", null, "java/lang/Object", null);
            org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_STATIC, "transform", "(IL" + dt + ";L" + sd + ";L" + cx + ";)L" + sd + ";", null, null);
            mv.visitCode();
            org.objectweb.asm.Label generic = new org.objectweb.asm.Label();
            org.objectweb.asm.Label[] arms = new org.objectweb.asm.Label[shape.length];
            for (int k = 0; k < arms.length; k++) arms[k] = new org.objectweb.asm.Label();
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 0);
            mv.visitTableSwitchInsn(0, shape.length - 1, generic, arms);
            for (int k = 0; k < shape.length; k++) {
                mv.visitLabel(arms[k]);
                Class<?> c = shape[k];
                if (!java.lang.reflect.Modifier.isPublic(c.getModifiers()) || c.isHidden() || c.getName().indexOf('/') >= 0) {
                    mv.visitJumpInsn(org.objectweb.asm.Opcodes.GOTO, generic);
                    continue;
                }
                String internal = c.getName().replace('.', '/');
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
                mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, internal);
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 3);
                mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, internal, "transform", erased, false);
                mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            }
            mv.visitLabel(generic);
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 3);
            mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, dt, "transform", erased, true);
            mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            String dump = System.getProperty("dotty.goForward.dump");
            if (dump != null) java.nio.file.Files.write(java.nio.file.Path.of(dump, "GoForwardDispatch.class"), bytes);
            MethodHandles.Lookup hidden = MethodHandles.lookup().defineHiddenClass(bytes, true);
            Class<?> sdC = Class.forName("dotty.tools.dotc.core.Denotations$SingleDenotation");
            Class<?> cxC = Class.forName("dotty.tools.dotc.core.Contexts$Context");
            Class<?> dtC = Class.forName("dotty.tools.dotc.core.DenotTransformers$DenotTransformer");
            return hidden.findStatic(hidden.lookupClass(), "transform", MethodType.methodType(sdC, int.class, dtC, sdC, cxC))
                    .asType(DISPATCH);
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
