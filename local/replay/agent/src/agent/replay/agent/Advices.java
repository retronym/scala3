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

  // ---- not in the fork -----------------------------------------------------------------------

  /** {@code ImplicitRefs.filterMatching}'s local {@code candidateKind}: kind None drops the ref. */
  public static final class CandidateKind {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(2) Object ref, @Advice.Argument(3) Object ctx, @Advice.Return int kind) {
      if (kind == 0) {
        Observer o = Hooks.observer;
        if (o != null) o.rejected(ref, "noMatch", ctx);
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
}
