// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.agent;

import static net.bytebuddy.matcher.ElementMatchers.*;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.JavaModule;

import replay.agent.JoinPoints.JoinPoint;
import replay.agent.JoinPoints.Kind;
import replay.agent.api.Hooks;

/**
 * Delivers the TyperObserver fork's hooks to a stock compiler.
 *
 * {@code -javaagent:replay-agent.jar[=opts]} (premain) or self-attach (agentmain, see the plugin's
 * {@code Install}). Options, comma-separated: {@code fixTypeApply} (the fork's TypeApply copier fix),
 * {@code rejected} (implicit scope, ranking and rejected-candidate hooks, not in the fork),
 * {@code tentative} (tryEither and TyperState-buffered reports, not in the fork), {@code nonames} (skip the Name
 * hashes), {@code nojvm} (skip the JVM backend hooks), {@code verbose} (print the join-point table on verify).
 *
 * The Name hashes add a method, so they need the class before it is loaded: premain only.
 */
public final class ReplayAgent {
  private ReplayAgent() {}

  enum Status { NOT_LOADED, FOUND, MISSING, ERROR, LOST }

  private static final Map<String, Status> status = new ConcurrentHashMap<>();
  private static final Map<String, String> detail = new ConcurrentHashMap<>();
  private static List<JoinPoint> active = List.of();
  private static boolean verbose;
  private static boolean dynamic;
  private static Instrumentation instrumentation;

  public static void premain(String args, Instrumentation inst) { install(args, inst, false); }
  public static void agentmain(String args, Instrumentation inst) { install(args, inst, true); }

  private static synchronized void install(String args, Instrumentation inst, boolean isDynamic) {
    if (Hooks.verifier != null) return;
    if (args != null) args = args.replace('+', ','); // `+` separates options inside a -P: value
    Set<String> opts = new HashSet<>(Arrays.asList((args == null ? "" : args).split(",")));
    verbose = opts.contains("verbose");
    dynamic = isDynamic;
    instrumentation = inst;
    List<JoinPoint> jps = new ArrayList<>();
    for (JoinPoint jp : JoinPoints.ALL) {
      boolean on = switch (jp.group()) {
        case "fork" -> true;
        case "jvm" -> !opts.contains("nojvm");
        case "fix" -> opts.contains("fixTypeApply");
        case "extra" -> opts.contains("rejected");
        case "tentative" -> opts.contains("tentative");
        case "names" -> !opts.contains("nonames");
        default -> false;
      };
      if (on) { jps.add(jp); status.put(jp.key(), Status.NOT_LOADED); }
    }
    active = List.copyOf(jps);
    Hooks.verifier = ReplayAgent::verify;

    Set<String> nameClasses = new LinkedHashSet<>();
    Set<String> adviceClasses = new LinkedHashSet<>();
    for (JoinPoint jp : active) (isNames(jp) ? nameClasses : adviceClasses).add(jp.owner());

    AgentBuilder.Listener listener = new Listener();
    if (!nameClasses.isEmpty()) {
      if (dynamic) {
        // Adding hashCode to a loaded class is a schema change; retransformation cannot do it.
        for (JoinPoint jp : active) if (isNames(jp)) {
          status.put(jp.key(), Status.LOST);
          detail.put(jp.key(), "already loaded; retransform cannot add a method");
        }
      } else {
        new AgentBuilder.Default()
          .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
          .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE) // no Nexus <clinit>: nothing to initialize
          .with(listener)
          .ignore(not(nameStartsWith("dotty.tools.dotc.core.Name")))
          .type(namedOneOf(nameClasses.toArray(String[]::new)))
          .transform(ReplayAgent::transformNames)
          .installOn(inst);
      }
    }
    AgentBuilder ab = new AgentBuilder.Default()
      .disableClassFormatChanges()
      .with(listener)
      .ignore(not(nameStartsWith("dotty.tools.dotc.").or(nameStartsWith("dotty.tools.backend.jvm."))));
    if (dynamic) ab = ab.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    ab.type(namedOneOf(adviceClasses.toArray(String[]::new)))
      .transform(ReplayAgent::transformAdvice)
      .installOn(inst);
  }

  private static boolean isNames(JoinPoint jp) {
    return jp.kind() == Kind.DEFINE_HASHCODE || jp.kind() == Kind.REPLACE_HASHCODE;
  }

  private static List<JoinPoint> sitesOf(TypeDescription td) {
    List<JoinPoint> res = new ArrayList<>();
    for (JoinPoint jp : active) if (jp.owner().equals(td.getName())) res.add(jp);
    return res;
  }

  private static MethodDescription.InDefinedShape find(TypeDescription td, JoinPoint jp) {
    MethodList<MethodDescription.InDefinedShape> ms =
      td.getDeclaredMethods().filter(named(jp.method()).and(hasDescriptor(jp.descriptor())));
    if (ms.size() != 1) {
      missing(jp, "no method " + jp.method() + jp.descriptor() + " in " + td.getName());
      return null;
    }
    if (jp.requiredField() != null && td.getDeclaredFields().filter(named(jp.requiredField())).isEmpty()) {
      missing(jp, "no field " + jp.requiredField() + " in " + td.getName());
      return null;
    }
    return ms.getOnly();
  }

  private static void missing(JoinPoint jp, String why) {
    status.put(jp.key(), Status.MISSING);
    detail.put(jp.key(), why);
    System.err.println("[replay-agent] MISSING join point " + jp.key() + ": " + why);
  }

  private static DynamicType.Builder<?> transformAdvice(DynamicType.Builder<?> builder, TypeDescription td,
      ClassLoader cl, JavaModule module, java.security.ProtectionDomain pd) {
    for (JoinPoint jp : sitesOf(td)) {
      MethodDescription.InDefinedShape md = find(td, jp);
      if (md == null) continue;
      if (jp.kind() == Kind.ADVICE) {
        builder = builder.visit(Advice.to(jp.advice()).on(is(md)));
      } else if (jp.kind() == Kind.SUBSTITUTE) {
        // RunHook names compiler types, so the compiler's loader must see it; describe it without loading.
        if (cl == null || cl.getResource("replay/agent/RunHook.class") == null) {
          missing(jp, "replay.agent.RunHook is not visible from the compiler's class loader " + cl);
          continue;
        }
        MethodDescription runHook = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(cl))
          .describe("replay.agent.RunHook").resolve().getDeclaredMethods().filter(named("runOn")).getOnly();
        builder = builder.visit(MemberSubstitution.strict()
          .method(named("runOn").and(takesArguments(2)).and(isDeclaredBy(named(JoinPoints.PHASE))))
          .replaceWith(runHook)
          .on(is(md)));
      }
      status.put(jp.key(), Status.FOUND); // SUBSTITUTE is re-checked against the emitted bytes below
    }
    return builder;
  }

  private static DynamicType.Builder<?> transformNames(DynamicType.Builder<?> builder, TypeDescription td,
      ClassLoader cl, JavaModule module, java.security.ProtectionDomain pd) {
    for (JoinPoint jp : sitesOf(td)) {
      if (jp.kind() == Kind.REPLACE_HASHCODE) {
        if (find(td, jp) == null) continue;
        builder = builder.method(named("hashCode").and(takesArguments(0)))
          .intercept(new Implementation.Simple(nameHash("tag()")));
        status.put(jp.key(), Status.FOUND);
        continue;
      }
      if (!td.getDeclaredMethods().filter(named("hashCode").and(takesArguments(0))).isEmpty()) {
        missing(jp, td.getName() + " already declares hashCode (compiler has the fix?)");
        continue;
      }
      if (td.getDeclaredFields().filter(named(jp.requiredField())).isEmpty()) {
        missing(jp, "no field " + jp.requiredField() + " in " + td.getName());
        continue;
      }
      builder = builder.defineMethod("hashCode", int.class, Visibility.PUBLIC)
        .intercept(new Implementation.Simple(nameHash(td.getName())));
      status.put(jp.key(), Status.FOUND);
    }
    return builder;
  }

  /** The fork's content hashes (Names.scala, NameKinds.scala), as bytecode. */
  private static ByteCodeAppender nameHash(String owner) {
    String self = owner.replace('.', '/');
    return (mv, ctx, method) -> {
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      switch (owner) {
        case "dotty.tools.dotc.core.Names$SimpleName" -> {      // value.hashCode
          mv.visitFieldInsn(Opcodes.GETFIELD, self, "value", "Ljava/lang/String;");
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);
        }
        case "dotty.tools.dotc.core.Names$TypeName" -> {        // ~toTermName.hashCode
          mv.visitFieldInsn(Opcodes.GETFIELD, self, "toTermName", "Ldotty/tools/dotc/core/Names$TermName;");
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
          mv.visitInsn(Opcodes.ICONST_M1);
          mv.visitInsn(Opcodes.IXOR);
        }
        case "dotty.tools.dotc.core.Names$DerivedName" -> {     // underlying.hashCode * 31 + info.hashCode
          mv.visitFieldInsn(Opcodes.GETFIELD, self, "underlying", "Ldotty/tools/dotc/core/Names$TermName;");
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
          mv.visitIntInsn(Opcodes.BIPUSH, 31);
          mv.visitInsn(Opcodes.IMUL);
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(Opcodes.GETFIELD, self, "info", "Ldotty/tools/dotc/core/NameKinds$NameInfo;");
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
          mv.visitInsn(Opcodes.IADD);
        }
        case "dotty.tools.dotc.core.NameKinds$NameKind" ->      // tag
          mv.visitFieldInsn(Opcodes.GETFIELD, self, "tag", "I");
        case "dotty.tools.dotc.core.NameKinds$NameKind$Info" -> { // kind.hashCode
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "kind", "()Ldotty/tools/dotc/core/NameKinds$NameKind;", false);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
        }
        case "tag()" ->                                          // NameKind.hashCode, inherited in the fork
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dotty/tools/dotc/core/NameKinds$NameKind", "tag", "()I", false);
        default -> throw new IllegalStateException(owner);
      }
      mv.visitInsn(Opcodes.IRETURN);
      return new ByteCodeAppender.Size(3, method.getStackSize());
    };
  }

  private static final class Listener extends AgentBuilder.Listener.Adapter {
    @Override
    public void onTransformation(TypeDescription td, ClassLoader cl, JavaModule module, boolean loaded, DynamicType dt) {
      if (td.getName().equals(JoinPoints.RUN)) checkRunSubstitution(dt.getBytes());
    }

    @Override
    public void onError(String typeName, ClassLoader cl, JavaModule module, boolean loaded, Throwable t) {
      for (JoinPoint jp : active) if (jp.owner().equals(typeName)) {
        status.put(jp.key(), Status.ERROR);
        detail.put(jp.key(), String.valueOf(t));
      }
      System.err.println("[replay-agent] ERROR transforming " + typeName + ": " + t);
      t.printStackTrace();
    }
  }

  /** MemberSubstitution does not say whether it replaced anything: count in the emitted method. */
  private static void checkRunSubstitution(byte[] bytes) {
    for (JoinPoint jp : active) if (jp.kind() == Kind.SUBSTITUTE) {
      int[] hooked = {0}, left = {0};
      new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
          if (!(name.equals(jp.method()) && desc.equals(jp.descriptor()))) return null;
          return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
              if (owner.equals("replay/agent/RunHook") && n.equals("runOn")) hooked[0]++;
              if (owner.equals("dotty/tools/dotc/core/Phases$Phase") && n.equals("runOn")) left[0]++;
            }
          };
        }
      }, 0);
      if (hooked[0] != 1 || left[0] != 0) missing(jp, "Phase.runOn calls replaced: " + hooked[0] + ", left: " + left[0]);
    }
  }

  /** Force-load every target class in {@code cl}, then fail unless every active site was found. */
  private static String verify(ClassLoader cl) {
    for (JoinPoint jp : active) {
      try {
        Class<?> c = Class.forName(jp.owner(), false, cl);
        if (dynamic && status.get(jp.key()) == Status.NOT_LOADED) {
          // loaded before the attach but not picked up by installOn's retransformation pass
          System.err.println("[replay-agent] retransforming " + jp.owner() + " explicitly");
          instrumentation.retransformClasses(c);
        }
      }
      catch (ClassNotFoundException e) { missing(jp, "class not found"); }
      catch (Exception e) { missing(jp, "retransform failed: " + e); }
    }
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-8s %-10s %-45s %-26s %s%n", "group", "status", "class", "method", "descriptor"));
    boolean ok = true;
    for (JoinPoint jp : active) {
      Status s = status.get(jp.key());
      if (s != Status.FOUND && s != Status.LOST) ok = false;
      sb.append(String.format("%-8s %-10s %-45s %-26s %s%s%n", jp.group(), s, jp.owner(), jp.method(), jp.descriptor(),
        detail.containsKey(jp.key()) ? "  (" + detail.get(jp.key()) + ")" : ""));
    }
    String table = sb.toString();
    if (verbose || !ok) System.err.print("[replay-agent] join points (" + (dynamic ? "self-attach" : "javaagent") + "):\n" + table);
    if (!ok) throw new IllegalStateException("replay agent: join points missing, see table above");
    return table;
  }
}
