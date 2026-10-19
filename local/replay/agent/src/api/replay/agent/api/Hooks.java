// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.agent.api;

import java.util.function.Function;

/**
 * Registration point shared by the agent's inlined advice and the plugin.
 *
 * On the bootstrap class path (the agent jar's {@code Boot-Class-Path}), so the advice inlined
 * into compiler classes and the plugin (a child loader of the compiler's) resolve the same class
 * whatever loader the compiler runs in. Fields are plain statics: the advice reads them on every
 * hooked call, and dotc's typer is single-threaded ({@code ContextBase.checkSingleThreaded}).
 */
public final class Hooks {
  private Hooks() {}

  /** Set by the plugin; null means every hook is a static read and a branch. */
  public static Observer observer;

  // Arm/consume markers for sites where the fork hooks a call inside a method rather than the
  // method itself: the outer method's advice arms, the first entry of the inner method consumes.
  // A direct (unarmed) call of the inner method, e.g. adapt1's own recursion, fires nothing.

  /** Armed by {@code Typer.adapt}, consumed by {@code Typer.adapt1}. */
  public static boolean armAdapt;
  /** Armed by {@code Namer.Completer.complete}, consumed by {@code completeInCreationContext}. */
  public static boolean armComplete;
  /** The Context of the innermost running {@code complete}; {@code completeInCreationContext} has none. */
  public static Object completeCtx;
  /** Armed by {@code ImplicitSearch.tryImplicit}, consumed by {@code Implicits.typedImplicit}. */
  public static boolean armCandidate;

  /** Armed by {@code Typer.tryEither}, consumed by {@code FreshContext.setNewTyperState}. */
  public static boolean armTyperState;

  /** Set by {@code genDefDef}'s advice for {@code initJMethod}'s (genDefDef does not nest). */
  public static Object genDefDef;
  /** {@code ownerClassName} of the innermost {@code LocalOptimizer.methodOptimizations}; {@code traceIfChanged} lacks it. */
  public static String optOwner;
  /** Armed by {@code GlobalOptimizer}'s {@code doInline} with its target method, consumed by {@code inlineCallsite}. */
  public static Object armInline;

  /** Installed by the agent: forces loading of every target class in the given loader and
   *  returns the join-point table, or throws if a site was not found. */
  public static Function<ClassLoader, String> verifier;

  public static String verify(ClassLoader compilerLoader) {
    Function<ClassLoader, String> v = verifier;
    if (v == null) throw new IllegalStateException("replay agent not installed (no -javaagent?)");
    return v.apply(compilerLoader);
  }
}
