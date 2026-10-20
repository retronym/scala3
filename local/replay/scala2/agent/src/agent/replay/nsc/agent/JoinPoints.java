// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.nsc.agent;

import java.util.List;

/**
 * The pinned join-point table for nsc (scala-compiler 2.13.18). Each site is matched by binary class name,
 * method name and erased descriptor; anything else is reported as MISSING and fails {@code Hooks.verify}
 * (unless the agent runs {@code lenient}). Copied from ../../../agent's table format, for nsc.
 */
final class JoinPoints {
  private JoinPoints() {}

  /** {@code core}: phase boundaries, typer events, copier, reports. {@code jvm}: the backend (off with
   *  {@code nojvm}). {@code buffered}: errors buffered by {@code silent} (off with {@code nobuffered}). */
  record JoinPoint(String group, String owner, String method, String descriptor, Class<?> advice,
                   String requiredField, String site, List<Alt> alternatives) {
    JoinPoint(String group, String owner, String method, String descriptor, Class<?> advice, String requiredField, String site) {
      this(group, owner, method, descriptor, advice, requiredField, site, List.of());
    }
    String key() { return owner + "." + method + descriptor; }

    List<Alt> candidates() {
      List<Alt> all = new java.util.ArrayList<>();
      all.add(new Alt(owner, method, descriptor, advice, requiredField));
      all.addAll(alternatives);
      return all;
    }

    JoinPoint or(Alt alt) {
      List<Alt> alts = new java.util.ArrayList<>(alternatives);
      alts.add(alt);
      return new JoinPoint(group, owner, method, descriptor, advice, requiredField, site, List.copyOf(alts));
    }
  }

  record Alt(String owner, String method, String descriptor, Class<?> advice, String requiredField) {
    String show() { return (owner.substring(owner.lastIndexOf('.') + 1)) + "." + method + descriptor; }
  }

  private static final String TREE = "Lscala/reflect/internal/Trees$Tree;";
  private static final String TYPE = "Lscala/reflect/internal/Types$Type;";
  private static final String SYM = "Lscala/reflect/internal/Symbols$Symbol;";
  private static final String PHASE = "Lscala/reflect/internal/Phase;";
  private static final String SNAP = "Lscala/tools/nsc/profile/ProfileSnap;";
  private static final String LIST = "Lscala/collection/immutable/List;";
  private static final String UNIT = "Lscala/tools/nsc/CompilationUnits$CompilationUnit;";
  private static final String RESULT = "Lscala/tools/nsc/typechecker/Implicits$SearchResult;";
  private static final String TC = "scala.tools.nsc.typechecker.";
  private static final String BJ = "scala.tools.nsc.backend.jvm.";
  private static final String CNODE = "Lscala/tools/asm/tree/ClassNode;";
  private static final String MNODE = "Lscala/tools/asm/tree/MethodNode;";

  static final List<JoinPoint> ALL = List.of(
    new JoinPoint("core", "scala.tools.nsc.profile.NoOpProfiler$", "beforePhase", "(" + PHASE + ")" + SNAP,
      Advices.BeforePhase.class, null, "Global.scala:1558 profiler.beforePhase(phase)"),
    new JoinPoint("core", "scala.tools.nsc.profile.NoOpProfiler$", "afterPhase", "(" + PHASE + SNAP + ")V",
      Advices.AfterPhase.class, null, "Global.scala:1562 profiler.afterPhase(phase, snap)"),
    new JoinPoint("core", "scala.tools.nsc.profile.RealProfiler", "beforePhase", "(" + PHASE + ")" + SNAP,
      Advices.BeforePhase.class, null, "Global.scala:1558 (-Yprofile-enabled)"),
    new JoinPoint("core", "scala.tools.nsc.profile.RealProfiler", "afterPhase", "(" + PHASE + SNAP + ")V",
      Advices.AfterPhase.class, null, "Global.scala:1562 (-Yprofile-enabled)"),
    new JoinPoint("core", "scala.tools.nsc.Global$Run", "compileUnitsInternal", "(" + LIST + PHASE + ")V",
      Advices.CompileUnits.class, null, "Global.scala:1544 the phase loop"),
    new JoinPoint("core", TC + "Typers$Typer", "typed", "(" + TREE + "I" + TYPE + ")" + TREE,
      Advices.Typed.class, null, "Typers.scala:6325 typed"),
    new JoinPoint("core", TC + "Typers$Typer", "adapt", "(" + TREE + "I" + TYPE + TREE + ")" + TREE,
      Advices.Adapt.class, null, "Typers.scala:727 adapt"),
    new JoinPoint("core", TC + "Namers$LockingTypeCompleter", "complete$",
      "(L" + "scala/tools/nsc/typechecker/Namers$LockingTypeCompleter;" + SYM + ")V",
      Advices.Complete.class, null, "Namers.scala LockingTypeCompleter.complete (all namer completers)"),
    new JoinPoint("core", TC + "Implicits$ImplicitSearch", "bestImplicit", "()" + RESULT,
      Advices.BestImplicit.class, null, "Implicits.scala bestImplicit"),
    new JoinPoint("core", TC + "Implicits$ImplicitSearch", "scala$tools$nsc$typechecker$Implicits$ImplicitSearch$$typedImplicit",
      "(Lscala/tools/nsc/typechecker/Implicits$ImplicitInfo;ZZ)" + RESULT,
      Advices.TypedImplicit.class, null, "Implicits.scala typedImplicit (one candidate)"),
    new JoinPoint("core", "scala.reflect.internal.Reporter", "filteredInfo",
      "(Lscala/reflect/internal/util/Position;Ljava/lang/String;Lscala/reflect/internal/Reporter$Severity;" + LIST + ")V",
      Advices.FilteredInfo.class, null, "Reporting.scala:123 filteredInfo (every report)"),
    new JoinPoint("core", "scala.reflect.internal.Trees$Tree", "copyAttrs", "(" + TREE + ")" + TREE,
      Advices.CopyAttrs.class, null, "Trees.scala copyAttrs (end of every strict copy)"),
    new JoinPoint("buffered", TC + "Contexts$BufferingReporter", "issue",
      "(Lscala/tools/nsc/typechecker/ContextErrors$AbsTypeError;Lscala/tools/nsc/typechecker/Contexts$Context;)V",
      Advices.BufferedIssue.class, null, "Contexts.scala BufferingReporter.issue (silent typing)"),

    new JoinPoint("jvm", BJ + "CodeGen", "genClass", "(Lscala/reflect/internal/Trees$ClassDef;" + UNIT + ")" + CNODE,
      Advices.GenClass.class, null, "CodeGen.scala:71 genClass"),
    new JoinPoint("jvm", BJ + "CodeGen", "genMirrorClass", "(" + SYM + UNIT + ")" + CNODE,
      Advices.GenMirrorClass.class, null, "CodeGen.scala:80 genMirrorClass"),
    new JoinPoint("jvm", BJ + "BCodeSkelBuilder$PlainSkelBuilder", "genDefDef", "(Lscala/reflect/internal/Trees$DefDef;)V",
      Advices.GenDefDef.class, null, "BCodeSkelBuilder.scala:600 genDefDef (stashes dd)"),
    new JoinPoint("jvm", BJ + "BCodeSkelBuilder$PlainSkelBuilder", "initJMethod", "(I" + LIST + ")V",
      Advices.InitJMethod.class, "mnode", "BCodeSkelBuilder.scala:584 initJMethod (fires)"),
    new JoinPoint("jvm", BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genStat", "(" + TREE + ")V",
      Advices.GenLoad.class, null, "BCodeBodyBuilder.scala:67 genStat"),
    new JoinPoint("jvm", BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genLoad",
      "(" + TREE + "Lscala/tools/nsc/backend/jvm/BTypes$BType;)V", Advices.GenLoad.class, null, "BCodeBodyBuilder.scala:274 genLoad"),
    new JoinPoint("jvm", BJ + "opt.LocalOpt", "methodOptimizations", "(" + MNODE + "Ljava/lang/String;)Z",
      Advices.MethodOptimizations.class, null, "LocalOpt.scala:224 methodOptimizations (stashes owner)"),
    new JoinPoint("jvm", BJ + "opt.LocalOpt", "traceIfChanged$1", "(Ljava/lang/String;Z" + MNODE + "Lscala/runtime/ObjectRef;)V",
      Advices.TraceIfChanged.class, null, "LocalOpt.scala:261 traceIfChanged (one pass)"),
    new JoinPoint("jvm", BJ + "opt.Inliner", "inlineCallsite",
      "(Lscala/tools/nsc/backend/jvm/opt/CallGraph$Callsite;Lscala/Option;Z)Lscala/collection/immutable/Map;",
      Advices.InlineCallsite.class, null, "Inliner.scala:686 inlineCallsite"),
    new JoinPoint("jvm", BJ + "PostProcessor", "serializeClass", "(" + CNODE + ")[B",
      Advices.SerializeClass.class, null, "PostProcessor.scala serializeClass (fires on return)")
  );
}
