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

  enum Kind { ADVICE, SUBSTITUTE, DEFINE_HASHCODE, REPLACE_HASHCODE }

  /** {@code fork}: the TyperObserver fork's hooks. {@code fix}: the fork's TypeApply behaviour fix.
   *  {@code names}: the fork's Name/NameKind content hashes. {@code extra} (option {@code rejected}) and
   *  {@code tentative} (option {@code tentative}): hooks the fork does not have. {@code jvm}: the fork's
   *  JVM backend hooks (on unless option {@code nojvm}). */
  record JoinPoint(String group, Kind kind, String owner, String method, String descriptor,
                   Class<?> advice, String requiredField, String forkSite) {
    String key() { return owner + "." + method + descriptor; }
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

  static final String RUN = "dotty.tools.dotc.Run";
  static final String PHASE = "dotty.tools.dotc.core.Phases$Phase";

  static final List<JoinPoint> ALL = List.of(
    new JoinPoint("fork", Kind.SUBSTITUTE, RUN, "runPhases$1",
      "(" + LIST + LIST + "[Ldotty/tools/dotc/core/Phases$Phase;" + CTX + ")V", null, null,
      "Run.scala:421/425 phaseBegin/phaseEnd (call to Phase.runOn replaced)"),
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
      "Implicits.scala:1101 implicitSearch"),
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
      "CodeGen.scala:162 classNodes (fires on return, after atPhase(sbtExtractDependencies))"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeSkelBuilder$PlainSkelBuilder", "genDefDef",
      "(Ldotty/tools/dotc/ast/Trees$DefDef;" + CTX + ")V", Advices.GenDefDef.class, null, "BCodeSkelBuilder.scala:929 genMethod (stashes dd)"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeSkelBuilder$PlainSkelBuilder", "initJMethod",
      "(I" + LIST + CTX + ")V", Advices.InitJMethod.class, "mnode", "BCodeSkelBuilder.scala:929 genMethod (fires)"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genStat",
      "(" + TREE + CTX + ")V", Advices.GenStat.class, null, "BCodeBodyBuilder.scala:91 genLoadEnter/Exit"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "BCodeBodyBuilder$PlainBodyBuilder", "genLoadTo",
      "(" + TREE + "L" + "dotty/tools/backend/jvm/BType;Ldotty/tools/backend/jvm/BCodeSkelBuilder$LoadDestination;" + CTX + ")V",
      Advices.GenLoadTo.class, null, "BCodeBodyBuilder.scala:320 genLoadEnter/Exit"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.LocalOptimizer", "methodOptimizations",
      "(" + MNODE + "Ljava/lang/String;)Z", Advices.MethodOptimizations.class, null, "LocalOptimizer.scala:219 optimizerPass (stashes owner)"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.LocalOptimizer", "traceIfChanged$1",
      "(Z" + MNODE + "Lscala/runtime/ObjectRef;Ljava/lang/String;)V", Advices.TraceIfChanged.class, null,
      "LocalOptimizer.scala:219 optimizerPass (fires)"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.GlobalOptimizer", "doInline$1",
      "(" + MNODE + "Ldotty/tools/backend/jvm/opt/MethodInlinerState;Lscala/runtime/BooleanRef;"
        + "Ldotty/tools/backend/jvm/opt/InlineRequest;Ldotty/tools/backend/jvm/analysis/AliasingFrame;Lscala/Option;)"
        + "Lscala/collection/immutable/Map;", Advices.DoInline.class, null, "Inliner.scala:127 inlined (arms)"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "opt.GlobalOptimizer", "inlineCallsite",
      "(Ldotty/tools/backend/jvm/opt/KnownCallsite;Lscala/Option;Z)Lscala/collection/immutable/Map;", Advices.InlineCallsite.class, null,
      "Inliner.scala:127 inlined (fires)"),
    new JoinPoint("jvm", Kind.ADVICE, BJ + "CodeGen", "serializeClassNode",
      "(Lorg/objectweb/asm/tree/ClassNode;)[B", Advices.SerializeClassNode.class, null,
      "CodeGen.scala:250 classWritten (fires on return, after the ClassWriter)"),

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
    new JoinPoint("names", Kind.REPLACE_HASHCODE, "dotty.tools.dotc.core.NameKinds$UniqueNameKind", "hashCode", "()I", null, null,
      "inherits NameKinds.scala:36 (case class)"),
    new JoinPoint("names", Kind.REPLACE_HASHCODE, "dotty.tools.dotc.core.NameKinds$AvoidNameKind$$anon$9", "hashCode", "()I", null, null,
      "inherits NameKinds.scala:36 (enum case UpperBound)"),
    new JoinPoint("names", Kind.REPLACE_HASHCODE, "dotty.tools.dotc.core.NameKinds$AvoidNameKind$$anon$10", "hashCode", "()I", null, null,
      "inherits NameKinds.scala:36 (enum case LowerBound)"),
    new JoinPoint("names", Kind.REPLACE_HASHCODE, "dotty.tools.dotc.core.NameKinds$AvoidNameKind$$anon$11", "hashCode", "()I", null, null,
      "inherits NameKinds.scala:36 (enum case BothBounds)"),

    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitRefs", "candidateKind$1",
      "(" + TYPE + "ZLdotty/tools/dotc/core/Types$TermRef;" + CTX + ")I", Advices.CandidateKind.class, null,
      "(none) filterMatching drops a ref"),
    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitSearch", "comesTooLate$1",
      "(" + CAND + ")Z", Advices.ComesTooLate.class, "x$5", "(none) searchImplicit drops a candidate"),
    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitSearch", "searchImplicit",
      "(" + LIST + "Z)" + RESULT, Advices.SearchImplicit.class, "x$5", "(none) one ranking over one scope"),
    new JoinPoint("extra", Kind.ADVICE, "dotty.tools.dotc.typer.Implicits$ImplicitSearch", "rank$1",
      "(ZZLscala/collection/mutable/ListBuffer;Z" + LIST + RESULT + LIST + ")" + RESULT, Advices.Rank.class, "x$5",
      "(none) rank step: pruned candidates"),

    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.typer.Typer$", "tryEither",
      "(Lscala/Function1;Lscala/Function2;" + CTX + ")Ljava/lang/Object;", Advices.TryEither.class, null, "(none) tentative typing"),
    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.core.Contexts$FreshContext", "setNewTyperState",
      "()Ldotty/tools/dotc/core/Contexts$FreshContext;", Advices.SetNewTyperState.class, null, "(none) tryEither's nested state"),
    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.core.TyperState", "commit",
      "(" + CTX + ")V", Advices.Commit.class, null, "(none) kept vs discarded"),
    new JoinPoint("tentative", Kind.ADVICE, "dotty.tools.dotc.reporting.StoreReporter", "report",
      "(Ldotty/tools/dotc/reporting/Diagnostic;" + CTX + ")V", Advices.StoreReport.class, "fromTyperState",
      "(none) reports buffered in a TyperState")
  );
}
