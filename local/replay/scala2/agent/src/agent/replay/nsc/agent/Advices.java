// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.nsc.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import replay.nsc.api.Hooks;
import replay.nsc.api.Observer;

/**
 * Advice for each join point in {@link JoinPoints}, inlined into the target method. With no observer a hook
 * is a getstatic and a branch. Compiler objects are passed as {@code Object}: the plugin casts. The observer
 * is read once, on enter, so a recorder installed mid-call sees no unmatched exit.
 */
public final class Advices {
  private Advices() {}

  public static final class BeforePhase {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object phase) {
      Observer o = Hooks.observer;
      if (o != null) o.phaseBegin(phase);
    }
  }

  public static final class AfterPhase {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object phase) {
      Observer o = Hooks.observer;
      if (o != null) o.phaseEnd(phase);
    }
  }

  public static final class CompileUnits {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      Observer o = Hooks.observer;
      if (o != null) o.runEnd();
    }
  }

  public static final class Typed {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.This Object typer, @Advice.Argument(0) Object tree, @Advice.Argument(1) int mode,
                          @Advice.Argument(2) Object pt) {
      Observer o = Hooks.observer;
      if (o != null) o.typedEnter(typer, tree, mode, pt);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.This Object typer, @Advice.Argument(0) Object tree,
                     @Advice.Argument(1) int mode, @Advice.Argument(2) Object pt,
                     @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.typedExit(typer, tree, mode, pt, t == null ? res : null);
    }
  }

  public static final class Adapt {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.This Object typer, @Advice.Argument(0) Object tree, @Advice.Argument(1) int mode,
                          @Advice.Argument(2) Object pt) {
      Observer o = Hooks.observer;
      if (o != null) o.adaptEnter(typer, tree, mode, pt);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.This Object typer, @Advice.Argument(0) Object tree,
                     @Advice.Argument(1) int mode, @Advice.Argument(2) Object pt,
                     @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.adaptExit(typer, tree, mode, pt, t == null ? res : null);
    }
  }

  /** The static body of the trait method {@code LockingTypeCompleter.complete}: (completer, sym). */
  public static final class Complete {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.Argument(0) Object completer, @Advice.Argument(1) Object sym) {
      Observer o = Hooks.observer;
      if (o != null) o.completeEnter(completer, sym);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.Argument(0) Object completer, @Advice.Argument(1) Object sym,
                     @Advice.Thrown Throwable t) {
      if (o != null) o.completeExit(completer, sym, t == null);
    }
  }

  public static final class BestImplicit {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.This Object search) {
      Observer o = Hooks.observer;
      if (o != null) o.implicitSearchEnter(search);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.This Object search,
                     @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.implicitSearchExit(search, t == null ? res : null);
    }
  }

  public static final class TypedImplicit {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.This Object search, @Advice.Argument(0) Object info) {
      Observer o = Hooks.observer;
      if (o != null) o.candidateEnter(search, info);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.This Object search, @Advice.Argument(0) Object info,
                     @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object res, @Advice.Thrown Throwable t) {
      if (o != null) o.candidateExit(search, info, t == null ? res : null);
    }
  }

  public static final class FilteredInfo {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object reporter, @Advice.Argument(0) Object pos, @Advice.Argument(1) String msg,
                      @Advice.Argument(2) Object severity) {
      Observer o = Hooks.observer;
      if (o != null) o.report(reporter, pos, msg, severity);
    }
  }

  public static final class BufferedIssue {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object err, @Advice.Argument(1) Object context) {
      Observer o = Hooks.observer;
      if (o != null) o.bufferedReport(err, context);
    }
  }

  /** {@code copy.copyAttrs(from)}: {@code this} is the copy. */
  public static final class CopyAttrs {
    @Advice.OnMethodExit
    static void exit(@Advice.This Object copy, @Advice.Argument(0) Object from) {
      Observer o = Hooks.observer;
      if (o != null) o.copied(from, copy);
    }
  }

  // ---- JVM backend ----

  public static final class GenClass {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object cd, @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object cn) {
      Observer o = Hooks.observer;
      if (o != null) o.classNode(cd, cn);
    }
  }

  public static final class GenMirrorClass {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object sym, @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object cn) {
      Observer o = Hooks.observer;
      if (o != null) o.mirrorNode(sym, cn);
    }
  }

  public static final class GenDefDef {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object dd) { Hooks.genDefDef = dd; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { Hooks.genDefDef = null; }
  }

  public static final class InitJMethod {
    @Advice.OnMethodExit
    static void exit(@Advice.FieldValue("mnode") Object mnode) {
      Observer o = Hooks.observer;
      Object dd = Hooks.genDefDef;
      if (o != null && dd != null) o.genMethod(dd, mnode);
    }
  }

  /** {@code genStat(tree)} and {@code genLoadTo(tree, expectedType, dest)}. {@code mnode} is private to the superclass
   *  {@code PlainSkelBuilder}, so read it through its accessor. */
  public static final class GenLoad {
    @Advice.OnMethodEnter
    static Observer enter(@Advice.This Object self, @Advice.Argument(0) Object tree) {
      Observer o = Hooks.observer;
      if (o == null) return null;
      Object mnode = ((scala.tools.nsc.backend.jvm.BCodeSkelBuilder.PlainSkelBuilder) self).mnode();
      if (mnode == null) return null;
      o.genLoadEnter(tree, mnode);
      return o;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter Observer o, @Advice.This Object self, @Advice.Argument(0) Object tree) {
      if (o != null) o.genLoadExit(tree, ((scala.tools.nsc.backend.jvm.BCodeSkelBuilder.PlainSkelBuilder) self).mnode());
    }
  }

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

  /** {@code removalRound} (local to {@code methodOptimizations}): one round of the local optimiser. */
  public static final class RemovalRound {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(9) Object method) {
      Hooks.optRound++;
      Observer o = Hooks.observer;
      if (o != null && Hooks.optOwner != null) o.optimizerPass(Hooks.optOwner, method, "beforeMethodOpt");
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { Hooks.optRound--; }
  }

  /** A pass of {@code removalRound}: fires on exit, named by the method, unless nested in another pass. */
  public static final class OptRoundPass {
    @Advice.OnMethodEnter
    static boolean enter() { return Hooks.optPass++ == 0; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter boolean outer, @Advice.Origin("#m") String name, @Advice.Argument(0) Object method) {
      Hooks.optPass--;
      Observer o = Hooks.observer;
      if (outer && o != null && Hooks.optOwner != null && Hooks.optRound > 0) o.optimizerPass(Hooks.optOwner, method, name);
    }
  }

  /** A final pass of {@code methodOptimizations}, after the rounds (local variables, line numbers). */
  public static final class OptPostPass {
    @Advice.OnMethodEnter
    static boolean enter() { return Hooks.optPass++ == 0; }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter boolean outer, @Advice.Origin("#m") String name, @Advice.Argument(0) Object method) {
      Hooks.optPass--;
      Observer o = Hooks.observer;
      if (outer && o != null && Hooks.optOwner != null && Hooks.optRound == 0) o.optimizerPass(Hooks.optOwner, method, name);
    }
  }

  public static final class InlineCallsite {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object callsite, @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object map) {
      Observer o = Hooks.observer;
      if (o != null) o.inlined(callsite, map);
    }
  }

  public static final class SerializeClass {
    @Advice.OnMethodExit
    static void exit(@Advice.Argument(0) Object cn) {
      Observer o = Hooks.observer;
      if (o != null) o.classWritten(cn);
    }
  }
}
