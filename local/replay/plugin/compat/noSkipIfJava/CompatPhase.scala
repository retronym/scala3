// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import dotty.tools.dotc.core.Phases.Phase

/** RecordPhase's base, for compilers without `Phase.skipIfJava` (3.3.x). */
abstract class CompatPhase extends Phase
