package dotty.tools.benchlab.audit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * `-javaagent:audit-agent.jar=spec=<file>,out=<report.json>` ({@code {pid}} in it is the JVM's pid): weaves the probes of an audit spec ({@link Spec}) into
 * the classes they name as those load, and writes the report ({@link Audit}) at exit. A probe becomes a call to
 * {@code Audit.hit(id, receiver)} at the method's entry, or before each of its returns.
 *
 * Built on the ASM that is on the benchmark's classpath (JMH's generator brings it) rather than the ClassFile API, so
 * the agent compiles on sbt's JDK as well as the benchmark's.
 */
public final class AuditAgent {
    private AuditAgent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        Map<String, String> kv = new HashMap<>();
        for (String a : (args == null ? "" : args).split(","))
            if (a.contains("=")) kv.put(a.substring(0, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
        if (!kv.containsKey("spec")) throw new IllegalArgumentException("audit agent: spec=<file> missing");
        Spec spec = Spec.read(Path.of(kv.get("spec")));
        // {pid}: one report per JVM, e.g. per JMH fork
        Path out = Path.of(kv.getOrDefault("out", "audit-{pid}.json").replace("{pid}", Long.toString(ProcessHandle.current().pid())));
        Map<String, List<Integer>> byOwner = new HashMap<>();  // internal class name -> probe ids
        for (Spec.Line l : spec.lines()) byOwner.computeIfAbsent(l.owner().replace('.', '/'), k -> new ArrayList<>()).add(Audit.register(l));
        Audit.start(out);
        Set<String> woven = Collections.synchronizedSet(new TreeSet<>());
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain pd, byte[] bytes) {
                List<Integer> ids = byOwner.get(name);
                if (ids == null) return null;
                try {
                    byte[] b = weave(bytes, ids, woven);
                    return b;
                } catch (Throwable t) {
                    System.err.println("[audit] cannot weave " + name + ": " + t);
                    return null;
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Spec.Line l : spec.lines())
                if (woven.stream().noneMatch(w -> w.startsWith(l.name() + " ")))
                    System.err.println("[audit] probe " + l.name() + " was never woven: no method " + l.owner() + "#" + l.method()
                        + (l.descPrefix() == null ? "" : l.descPrefix()) + " loaded");
        }, "audit-check"));
        System.err.println("[audit] " + spec.lines().size() + " probes, report to " + out);
    }

    static byte[] weave(byte[] bytes, List<Integer> ids, Set<String> woven) {
        ClassReader r = new ClassReader(bytes);
        // Stack-neutral code at method entry and before returns: existing frames stay valid, only max stack changes
        ClassWriter w = new ClassWriter(r, ClassWriter.COMPUTE_MAXS);
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                List<int[]> here = new ArrayList<>();  // {probe id, exit?}
                for (int id : ids) {
                    Spec.Line l = Audit.probes.get(id).spec;
                    if (l.method().equals(name) && (l.descPrefix() == null || desc.startsWith(l.descPrefix()))) {
                        here.add(new int[]{id, l.exit() ? 1 : 0});
                        woven.add(l.name() + " " + name + desc);
                    }
                }
                if (here.isEmpty()) return mv;
                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private void call(int id) {
                        super.visitLdcInsn(id);
                        if (isStatic) super.visitInsn(Opcodes.ACONST_NULL);
                        else super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "dotty/tools/benchlab/audit/Audit", "hit", "(ILjava/lang/Object;)V", false);
                    }

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        for (int[] p : here) if (p[1] == 0) call(p[0]);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
                            for (int[] p : here) if (p[1] == 1) call(p[0]);
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return w.toByteArray();
    }
}
