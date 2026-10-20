// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.nsc.agent;

import static net.bytebuddy.matcher.ElementMatchers.*;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import replay.nsc.agent.JoinPoints.JoinPoint;
import replay.nsc.api.Hooks;

/**
 * Delivers the recorder's hooks to an unmodified nsc (scalac 2.13). A copy of ../../../agent's
 * {@code ReplayAgent} reduced to advice sites: no Name hashes (nsc's names are interned), no gate (nsc runs
 * any {@code -Xplugin}), no substitution (the phase loop is hooked at its profiler calls).
 *
 * {@code -javaagent:replay-nsc-agent.jar[=opts]}. Options, comma-separated: {@code nojvm} (skip the backend
 * hooks), {@code nobuffered} (skip buffered typer errors), {@code lenient} (a MISSING site is reported in the
 * table but does not fail verify), {@code verbose} (print the table on verify).
 */
public final class NscAgent {
  private NscAgent() {}

  enum Status { NOT_LOADED, FOUND, MISSING, ERROR }

  private static final Map<String, Status> status = new ConcurrentHashMap<>();
  private static final Map<String, String> detail = new ConcurrentHashMap<>();
  private static List<JoinPoint> active = List.of();
  private static boolean verbose;
  private static boolean lenient;

  public static void premain(String args, Instrumentation inst) { install(args, inst); }

  private static synchronized void install(String args, Instrumentation inst) {
    if (Hooks.verifier != null) return;
    Set<String> opts = new HashSet<>(Arrays.asList((args == null ? "" : args).replace('+', ',').split(",")));
    verbose = opts.contains("verbose");
    lenient = opts.contains("lenient");
    List<JoinPoint> jps = new ArrayList<>();
    for (JoinPoint jp : JoinPoints.ALL) {
      boolean on = switch (jp.group()) {
        case "core" -> true;
        case "jvm" -> !opts.contains("nojvm");
        case "buffered" -> !opts.contains("nobuffered");
        default -> false;
      };
      if (on) { jps.add(jp); status.put(jp.key(), Status.NOT_LOADED); }
    }
    active = List.copyOf(jps);
    Hooks.verifier = NscAgent::verify;

    Set<String> classes = new LinkedHashSet<>();
    for (JoinPoint jp : active) for (JoinPoints.Alt a : jp.candidates()) classes.add(a.owner());
    // ignore(none()): Byte Buddy skips synthetic methods by default, and traceIfChanged$1 is one.
    new AgentBuilder.Default(new net.bytebuddy.ByteBuddy().ignore(none()))
      .disableClassFormatChanges()
      .with(new Listener())
      .ignore(not(nameStartsWith("scala.tools.nsc.").or(nameStartsWith("scala.reflect.internal."))))
      .type(namedOneOf(classes.toArray(String[]::new)))
      .transform(NscAgent::transform)
      .installOn(inst);
  }

  private static List<JoinPoint> sitesOf(TypeDescription td) {
    List<JoinPoint> res = new ArrayList<>();
    for (JoinPoint jp : active)
      if (jp.candidates().stream().anyMatch(a -> a.owner().equals(td.getName()))) res.add(jp);
    return res;
  }

  private static JoinPoints.Alt find(TypeDescription td, JoinPoint jp) {
    String why = null;
    boolean elsewhere = false;
    for (JoinPoints.Alt a : jp.candidates()) {
      if (!a.owner().equals(td.getName())) { elsewhere = true; continue; }
      MethodList<MethodDescription.InDefinedShape> ms =
        td.getDeclaredMethods().filter(named(a.method()).and(hasDescriptor(a.descriptor())));
      if (ms.size() != 1) { if (why == null) why = "no method " + a.method() + a.descriptor() + " in " + td.getName(); continue; }
      if (a.requiredField() != null && !hasField(td, a.requiredField())) {
        if (why == null) why = "no field " + a.requiredField() + " in " + td.getName();
        continue;
      }
      if (!a.method().equals(jp.method()) || !a.descriptor().equals(jp.descriptor()) || !a.owner().equals(jp.owner()))
        detail.put(jp.key(), "as " + a.show());
      return a;
    }
    if (!elsewhere) missing(jp, why);
    else if (status.get(jp.key()) != Status.FOUND) detail.putIfAbsent(jp.key(), why);
    return null;
  }

  /** A field of {@code td} or a superclass (FieldValue looks up the hierarchy). */
  private static boolean hasField(TypeDescription td, String name) {
    for (TypeDescription.Generic t = td.asGenericType(); t != null; t = t.getSuperClass())
      if (!t.asErasure().getDeclaredFields().filter(named(name)).isEmpty()) return true;
    return false;
  }

  private static void missing(JoinPoint jp, String why) {
    status.put(jp.key(), Status.MISSING);
    detail.put(jp.key(), why);
    System.err.println("[replay-nsc-agent] MISSING join point " + jp.key() + ": " + why);
  }

  private static DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription td,
      ClassLoader cl, JavaModule module, java.security.ProtectionDomain pd) {
    for (JoinPoint jp : sitesOf(td)) {
      JoinPoints.Alt site = find(td, jp);
      if (site == null) continue;
      MethodDescription.InDefinedShape md =
        td.getDeclaredMethods().filter(named(site.method()).and(hasDescriptor(site.descriptor()))).getOnly();
      builder = builder.visit(Advice.to(site.advice()).on(is(md)));
      status.put(jp.key(), Status.FOUND);
    }
    return builder;
  }

  private static final class Listener extends AgentBuilder.Listener.Adapter {
    @Override
    public void onError(String typeName, ClassLoader cl, JavaModule module, boolean loaded, Throwable t) {
      for (JoinPoint jp : active) if (jp.owner().equals(typeName)) {
        status.put(jp.key(), Status.ERROR);
        detail.put(jp.key(), String.valueOf(t));
      }
      System.err.println("[replay-nsc-agent] ERROR transforming " + typeName + ": " + t);
      t.printStackTrace();
    }
  }

  /** Force-load every target class in {@code cl}, then fail unless every active site was found. */
  private static String verify(ClassLoader cl) {
    for (JoinPoint jp : active) {
      boolean anyClass = false;
      for (JoinPoints.Alt a : jp.candidates()) {
        try { Class.forName(a.owner(), false, cl); anyClass = true; }
        catch (ClassNotFoundException e) { /* an alternative's class: another version */ }
      }
      if (!anyClass) missing(jp, "class not found");
      else if (status.get(jp.key()) == Status.NOT_LOADED) missing(jp, detail.getOrDefault(jp.key(), "no candidate matched"));
    }
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-8s %-10s %-45s %-26s %s%n", "group", "status", "class", "method", "descriptor"));
    boolean ok = true;
    for (JoinPoint jp : active) {
      Status s = status.get(jp.key());
      if (s != Status.FOUND && !(lenient && s == Status.MISSING)) ok = false;
      sb.append(String.format("%-8s %-10s %-45s %-26s %s%s%n", jp.group(), s, jp.owner(), jp.method(), jp.descriptor(),
        detail.containsKey(jp.key()) ? "  (" + detail.get(jp.key()) + ")" : ""));
    }
    String table = sb.toString();
    if (verbose || !ok) System.err.print("[replay-nsc-agent] join points:\n" + table);
    if (!ok) throw new IllegalStateException("replay nsc agent: join points missing, see table above");
    return table;
  }
}
