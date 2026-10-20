// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.agent.api;

/**
 * Replaces the research-plugin gate in {@code Plugins.addPluginPhases} (agent option {@code research}).
 * Released compilers run research plugins only if the version string says SNAPSHOT, NIGHTLY or
 * nonbootstrapped: {@code Properties.researchPluginEnabled} (3.4+) or, in 3.3.x,
 * {@code Feature.isExperimentalEnabled}. The agent substitutes that one call, in that one method, with
 * these; nothing else that reads the flags (experimental language features) is affected.
 */
public final class Gate {
  private Gate() {}

  /** For {@code Properties$.MODULE$.researchPluginEnabled()}. */
  public static boolean researchPluginEnabled(Object properties) { return true; }

  /** For {@code Feature$.MODULE$.isExperimentalEnabled(ctx)} (3.3.x). */
  public static boolean isExperimentalEnabled(Object feature, Object ctx) { return true; }
}
