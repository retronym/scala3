// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import dotty.tools.dotc.ast.Trees;
import dotty.tools.dotc.core.Contexts;
import dotty.tools.dotc.core.Types;
import dotty.tools.dotc.util.SourceFile;
import replay.agent.api.Hooks;
import replay.agent.api.Observer;

/**
 * Advice for each join point in {@link JoinPoints}. Inlined into the target method (Byte Buddy's
 * default), so with no observer installed a hook is a getstatic and a branch.
 *
 * Rules: no allocation on enter or exit (state shared between enter and exit is in
 * {@code @Advice.Local}s, never an array or holder); no calls into the compiler beyond the field
 * accessors needed to mirror a guard of the fork's hook site; the observer is read once, on enter,
 * as the fork's hook sites do.
 */
public final class Advices {
  private Advices() {}

  /** {@code Typer.typed(tree, pt, locked)}. The fork hooks only the final {@code else} branch, so
   *  mirror its two guards: the source-switching self-call and a cancelled run fire nothing. */
  public static final class Typed {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Trees.Tree<?> tree, @Advice.Argument(1) Object pt,
                          @Advice.Argument(3) Contexts.Context ctx) {
      Observer o = Hooks.observer;
      if (o == null) return null;
      SourceFile ts = tree.source();
      SourceFile cs = ctx.source();
      if (!(ts == null ? cs == null : ts.equals(cs)) && ts.exists()) return null;
      if (ctx.run().isCancelled()) return null;
      o.typedEnter(tree, pt, ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object tree, @Advice.Argument(1) Object pt,
                     @Advice.Argument(3) Object ctx, @Advice.Return Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.typedExit(tree, pt, t == null ? res : null, ctx);
    }
  }

  /** {@code Typer.adapt(tree, pt, locked)}: arms {@link Adapt1}. The fork hooks the {@code adapt1}
   *  call inside adapt's {@code try}, so a TypeError still exits the event before errorTree. */
  public static final class Adapt {
    @Advice.OnMethodEnter
    static void enter() { Hooks.armAdapt = true; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { Hooks.armAdapt = false; }
  }

  /** {@code Typer.adapt1}: fires only when armed, so adapt1's direct self-call is not an event. */
  public static final class Adapt1 {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Object tree, @Advice.Argument(1) Object pt, @Advice.Argument(3) Object ctx) {
      if (!Hooks.armAdapt) return null;
      Hooks.armAdapt = false;
      Observer o = Hooks.observer;
      if (o != null) o.adaptEnter(tree, pt, ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object tree, @Advice.Argument(1) Object pt,
                     @Advice.Argument(3) Object ctx, @Advice.Return Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.adaptExit(tree, pt, t == null ? res : null, ctx);
    }
  }

  /** {@code Namer.Completer.complete(denot)(using ctx)}: arms {@link CompleteInCreationContext}
   *  and publishes ctx, which that method lacks. The fork hooks only the
   *  {@code completeInCreationContext} call, not the run-id branch or SuspendException handling. */
  public static final class Complete {
    @Advice.OnMethodEnter
    static Object enter(@Advice.Argument(1) Object ctx) {
      Object prev = Hooks.completeCtx;
      Hooks.completeCtx = ctx;
      Hooks.armComplete = true;
      return prev;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Object prev) {
      Hooks.completeCtx = prev;
      Hooks.armComplete = false;
    }
  }

  /** {@code completeInCreationContext(denot)} in {@code Completer} and its overrides. */
  public static final class CompleteInCreationContext {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object denot,
                      @Advice.Local("o") Observer o, @Advice.Local("c") Object c) {
      if (Hooks.armComplete) {
        Hooks.armComplete = false;
        o = Hooks.observer;
        c = Hooks.completeCtx;
        if (o != null) o.completeEnter(denot, c);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(0) Object denot, @Advice.Thrown Throwable t,
                     @Advice.Local("o") Observer o, @Advice.Local("c") Object c) {
      if (o != null) o.completeExit(denot, t == null, c);
    }
  }

  /** {@code Implicits.inferImplicit(pt, argument, span, ignored)} (the trait's default method). */
  public static final class InferImplicit {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Object pt, @Advice.Argument(1) Object arg,
                          @Advice.Argument(2) long span, @Advice.Argument(4) Object ctx) {
      Observer o = Hooks.observer;
      if (o != null) o.implicitSearchEnter(pt, arg, span, ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object pt, @Advice.Argument(1) Object arg,
                     @Advice.Argument(2) long span, @Advice.Argument(4) Object ctx,
                     @Advice.Return Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.implicitSearchExit(pt, arg, span, t == null ? res : null, ctx);
    }
  }

  /** {@code ImplicitSearch.tryImplicit}: arms {@link TypedImplicit}; ImportSuggestions' own calls of
   *  typedImplicit stay unobserved, as in the fork. */
  public static final class TryImplicit {
    @Advice.OnMethodEnter
    static void enter() { Hooks.armCandidate = true; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { Hooks.armCandidate = false; }
  }

  /** {@code Implicits.typedImplicit(cand, pt, argument, span)(using typingCtx)}. */
  public static final class TypedImplicit {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Object cand, @Advice.Argument(3) long span, @Advice.Argument(4) Object ctx) {
      if (!Hooks.armCandidate) return null;
      Hooks.armCandidate = false;
      Observer o = Hooks.observer;
      if (o != null) o.candidateEnter(cand, span, ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object cand, @Advice.Argument(3) long span,
                     @Advice.Argument(4) Object ctx, @Advice.Return Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.candidateExit(cand, span, t == null ? res : null, ctx);
    }
  }

  /** {@code Reporter.report(dia)(using ctx)}, before suppression and buffering. */
  public static final class Report {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object reporter, @Advice.Argument(0) Object dia, @Advice.Argument(1) Object ctx) {
      Observer o = Hooks.observer;
      if (o != null) o.report(dia, reporter, ctx);
    }
  }

  /** Both {@code TreeCopier.finalize} overloads, on normal return. */
  public static final class Finalize {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object tree, @Advice.Return Object res) {
      Observer o = Hooks.observer;
      if (o != null) o.copied(tree, res);
    }
  }

  /** Optional behaviour patch, not an observation: the fork's {@code TimeTravellingTreeCopier.TypeApply}
   *  fix, keeping an unchanged TypeApply. Calls {@code =:=}, so it runs compiler code. */
  public static final class TypeApplyFix {
    @Advice.OnMethodExit
    @SuppressWarnings({"rawtypes"})
    static void exit(@Advice.Argument(0) Object tree, @Advice.Argument(1) Object fun, @Advice.Argument(2) Object args,
                     @Advice.Argument(3) Contexts.Context ctx,
                     @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object res) {
      if (tree instanceof Trees.TypeApply old && res != old && old.fun() == fun && old.args() == args) {
        Object t1 = ((Trees.Tree) res).tpe();
        Object t0 = old.tpe();
        if (t1 == t0 || t1.equals(t0) && ((Types.Type) t1).$eq$colon$eq((Types.Type) t0, ctx)) res = old;
      }
    }
  }

  // ---- JVM backend (the fork's backend hooks) ------------------------------------------------
  // The optimiser's sites have no Context; the static observer needs none.

  /** {@code CodeGen.generateClassNodes(typeDef)}: the fork fires just before the method's last step
   *  (sbt class names under atPhase, which does not touch the ClassNodes), so fire on return. */
  public static final class GenerateClassNodes {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object typeDef, @Advice.Argument(1) Object ctx, @Advice.Return Object nodes) {
      Observer o = Hooks.observer;
      if (o != null) o.classNodes(typeDef, nodes, ctx);
    }
  }

  /** {@code CodeGen.genUnit}'s local {@code genClassDef(cd)} on 3.3 to 3.7: {@code genClassDef$1(ctx, unit,
   *  generatedClasses, generatedTasty, cd)}. Like {@code generateClassNodes} it builds the main and mirror ClassNodes
   *  and adds the TASTy attribute; it appends them to {@code generatedClasses} (GeneratedClass(classNode, ...)) instead
   *  of returning them, so fire on exit with the appended nodes as (ClassNode, null) pairs, the shape
   *  {@link GenerateClassNodes} passes. The backend methods below it take no Context: publish this one. */
  public static final class GenClassDef {
    @Advice.OnMethodEnter
    static int enter(@Advice.Argument(0) Object ctx, @Advice.Argument(2) scala.collection.mutable.ListBuffer<?> classes,
                     @Advice.Local("prev") Object prev) {
      prev = Hooks.backendCtx;
      Hooks.backendCtx = ctx;
      return classes.length();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void exit(@Advice.Enter int n0, @Advice.Argument(0) Object ctx,
                     @Advice.Argument(2) scala.collection.mutable.ListBuffer<?> classes, @Advice.Argument(4) Object cd,
                     @Advice.Local("prev") Object prev, @Advice.Thrown Throwable t) {
      Hooks.backendCtx = prev;
      Observer o = Hooks.observer;
      int n = classes.length();
      if (o != null && t == null && n > n0) {
        // Allocates, but only with an observer installed. GeneratedClass is a case class: element 0 is classNode.
        scala.collection.immutable.List nodes = scala.collection.immutable.Nil$.MODULE$;
        for (int i = n - 1; i >= n0; i--)
          nodes = new scala.collection.immutable.$colon$colon(
            new scala.Tuple2(((scala.Product) classes.apply(i)).productElement(0), null), nodes);
        o.classNodes(cd, nodes, ctx);
      }
    }
  }

  /** {@link GenClassDef} for 3.8 to 3.10: {@code genClassDef$1(ctx, generatedClasses, generatedTasty, cd)}. */
  public static final class GenClassDef310 {
    @Advice.OnMethodEnter
    static int enter(@Advice.Argument(1) scala.collection.mutable.ListBuffer<?> classes) { return classes.length(); }

    @Advice.OnMethodExit
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void exit(@Advice.Enter int n0, @Advice.Argument(0) Object ctx,
                     @Advice.Argument(1) scala.collection.mutable.ListBuffer<?> classes, @Advice.Argument(3) Object cd) {
      Observer o = Hooks.observer;
      int n = classes.length();
      if (o != null && n > n0) {
        scala.collection.immutable.List nodes = scala.collection.immutable.Nil$.MODULE$;
        for (int i = n - 1; i >= n0; i--)
          nodes = new scala.collection.immutable.$colon$colon(
            new scala.Tuple2(((scala.Product) classes.apply(i)).productElement(0), null), nodes);
        o.classNodes(cd, nodes, ctx);
      }
    }
  }

  /** {@code PlainSkelBuilder.genDefDef(dd)}: stashes dd for {@link InitJMethod}. */
  public static final class GenDefDef {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object dd) { Hooks.genDefDef = dd; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { Hooks.genDefDef = null; }
  }

  /** {@code initJMethod}, called only by genDefDef: the fork's hook follows it, if mnode was set. */
  public static final class InitJMethod {
    @Advice.OnMethodExit
    static void exit(@Advice.FieldValue("mnode") Object mnode, @Advice.Argument(2) Object ctx) {
      Object dd = Hooks.genDefDef;
      Hooks.genDefDef = null;
      Observer o = Hooks.observer;
      if (o != null && dd != null && mnode != null) o.genMethod(dd, mnode, ctx);
    }
  }

  /** {@code PlainBodyBuilder.genStat(tree)} and {@code genLoadTo(tree, expected, dest)}. As in the fork,
   *  mnode is read once on entry and a throw skips the exit (the compilation is over anyway). */
  public static final class GenStat {
    @Advice.OnMethodEnter
    static void enter(@Advice.This dotty.tools.backend.jvm.BCodeSkelBuilder.PlainSkelBuilder self,
                      @Advice.Argument(0) Object tree, @Advice.Argument(1) Object ctx,
                      @Advice.Local("o") Observer o, @Advice.Local("m") Object m) {
      o = Hooks.observer;
      if (o != null) { m = self.mnode(); o.genLoadEnter(tree, m, ctx); }
    }

    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object tree, @Advice.Argument(1) Object ctx,
                     @Advice.Local("o") Observer o, @Advice.Local("m") Object m) {
      if (o != null) o.genLoadExit(tree, m, ctx);
    }
  }

  public static final class GenLoadTo {
    @Advice.OnMethodEnter
    static void enter(@Advice.This dotty.tools.backend.jvm.BCodeSkelBuilder.PlainSkelBuilder self,
                      @Advice.Argument(0) Object tree, @Advice.Argument(3) Object ctx,
                      @Advice.Local("o") Observer o, @Advice.Local("m") Object m) {
      o = Hooks.observer;
      if (o != null) { m = self.mnode(); o.genLoadEnter(tree, m, ctx); }
    }

    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object tree, @Advice.Argument(3) Object ctx,
                     @Advice.Local("o") Observer o, @Advice.Local("m") Object m) {
      if (o != null) o.genLoadExit(tree, m, ctx);
    }
  }

  /** {@link InitJMethod} on 3.3 to 3.7: {@code initJMethod(flags, params)} has no Context; use {@link GenClassDef}'s. */
  public static final class InitJMethodNoCtx {
    @Advice.OnMethodExit
    static void exit(@Advice.FieldValue("mnode") Object mnode) {
      Object dd = Hooks.genDefDef;
      Hooks.genDefDef = null;
      Observer o = Hooks.observer;
      if (o != null && dd != null && mnode != null) o.genMethod(dd, mnode, Hooks.backendCtx);
    }
  }

  /** {@link GenStat} and {@link GenLoadTo} on 3.3 to 3.7: {@code genStat(tree)}, {@code genLoadTo(tree, expected, dest)}
   *  have no Context; use {@link GenClassDef}'s. */
  public static final class GenLoadNoCtx {
    @Advice.OnMethodEnter
    static void enter(@Advice.This dotty.tools.backend.jvm.BCodeSkelBuilder.PlainSkelBuilder self,
                      @Advice.Argument(0) Object tree, @Advice.Local("o") Observer o, @Advice.Local("m") Object m) {
      o = Hooks.observer;
      if (o != null) { m = self.mnode(); o.genLoadEnter(tree, m, Hooks.backendCtx); }
    }

    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object tree, @Advice.Local("o") Observer o, @Advice.Local("m") Object m) {
      if (o != null) o.genLoadExit(tree, m, Hooks.backendCtx);
    }
  }

  /** {@code LocalOptimizer.methodOptimizations(method, ownerClassName)}: stashes the owner, which the
   *  lambda-lifted {@code traceIfChanged$1} does not capture. */
  public static final class MethodOptimizations {
    @Advice.OnMethodEnter
    static String enter(@Advice.Argument(1) String owner) {
      String prev = Hooks.optOwner;
      Hooks.optOwner = owner;
      return prev;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter String prev) { Hooks.optOwner = prev; }
  }

  /** {@code traceIfChanged$1(doTrace, method, currentTrace, optName)}: every local pass calls it. */
  public static final class TraceIfChanged {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(1) Object method, @Advice.Argument(3) String pass) {
      Observer o = Hooks.observer;
      if (o != null) o.optimizerPass(Hooks.optOwner, method, pass);
    }
  }

  /** {@code GlobalOptimizer}'s local {@code doInline(r, aliasFrame, w)}: arms {@link InlineCallsite}
   *  with its target method; {@code inlineCallsites} (closure optimiser) stays unobserved, as in the fork. */
  public static final class DoInline {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object method) { Hooks.armInline = method; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { Hooks.armInline = null; }
  }

  /** {@link DoInline} on released 3.8 to 3.10, whose own optimiser inlined {@code doInline} (and so left
   *  {@code doInline$1} dead) into the per-request lambda {@code runInliner$$anonfun$1(rolledBack, state, method, ...)};
   *  in 3.10 every {@code inlineCallsite} call comes from that lambda. */
  public static final class DoInlineLambda {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(2) Object method) { Hooks.armInline = method; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { Hooks.armInline = null; }
  }

  /** Not an advice: marks the alternative of {@code traceIfChanged$1} whose inlined call sites (the pass name
   *  constants in {@code methodOptimizations} and {@code removalRound$1}) get a {@link Hooks#optimizerPass} call. */
  public static final class InlinedTraceIfChanged { private InlinedTraceIfChanged() {} }

  public static final class InlineCallsite {
    @Advice.OnMethodEnter
    static Object enter() {
      Object m = Hooks.armInline;
      Hooks.armInline = null;
      return m;
    }

    @Advice.OnMethodExit
    static void exit(@Advice.Enter Object method, @Advice.Argument(0) Object callsite, @Advice.Return Object insnMap) {
      if (method != null) {
        Observer o = Hooks.observer;
        if (o != null) o.inlined(method, callsite, insnMap);
      }
    }
  }

  /** {@code CodeGen.serializeClassNode(classNode)}. The fork fires before {@code classNode.accept(writer)};
   *  the ClassWriter reads the node without changing it, so firing on exit (also on a throw) sees the same state. */
  public static final class SerializeClassNode {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(0) Object classNode) {
      Observer o = Hooks.observer;
      if (o != null) o.classWritten(classNode);
    }
  }

  // ---- not in the fork -----------------------------------------------------------------------

  /** {@code ImplicitRefs.filterMatching}'s local {@code candidateKind}: kind None drops the ref. */
  public static final class CandidateKind {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(2) Object ref, @Advice.Argument(3) Object ctx, @Advice.Return int kind) {
      if (kind == 0) {
        Observer o = Hooks.observer;
        if (o != null) o.rejected(ref, "notEligible", ctx);
      }
    }
  }

  /** {@code ImplicitSearch.searchImplicit}'s local {@code comesTooLate}: true drops the candidate.
   *  The search's Context is the class parameter field {@code x$5}. */
  public static final class ComesTooLate {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object cand, @Advice.FieldValue("x$5") Object ctx, @Advice.Return boolean tooLate) {
      if (tooLate) {
        Observer o = Hooks.observer;
        if (o != null) o.rejected(cand, "comesTooLate", ctx);
      }
    }
  }

  /** {@link InferImplicit} for 3.3, whose {@code inferImplicit} has no {@code ignored} parameter. */
  public static final class InferImplicit33 {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Object pt, @Advice.Argument(1) Object arg,
                          @Advice.Argument(2) long span, @Advice.Argument(3) Object ctx) {
      Observer o = Hooks.observer;
      if (o != null) o.implicitSearchEnter(pt, arg, span, ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object pt, @Advice.Argument(1) Object arg,
                     @Advice.Argument(2) long span, @Advice.Argument(3) Object ctx,
                     @Advice.Return Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.implicitSearchExit(pt, arg, span, t == null ? res : null, ctx);
    }
  }

  /** {@code ImplicitSearch.searchImplicit(eligible, contextual)} (private). */
  public static final class SearchImplicit {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Object eligible, @Advice.Argument(1) boolean contextual,
                          @Advice.FieldValue("x$5") Object ctx) {
      Observer o = Hooks.observer;
      if (o != null) o.scopeEnter(eligible, contextual, ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object eligible, @Advice.Argument(1) boolean contextual,
                     @Advice.FieldValue("x$5") Object ctx, @Advice.Return Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.scopeExit(eligible, contextual, t == null ? res : null, ctx);
    }
  }

  /** {@code searchImplicit}'s local {@code rank(pending, found, rfailures)}: successive pending lists
   *  show which candidates a success (or an ambiguity being healed) pruned. */
  public static final class Rank {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(4) Object pending, @Advice.Argument(5) Object found, @Advice.FieldValue("x$5") Object ctx) {
      Observer o = Hooks.observer;
      if (o != null) o.rankStep(pending, found, ctx);
    }
  }

  /** {@link SearchImplicit} for 3.3: the Context field is {@code x$4}. */
  public static final class SearchImplicit33 {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Object eligible, @Advice.Argument(1) boolean contextual,
                          @Advice.FieldValue("x$4") Object ctx) {
      Observer o = Hooks.observer;
      if (o != null) o.scopeEnter(eligible, contextual, ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object eligible, @Advice.Argument(1) boolean contextual,
                     @Advice.FieldValue("x$4") Object ctx, @Advice.Return Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.scopeExit(eligible, contextual, t == null ? res : null, ctx);
    }
  }

  /** {@link Rank} for 3.3: {@code rank$1(contextual, pending, found, rfailures)}, Context field {@code x$4}. */
  public static final class Rank33 {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(1) Object pending, @Advice.Argument(2) Object found, @Advice.FieldValue("x$4") Object ctx) {
      Observer o = Hooks.observer;
      if (o != null) o.rankStep(pending, found, ctx);
    }
  }

  /** {@code Typer.tryEither(op)(fallBack)}: arms {@link SetNewTyperState}. */
  public static final class TryEither {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(2) Object ctx) {
      Observer o = Hooks.observer;
      Hooks.armTyperState = true;
      if (o != null) o.tentativeEnter(ctx);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(2) Object ctx, @Advice.Thrown Throwable t) {
      Hooks.armTyperState = false;
      if (o != null) o.tentativeExit(t != null, ctx);
    }
  }

  /** {@code FreshContext.setNewTyperState}: the first call after tryEither enter is its nestedCtx. */
  public static final class SetNewTyperState {
    @Advice.OnMethodExit
    static void exit(@Advice.Return Object fresh) {
      if (Hooks.armTyperState) {
        Hooks.armTyperState = false;
        Observer o = Hooks.observer;
        if (o != null) o.tentativeContext(fresh);
      }
    }
  }

  /** {@code TyperState.commit}: tells kept from discarded tentative states. */
  public static final class Commit {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object ts) {
      Observer o = Hooks.observer;
      if (o != null) o.typerStateCommit(ts);
    }
  }

  /** {@code StoreReporter.report}: the TyperState-buffering branch skips {@code Reporter.report}. */
  public static final class StoreReport {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object reporter, @Advice.Argument(0) Object dia, @Advice.Argument(1) Object ctx,
                      @Advice.FieldValue("fromTyperState") boolean fromTyperState) {
      if (fromTyperState) {
        Observer o = Hooks.observer;
        if (o != null) o.bufferedReport(dia, reporter, ctx);
      }
    }
  }
}
