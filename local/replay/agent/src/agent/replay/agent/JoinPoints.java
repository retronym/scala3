// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.agent;

import java.util.List;

/**
 * The pinned join-point table for one compiler version (here: scala3 main at ae374e9583, 3.10.0
 * nonbootstrapped). Each site is matched by binary class name, method name and erased
 * descriptor; anything else is reported as MISSING and fails {@code Hooks.verify}.
 */
final class JoinPoints {
  private JoinPoints() {}

  enum Kind { ADVICE, SUBSTITUTE, GATE, DEFINE_HASHCODE, REPLACE_HASHCODE }

  /** {@code fork}: the TyperObserver fork's hooks. {@code fix}: the fork's TypeApply behaviour fix.
   *  {@code names}: the fork's Name/NameKind content hashes. {@code extra} (option {@code rejected}) and
   *  {@code tentative} (option {@code tentative}): hooks the fork does not have. {@code jvm}: the fork's
   *  JVM backend hooks (on unless option {@code nojvm}). {@code gate} (option {@code research}): run research
   *  plugins on a released compiler. */
  record JoinPoint(String group, Kind kind, String owner, String method, String descriptor,
                   Class<?> advice, String requiredField, String forkSite, List<Alt> alternatives) {
    JoinPoint(String group, Kind kind, String owner, String method, String descriptor,
              Class<?> advice, String requiredField, String forkSite) {
      this(group, kind, owner, method, descriptor, advice, requiredField, forkSite, List.of());
    }
    String key() { return owner + "." + method + descriptor; }

    /** This site as pinned, then its alternatives. */
    List<Alt> candidates() {
      List<Alt> all = new java.util.ArrayList<>();
      all.add(new Alt(owner, method, descriptor, advice, requiredField));
      all.addAll(alternatives);
      return all;
    }

    /** The same site in another compiler version (tried in order after the pinned one). */
    JoinPoint or(Alt alt) {
      List<Alt> alts = new java.util.ArrayList<>(alternatives);
      alts.add(alt);
      return new JoinPoint(group, kind, owner, method, descriptor, advice, requiredField, forkSite, List.copyOf(alts));
    }
    JoinPoint or(String name, String desc) { return or(new Alt(owner, name, desc, advice, requiredField)); }
  }

  /** Where a site is in another compiler version: possibly another class, advice (argument indices) or field. */
  record Alt(String owner, String method, String descriptor, Class<?> advice, String requiredField) {
    String show() { return (owner.substring(owner.lastIndexOf('.') + 1)) + "." + method + descriptor; }
  }

  private static final String TREE = "Ldotty/tools/dotc/ast/Trees$Tree;";
  private static final String TYPE = "Ldotty/tools/dotc/core/Types$Type;";
  private static final String CTX = "Ldotty/tools/dotc/core/Contexts$Context;";
  private static final String LOCKED = "Ldotty/tools/dotc/util/SimpleIdentitySet;";
  private static final String DENOT = "Ldotty/tools/dotc/core/SymDenotations$SymDenotation;";
  private static final String CAND = "Ldotty/tools/dotc/typer/Implicits$Candidate;";
  private static final String RESULT = "Ldotty/tools/dotc/typer/Implicits$SearchResult;";
  private static final String LIST = "Lscala/collection/immutable/List;";

  private static final String BJ = "dotty.tools.backend.jvm.";
  private static final String MNODE = "Lorg/objectweb/asm/tree/MethodNode;";
  private static final String CNODE = "Lorg/objectweb/asm/tree/ClassNode;";
  private static final String BUF = "Lscala/collection/mutable/ListBuffer;";
  private static final String OPT = BJ + "opt.";
  // Released 3.x before 3.8 use the shaded scala-asm, and the server rewrites org.objectweb.asm in these sources
  // (including MNODE and CNODE) to the compiler's package, so one descriptor serves both ASM packages.

  private static JoinPoint avoidCase(String name, int anon) {
    String c = "dotty.tools.dotc.core.NameKinds$AvoidNameKind$$anon$";
    return new JoinPoint("names", Kind.REPLACE_HASHCODE, c + anon, "hashCode", "()I", null, name,
      "inherits NameKinds.scala:36 (enum case " + name + ")")
      .or(new Alt(c + (anon - 1), "hashCode", "()I", null, name));
  }

  static final String RUN = "dotty.tools.dotc.Run";
  static final String PLUGINS = "dotty.tools.dotc.plugins.Plugins";
  static final String PHASE = "dotty.tools.dotc.core.Phases$Phase";

  static final List<JoinPoint> ALL = List.of(
    new JoinPoint("gate", Kind.GATE, PLUGINS, "addPluginPhases", "(" + LIST + CTX + ")" + LIST, null, null,
      "Plugins.scala:128 research plugins only on SNAPSHOT/NIGHTLY/nonbootstrapped (call replaced)"),
    new JoinPoint("fork", Kind.SUBSTITUTE, RUN, "runPhases$1",
      "(" + LIST + LIST + "[Ldotty/tools/dotc/core/Phases$Phase;" + CTX + ")V", null, null,
      "Run.scala:421/425 phaseBegin/phaseEnd (call to Phase.runOn replaced)")
      // 3.4 to 3.7: the per-phase loop body is a lambda (and 3.3 has one ObjectRef fewer)
      .or("runPhases$1$$anonfun$1", "(" + CTX + "Lscala/runtime/ObjectRef;Ldotty/tools/dotc/profile/Profiler;"
        + "Lscala/runtime/ObjectRef;Lscala/runtime/BooleanRef;Ldotty/tools/dotc/core/Phases$Phase;)V")
      .or("runPhases$1$$anonfun$1", "(" + CTX + "Ldotty/tools/dotc/profile/Profiler;"
        + "Lscala/runtime/ObjectRef;Lscala/runtime/BooleanRef;Ldotty/tools/dotc/core/Phases$Phase;)V"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Typer", "typed",
      "(" + TREE + TYPE + LOCKED + CTX + ")" + TREE, Advices.Typed.class, null, "Typer.scala:4157 typed"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Typer", "adapt",
      "(" + TREE + TYPE + LOCKED + CTX + ")" + TREE, Advices.Adapt.class, null, "Typer.scala:4541 adapt (arms)"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Typer", "adapt1",
      "(" + TREE + TYPE + LOCKED + CTX + ")" + TREE, Advices.Adapt1.class, null, "Typer.scala:4541 adapt (fires)"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Namer$Completer", "complete",
      "(" + DENOT + CTX + ")V", Advices.Complete.class, null, "Namer.scala:940 complete (arms)"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Namer$Completer", "completeInCreationContext",
      "(" + DENOT + ")V", Advices.CompleteInCreationContext.class, null, "Namer.scala:940 complete (fires)"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Namer$ClassCompleter", "completeInCreationContext",
      "(" + DENOT + ")V", Advices.CompleteInCreationContext.class, null, "Namer.scala:940 complete (fires, override)"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits", "inferImplicit",
      "(" + TYPE + TREE + "JLscala/collection/immutable/Set;" + CTX + ")" + RESULT, Advices.InferImplicit.class, null,
      "Implicits.scala:1101 implicitSearch")
      // 3.3: no `ignored` parameter
      .or(new Alt("dotty.tools.dotc.typer.Implicits", "inferImplicit", "(" + TYPE + TREE + "J" + CTX + ")" + RESULT,
        Advices.InferImplicit33.class, null)),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitSearch", "tryImplicit",
      "(" + CAND + "Z)" + RESULT, Advices.TryImplicit.class, null, "Implicits.scala:1312 candidate (arms)"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits", "typedImplicit",
      "(" + CAND + TYPE + TREE + "J" + CTX + ")" + RESULT, Advices.TypedImplicit.class, null,
      "Implicits.scala:1312 candidate (fires)"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.reporting.Reporter", "report",
      "(Ldotty/tools/dotc/reporting/Diagnostic;" + CTX + ")V", Advices.Report.class, null, "Reporter.scala:241 report"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.ast.Trees$Instance$TreeCopier", "finalize",
      "(" + TREE + TREE + ")" + TREE, Advices.Finalize.class, null, "Trees.scala:1282 copied"),
    new JoinPoint("fork", Kind.ADVICE, "dotty.tools.dotc.ast.Trees$Instance$TreeCopier", "finalize",
      "(" + TREE + "Ldotty/tools/dotc/ast/Trees$MemberDef;)Ldotty/tools/dotc/ast/Trees$MemberDef;", Advices.Finalize.class, null,
      "Trees.scala:1289 copied"),

    new JoinPoint("jvm", Kind.ADVICE, BJ + "CodeGen", "generateClassNodes",
      "(Ldotty/tools/dotc/ast/Trees$TypeDef;" + CTX + ")" + LIST, Advices.GenerateClassNodes.class, null,
      "CodeGen.scala:162 classNodes (fires on return, after atPhase(sbtExtractDependencies))")
      // 3.8 to 3.10: genUnit's local genClassDef(cd) appends GeneratedClass(classNode, ...) to a ListBuffer
      .or(new Alt(BJ + "CodeGen", "genClassDef$1", "(" + CTX + BUF + BUF + "Ldotty/tools/dotc/ast/Trees$TypeDef;)V",
        Advices.GenClassDef310.class, null))
      // 3.3 to 3.7: the same, with the CompilationUnit as a parameter
      .or(new Alt(BJ + "CodeGen", "genClassDef$1",
        "(" + CTX + "Ldotty/tools/dotc/CompilationUnit;" + BUF + BUF + "Ldotty/tools/dotc/ast/Trees$TypeDef;)V",
        Advices.GenClassDef.class, null)),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeSkelBuilder$PlainSkelBuilder", "genDefDef",
      "(Ldotty/tools/dotc/ast/Trees$DefDef;" + CTX + ")V", Advices.GenDefDef.class, null, "BCodeSkelBuilder.scala:929 genMethod (stashes dd)")
      // 3.3 to 3.7: the backend has no Context parameters (DottyBackendInterface.ctx); see Advices.GenClassDef
      .or("genDefDef", "(Ldotty/tools/dotc/ast/Trees$DefDef;)V"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeSkelBuilder$PlainSkelBuilder", "initJMethod",
      "(I" + LIST + CTX + ")V", Advices.InitJMethod.class, "mnode", "BCodeSkelBuilder.scala:929 genMethod (fires)")
      .or(new Alt(BJ + "BCodeSkelBuilder$PlainSkelBuilder", "initJMethod", "(I" + LIST + ")V", Advices.InitJMethodNoCtx.class, "mnode")),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genStat",
      "(" + TREE + CTX + ")V", Advices.GenStat.class, null, "BCodeBodyBuilder.scala:91 genLoadEnter/Exit")
      .or(new Alt(BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genStat", "(" + TREE + ")V", Advices.GenLoadNoCtx.class, null)),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genLoadTo",
      "(" + TREE + "L" + "dotty/tools/backend/jvm/BType;Ldotty/tools/backend/jvm/BCodeSkelBuilder$LoadDestination;" + CTX + ")V",
      Advices.GenLoadTo.class, null, "BCodeBodyBuilder.scala:320 genLoadEnter/Exit")
      // 3.3 to 3.7: BType is BTypes#BType
      .or(new Alt(BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genLoadTo",
        "(" + TREE + "Ldotty/tools/backend/jvm/BTypes$BType;Ldotty/tools/backend/jvm/BCodeSkelBuilder$LoadDestination;)V",
        Advices.GenLoadNoCtx.class, null)),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.LocalOptimizer", "methodOptimizations",
      "(" + MNODE + "Ljava/lang/String;)Z", Advices.MethodOptimizations.class, null, "LocalOptimizer.scala:219 optimizerPass (stashes owner)")
      // 3.8 to 3.10: the local optimiser is LocalOpt (3.3 to 3.7 have no optimiser)
      .or(new Alt(OPT + "LocalOpt", "methodOptimizations", "(" + MNODE + "Ljava/lang/String;)Z", Advices.MethodOptimizations.class, null)),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.LocalOptimizer", "traceIfChanged$1",
      "(Z" + MNODE + "Lscala/runtime/ObjectRef;Ljava/lang/String;)V", Advices.TraceIfChanged.class, null,
      "LocalOptimizer.scala:219 optimizerPass (fires)")
      // released 3.8 to 3.10 are built with -opt: traceIfChanged$1 is inlined at its 13 call sites (and dead); hook the
      // copies in the methods that held the calls, anchored on methodOptimizations (ReplayAgent.InlinedTraceSites)
      .or(new Alt(OPT + "LocalOpt", "methodOptimizations", "(" + MNODE + "Ljava/lang/String;)Z", Advices.InlinedTraceIfChanged.class, null))
      .or(new Alt(OPT + "LocalOpt", "traceIfChanged$1", "(Z" + MNODE + "Lscala/runtime/ObjectRef;Ljava/lang/String;)V",
        Advices.TraceIfChanged.class, null)),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.GlobalOptimizer", "doInline$1",
      "(" + MNODE + "Ldotty/tools/backend/jvm/opt/MethodInlinerState;Lscala/runtime/BooleanRef;"
        + "Ldotty/tools/backend/jvm/opt/InlineRequest;Ldotty/tools/backend/jvm/analysis/AliasingFrame;Lscala/Option;)"
        + "Lscala/collection/immutable/Map;", Advices.DoInline.class, null, "Inliner.scala:127 inlined (arms)")
      // 3.8 to 3.10: the inliner is opt.Inliner; released builds inlined doInline into the per-request lambda
      .or(new Alt(OPT + "Inliner", "runInliner$$anonfun$1", "(Lscala/runtime/BooleanRef;Ldotty/tools/backend/jvm/opt/MethodInlinerState;"
        + MNODE + "Lscala/collection/mutable/Set;Lscala/collection/mutable/Map;Lscala/collection/mutable/Queue;Lscala/Function1;"
        + "Lscala/runtime/BooleanRef;Lscala/Tuple2;)V", Advices.DoInlineLambda.class, null))
      .or(new Alt(OPT + "Inliner", "doInline$1", "(" + MNODE + "Ldotty/tools/backend/jvm/opt/MethodInlinerState;Lscala/runtime/BooleanRef;"
        + "Ldotty/tools/backend/jvm/opt/InlineRequest;Ldotty/tools/backend/jvm/analysis/AliasingFrame;Lscala/Option;)"
        + "Lscala/collection/immutable/Map;", Advices.DoInline.class, null)),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.GlobalOptimizer", "inlineCallsite",
      "(Ldotty/tools/backend/jvm/opt/KnownCallsite;Lscala/Option;Z)Lscala/collection/immutable/Map;", Advices.InlineCallsite.class, null,
      "Inliner.scala:127 inlined (fires)")
      .or(new Alt(OPT + "Inliner", "inlineCallsite", "(Ldotty/tools/backend/jvm/opt/KnownCallsite;Lscala/Option;Z)Lscala/collection/immutable/Map;",
        Advices.InlineCallsite.class, null)),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "CodeGen", "serializeClassNode",
      "(" + CNODE + ")[B", Advices.SerializeClassNode.class, null,
      "CodeGen.scala:250 classWritten (fires on return, after the ClassWriter)")
      // 3.3 to 3.10: PostProcessor.sendToDisk -> serializeClass, after the local optimiser and setInnerClasses
      .or(new Alt(BJ + "PostProcessor", "serializeClass", "(" + CNODE + ")[B", Advices.SerializeClassNode.class, null)),

    new JoinPoint("fix", Kind.ADVICE, "dotty.tools.dotc.ast.tpd$TimeTravellingTreeCopier", "TypeApply",
      "(" + TREE + TREE + LIST + CTX + ")Ldotty/tools/dotc/ast/Trees$TypeApply;", Advices.TypeApplyFix.class, null,
      "tpd.scala:851 TypeApply kept if unchanged"),

    new JoinPoint("names", Kind.DEFINE_HASHCODE, "dotty.tools.dotc.core.Names$SimpleName", "hashCode", "()I", null, "value",
      "Names.scala:264 value.hashCode"),
    new JoinPoint("names", Kind.DEFINE_HASHCODE, "dotty.tools.dotc.core.Names$TypeName", "hashCode", "()I", null, "toTermName",
      "Names.scala:344 ~toTermName.hashCode"),
    new JoinPoint("names", Kind.DEFINE_HASHCODE, "dotty.tools.dotc.core.Names$DerivedName", "hashCode", "()I", null, "underlying",
      "Names.scala:388 underlying.hashCode * 31 + info.hashCode"),
    new JoinPoint("names", Kind.DEFINE_HASHCODE, "dotty.tools.dotc.core.NameKinds$NameKind", "hashCode", "()I", null, "tag",
      "NameKinds.scala:36 tag"),
    new JoinPoint("names", Kind.DEFINE_HASHCODE, "dotty.tools.dotc.core.NameKinds$NameKind$Info", "hashCode", "()I", null, "$outer",
      "NameKinds.scala:46 kind.hashCode"),

    // Subclasses compiled without the base hashCode got a synthesized one (case class: product hash;
    // enum case: hash of its name). In the fork they inherit NameKind.hashCode, so replace the body with `tag`.
    // For an enum case the required "field" is the case name its synthesized hashCode loads (ReplayAgent.loadsString):
    // the anonymous class numbering differs between versions (3.7: $$anon$8 to $$anon$10, else $$anon$9 to $$anon$11).
    new JoinPoint("names", Kind.REPLACE_HASHCODE, "dotty.tools.dotc.core.NameKinds$UniqueNameKind", "hashCode", "()I", null, null,
      "inherits NameKinds.scala:36 (case class)"),
    avoidCase("UpperBound", 9),
    avoidCase("LowerBound", 10),
    avoidCase("BothBounds", 11),

    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitRefs", "candidateKind$1",
      "(" + TYPE + "ZLdotty/tools/dotc/core/Types$TermRef;" + CTX + ")I", Advices.CandidateKind.class, null,
      "(none) filterMatching drops a ref"),
    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitSearch", "comesTooLate$1",
      "(" + CAND + ")Z", Advices.ComesTooLate.class, "x$5", "(none) searchImplicit drops a candidate"),
    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitSearch", "searchImplicit",
      "(" + LIST + "Z)" + RESULT, Advices.SearchImplicit.class, "x$5", "(none) one ranking over one scope")
      // 3.3: the class's Context field is x$4 (no `ignored` parameter before it)
      .or(new Alt("dotty.tools.dotc.typer.Implicits$ImplicitSearch", "searchImplicit", "(" + LIST + "Z)" + RESULT,
        Advices.SearchImplicit33.class, "x$4")),
    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitSearch", "rank$1",
      "(ZZLscala/collection/mutable/ListBuffer;Z" + LIST + RESULT + LIST + ")" + RESULT, Advices.Rank.class, "x$5",
      "(none) rank step: pruned candidates")
      // 3.3: rank(pending, found, rfailures) captures only `contextual`
      .or(new Alt("dotty.tools.dotc.typer.Implicits$ImplicitSearch", "rank$1", "(Z" + LIST + RESULT + LIST + ")" + RESULT,
        Advices.Rank33.class, "x$4")),

    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.typer.Typer$", "tryEither",
      "(Lscala/Function1;Lscala/Function2;" + CTX + ")Ljava/lang/Object;", Advices.TryEither.class, null, "(none) tentative typing")
      // 3.3: a method of class Typer, not of its companion
      .or(new Alt("dotty.tools.dotc.typer.Typer", "tryEither", "(Lscala/Function1;Lscala/Function2;" + CTX + ")Ljava/lang/Object;",
        Advices.TryEither.class, null)),
    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.core.Contexts$FreshContext", "setNewTyperState",
      "()Ldotty/tools/dotc/core/Contexts$FreshContext;", Advices.SetNewTyperState.class, null, "(none) tryEither's nested state"),
    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.core.TyperState", "commit",
      "(" + CTX + ")V", Advices.Commit.class, null, "(none) kept vs discarded"),
    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.reporting.StoreReporter", "report",
      "(Ldotty/tools/dotc/reporting/Diagnostic;" + CTX + ")V", Advices.StoreReport.class, "fromTyperState",
      "(none) reports buffered in a TyperState")
  );
}
