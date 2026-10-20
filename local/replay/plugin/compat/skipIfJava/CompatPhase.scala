// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.Phase

/** RecordPhase's base, for compilers with `Phase.skipIfJava` (3.4+): record Java units too. */
abstract class CompatPhase extends Phase:
  override def skipIfJava(using Context) = false
