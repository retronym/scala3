// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.nsc.api;

import java.util.function.Function;

/**
 * Registration point shared by the nsc agent's inlined advice and the plugin (copied from
 * ../../agent's {@code replay.agent.api.Hooks}, for nsc). On the bootstrap class path (the agent jar's
 * {@code Boot-Class-Path}), so advice inlined into compiler classes and the plugin (a child loader of the
 * compiler's) resolve the same class. nsc's typer is single-threaded; the backend hooks need
 * {@code -Ybackend-parallelism:1} (the default).
 */
public final class Hooks {
  private Hooks() {}

  /** Set by the plugin; null means every hook is a static read and a branch. */
  public static Observer observer;

  /** The DefDef of the running {@code genDefDef}, for {@code initJMethod}'s advice (genDefDef does not nest). */
  public static Object genDefDef;
  /** {@code ownerClassName} of the innermost {@code LocalOpt.methodOptimizations}; {@code traceIfChanged} lacks it. */
  public static String optOwner;

  /** Installed by the agent: forces loading of every target class in the given loader and returns the
   *  join-point table, or throws if a site was not found. */
  public static Function<ClassLoader, String> verifier;

  public static String verify(ClassLoader compilerLoader) {
    Function<ClassLoader, String> v = verifier;
    if (v == null) throw new IllegalStateException("replay nsc agent not installed (no -javaagent?)");
    return v.apply(compilerLoader);
  }
}
