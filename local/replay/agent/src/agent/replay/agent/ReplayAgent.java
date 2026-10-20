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
 * hashes), {@code nojvm} (skip the JVM backend hooks), {@code research} (lift the research-plugin gate of a
 * released compiler, see {@link replay.agent.api.Gate}), {@code lenient} (a MISSING site is reported in the table
 * but does not fail verify: for compilers the table was not pinned against), {@code verbose} (print the
 * join-point table on verify).
 *
 * The Name hashes add a method, so they need the class before it is loaded: premain only.
 */
public final class ReplayAgent {
  private ReplayAgent() {}

  enum Status { NOT_LOADED, FOUND, MISSING, ERROR, LOST }

  private static final Map<String, Status> status = new ConcurrentHashMap<>();
  private static final Map<String, String> detail = new ConcurrentHashMap<>();
  /** The alternative a site matched, when it is not the pinned one. */
  private static final Map<String, JoinPoints.Alt> matched = new ConcurrentHashMap<>();
  private static List<JoinPoint> active = List.of();
  private static boolean verbose;
  private static boolean lenient;
  private static boolean dynamic;
  private static Instrumentation instrumentation;

  public static void premain(String args, Instrumentation inst) { install(args, inst, false); }
  public static void agentmain(String args, Instrumentation inst) { install(args, inst, true); }

  private static synchronized void install(String args, Instrumentation inst, boolean isDynamic) {
    if (Hooks.verifier != null) return;
    if (args != null) args = args.replace('+', ','); // `+` separates options inside a -P: value
    Set<String> opts = new HashSet<>(Arrays.asList((args == null ? "" : args).split(",")));
    verbose = opts.contains("verbose");
    lenient = opts.contains("lenient");
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
        case "gate" -> opts.contains("research");
        default -> false;
      };
      if (on) { jps.add(jp); status.put(jp.key(), Status.NOT_LOADED); }
    }
    active = List.copyOf(jps);
    Hooks.verifier = ReplayAgent::verify;

    Set<String> nameClasses = new LinkedHashSet<>();
    Set<String> adviceClasses = new LinkedHashSet<>();
    for (JoinPoint jp : active) for (JoinPoints.Alt a : jp.candidates()) (isNames(jp) ? nameClasses : adviceClasses).add(a.owner());

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
    // ignore(none()): Byte Buddy skips synthetic methods by default, and before 3.8 Run's per-phase loop body
    // is a lambda (runPhases$1$$anonfun$1, ACC_SYNTHETIC). Only the pinned sites are visited either way.
    AgentBuilder ab = new AgentBuilder.Default(new net.bytebuddy.ByteBuddy().ignore(none()))
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
    for (JoinPoint jp : active)
      if (jp.candidates().stream().anyMatch(a -> a.owner().equals(td.getName()))) res.add(jp);
    return res;
  }

  /** The pinned site or the first alternative that matches in {@code td}. If none does and no candidate is in
   *  another class, the site is MISSING now; otherwise {@link #verify} decides once every class has loaded. */
  private static JoinPoints.Alt find(TypeDescription td, JoinPoint jp) { return find(td, jp, null); }

  private static JoinPoints.Alt find(TypeDescription td, JoinPoint jp, ClassLoader cl) {
    String why = null;
    boolean elsewhere = false;
    for (JoinPoints.Alt a : jp.candidates()) {
      if (!a.owner().equals(td.getName())) { elsewhere = true; continue; }
      MethodList<MethodDescription.InDefinedShape> ms =
        td.getDeclaredMethods().filter(named(a.method()).and(hasDescriptor(a.descriptor())));
      if (ms.size() != 1) { if (why == null) why = "no method " + a.method() + a.descriptor() + " in " + td.getName(); continue; }
      if (a.requiredField() != null && jp.kind() == Kind.REPLACE_HASHCODE) {
        if (!loadsString(td, cl, a.method(), a.descriptor(), a.requiredField())) {
          if (why == null) why = td.getName() + "." + a.method() + " does not load \"" + a.requiredField() + "\"";
          continue;
        }
      } else if (a.requiredField() != null && td.getDeclaredFields().filter(named(a.requiredField())).isEmpty()) {
        if (why == null) why = "no field " + a.requiredField() + " in " + td.getName();
        continue;
      }
      if (!a.owner().equals(jp.owner()) || !a.method().equals(jp.method()) || !a.descriptor().equals(jp.descriptor())
          || a.advice() != jp.advice()) {
        matched.put(jp.key(), a);
        detail.put(jp.key(), "as " + a.show());
      } else detail.remove(jp.key());
      return a;
    }
    if (!elsewhere) missing(jp, why);
    else if (status.get(jp.key()) != Status.FOUND) detail.putIfAbsent(jp.key(), why);
    return null;
  }

  /** Whether {@code td}'s method loads the string constant {@code s}: tells apart enum cases, which are anonymous
   *  classes numbered differently in different compiler versions. */
  private static boolean loadsString(TypeDescription td, ClassLoader cl, String name, String desc, String s) {
    byte[] bytes;
    try { bytes = ClassFileLocator.ForClassLoader.of(cl).locate(td.getName()).resolve(); }
    catch (Exception e) { return false; }
    boolean[] found = {false};
    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
        if (!(n.equals(name) && d.equals(desc))) return null;
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public void visitLdcInsn(Object v) { if (s.equals(v)) found[0] = true; }
        };
      }
    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return found[0];
  }

  private static MethodDescription.InDefinedShape method(TypeDescription td, JoinPoints.Alt a) {
    return td.getDeclaredMethods().filter(named(a.method()).and(hasDescriptor(a.descriptor()))).getOnly();
  }

  private static void missing(JoinPoint jp, String why) {
    status.put(jp.key(), Status.MISSING);
    detail.put(jp.key(), why);
    System.err.println("[replay-agent] MISSING join point " + jp.key() + ": " + why);
  }

  private static DynamicType.Builder<?> transformAdvice(DynamicType.Builder<?> builder, TypeDescription td,
      ClassLoader cl, JavaModule module, java.security.ProtectionDomain pd) {
    for (JoinPoint jp : sitesOf(td)) {
      JoinPoints.Alt site = find(td, jp);
      if (site == null) continue;
      MethodDescription.InDefinedShape md = method(td, site);
      if (jp.kind() == Kind.ADVICE && site.advice() == Advices.InlinedTraceIfChanged.class) {
        builder = builder.visit(new net.bytebuddy.asm.AsmVisitorWrapper.ForDeclaredMethods()
          .invokable(named(site.method()).or(named("removalRound$1")), new InlinedTraceSites()));
        inlinedTrace.put(td.getName(), jp);
      } else if (jp.kind() == Kind.ADVICE) {
        builder = builder.visit(Advice.to(site.advice()).on(is(md)));
      } else if (jp.kind() == Kind.GATE) {
        builder = builder.visit(MemberSubstitution.relaxed()
          .method(named("researchPluginEnabled").and(takesArguments(0)).and(isDeclaredBy(named("dotty.tools.dotc.config.Properties$"))))
          .replaceWith(gate("researchPluginEnabled"))
          .method(named("isExperimentalEnabled").and(takesArguments(1)).and(isDeclaredBy(named("dotty.tools.dotc.config.Feature$"))))
          .replaceWith(gate("isExperimentalEnabled"))
          .on(is(md)));
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

  /** Classes with {@link InlinedTraceSites} applied, re-checked against the emitted bytes. */
  private static final Map<String, JoinPoint> inlinedTrace = new ConcurrentHashMap<>();
  private static final Set<String> PASSES = Set.of("beforeMethodOpt", "nullness", "dce", "boxUnbox", "copyProp", "staleStores",
    "redundantCasts", "pushPop", "storeLoadPairs", "staleHandlers", "simplifyJumps", "localVariables", "lineNumbers");

  /** The inlined copies of {@code traceIfChanged(pass)} in a released (-opt built) compiler's {@code LocalOpt}: each
   *  starts by storing the pass name constant, so after {@code ldc pass} insert {@code Hooks.optimizerPass(method, pass)}
   *  (the MethodNode is parameter 1 of both methods that held the calls), the point where the call's arguments were
   *  evaluated. On a build without the inlining the constants are the call's argument: the same point. */
  private static final class InlinedTraceSites implements net.bytebuddy.asm.AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
    @Override
    public MethodVisitor wrap(TypeDescription type, MethodDescription method, MethodVisitor mv,
        Implementation.Context ctx, TypePool pool, int writerFlags, int readerFlags) {
      if (method.getParameters().isEmpty() || !method.getParameters().get(0).getType().asErasure().getName().endsWith(".tree.MethodNode"))
        return mv;
      return new MethodVisitor(Opcodes.ASM9, mv) {
        @Override
        public void visitLdcInsn(Object v) {
          super.visitLdcInsn(v);
          if (v instanceof String p && PASSES.contains(p)) {
            super.visitInsn(Opcodes.DUP);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitInsn(Opcodes.SWAP);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "replay/agent/api/Hooks", "optimizerPass", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
          }
        }
        @Override
        public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(maxStack + 2, maxLocals); }
      };
    }
  }

  /** Every pass name must have got its hook. */
  private static void checkInlinedTrace(String cls, byte[] bytes) {
    JoinPoint jp = inlinedTrace.get(cls);
    if (jp == null) return;
    Set<String> hooked = new HashSet<>();
    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
        return new MethodVisitor(Opcodes.ASM9) {
          Object last;
          @Override public void visitLdcInsn(Object v) { last = v; }
          @Override public void visitInsn(int op) { if (op != Opcodes.DUP && op != Opcodes.SWAP) last = null; }
          @Override
          public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
            if (owner.equals("replay/agent/api/Hooks") && n.equals("optimizerPass") && last instanceof String p) hooked.add(p);
            last = null;
          }
        };
      }
    }, 0);
    if (!hooked.equals(PASSES)) missing(jp, "inlined traceIfChanged sites hooked: " + hooked);
    else detail.put(jp.key(), detail.getOrDefault(jp.key(), "") + ", " + hooked.size() + " inlined sites");
  }

  private static MethodDescription gate(String name) {
    return TypeDescription.ForLoadedType.of(replay.agent.api.Gate.class).getDeclaredMethods().filter(named(name)).getOnly();
  }

  private static DynamicType.Builder<?> transformNames(DynamicType.Builder<?> builder, TypeDescription td,
      ClassLoader cl, JavaModule module, java.security.ProtectionDomain pd) {
    for (JoinPoint jp : sitesOf(td)) {
      if (jp.kind() == Kind.REPLACE_HASHCODE) {
        if (find(td, jp, cl) == null) continue;
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
      if (td.getName().equals(JoinPoints.PLUGINS)) checkGate(dt.getBytes());
      checkInlinedTrace(td.getName(), dt.getBytes());
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

  private static boolean isSite(JoinPoint jp, String name, String desc) {
    JoinPoints.Alt m = matched.get(jp.key());
    return m == null ? name.equals(jp.method()) && desc.equals(jp.descriptor()) : name.equals(m.method()) && desc.equals(m.descriptor());
  }

  /** MemberSubstitution does not say whether it replaced anything: count in the emitted method. */
  private static void checkRunSubstitution(byte[] bytes) {
    for (JoinPoint jp : active) if (jp.kind() == Kind.SUBSTITUTE) {
      int[] hooked = {0}, left = {0};
      new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
          if (!isSite(jp, name, desc)) return null;
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

  /** The gate substitution must replace exactly one flag read and leave none. */
  private static void checkGate(byte[] bytes) {
    for (JoinPoint jp : active) if (jp.kind() == Kind.GATE) {
      int[] hooked = {0}, left = {0};
      new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
          if (!(name.equals(jp.method()) && desc.equals(jp.descriptor()))) return null;
          return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
              if (owner.equals("replay/agent/api/Gate")) hooked[0]++;
              else if (n.equals("researchPluginEnabled") || n.equals("isExperimentalEnabled")) left[0]++;
            }
          };
        }
      }, 0);
      if (hooked[0] != 1 || left[0] != 0) missing(jp, "gate reads replaced: " + hooked[0] + ", left: " + left[0]);
    }
  }

  /** Force-load every target class in {@code cl}, then fail unless every active site was found. */
  private static String verify(ClassLoader cl) {
    for (JoinPoint jp : active) {
      boolean anyClass = false;
      for (JoinPoints.Alt a : jp.candidates()) {
        try {
          Class<?> c = Class.forName(a.owner(), false, cl);
          anyClass = true;
          if (dynamic && status.get(jp.key()) == Status.NOT_LOADED) {
            // loaded before the attach but not picked up by installOn's retransformation pass
            System.err.println("[replay-agent] retransforming " + a.owner() + " explicitly");
            instrumentation.retransformClasses(c);
          }
        }
        catch (ClassNotFoundException e) { /* an alternative's class: another version */ }
        catch (Exception e) { missing(jp, "retransform failed: " + e); }
      }
      if (!anyClass) missing(jp, "class not found");
      else if (status.get(jp.key()) == Status.NOT_LOADED) missing(jp, detail.getOrDefault(jp.key(), "no candidate matched"));
    }
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-8s %-10s %-45s %-26s %s%n", "group", "status", "class", "method", "descriptor"));
    boolean ok = true;
    for (JoinPoint jp : active) {
      Status s = status.get(jp.key());
      if (s != Status.FOUND && s != Status.LOST && !(lenient && s == Status.MISSING)) ok = false;
      sb.append(String.format("%-8s %-10s %-45s %-26s %s%s%n", jp.group(), s, jp.owner(), jp.method(), jp.descriptor(),
        detail.containsKey(jp.key()) ? "  (" + detail.get(jp.key()) + ")" : ""));
    }
    String table = sb.toString();
    if (verbose || !ok) System.err.print("[replay-agent] join points (" + (dynamic ? "self-attach" : "javaagent") + "):\n" + table);
    if (!ok) throw new IllegalStateException("replay agent: join points missing, see table above");
    return table;
  }
}
