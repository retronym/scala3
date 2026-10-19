// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.agent;

import dotty.tools.dotc.core.Contexts;
import dotty.tools.dotc.core.Phases;
import replay.agent.api.Hooks;
import replay.agent.api.Observer;
import scala.collection.immutable.List;

/**
 * Replaces the one {@code phase.runOn(units)} call in {@code Run.runPhases} (via MemberSubstitution).
 * There is no method boundary to advise there: {@code Profiler.onPhase} and {@code Stats.trackTime}
 * are inline, and {@code Phase.runOn} itself is overridden and also called via {@code super}.
 *
 * This class names compiler types, so it must be loaded by the compiler's loader: it is in the
 * agent jar, which {@code -javaagent} puts on the system class path (where {@code java -cp} puts the
 * compiler). A compiler in another loader would need it injected there; not done.
 */
public final class RunHook {
  private RunHook() {}

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List runOn(Phases.Phase phase, List units, Contexts.Context ctx) {
    Observer o = Hooks.observer;
    if (o != null) o.phaseBegin(phase, ctx);
    List res;
    try {
      res = phase.runOn(units, ctx);
    } catch (Throwable t) {
      // Run catches InterruptedException and carries on with the old units; the fork's phaseEnd
      // runs after that catch. Here it runs just before it. Other throwables: no phaseEnd.
      if (o != null && t instanceof InterruptedException) o.phaseEnd(phase, units, ctx);
      throw t;
    }
    if (o != null) o.phaseEnd(phase, res, ctx);
    return res;
  }
}
