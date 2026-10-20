// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.Symbol
import org.objectweb.asm.tree.MethodNode

/** The compiler APIs the recorder uses that differ between Scala versions. This one is for compilers
 *  with the JVM optimiser (`dotty.tools.backend.jvm.opt`, main / 3.8+); `../noopt` is for older ones.
 *  The server (../../server) picks the directory per version; `build.sh` uses this one.
 */
object Compat:
  /** The backend's `sym.javaSimpleName` (an extension in the backend before, a denotation method now). */
  def javaSimpleName(sym: Symbol)(using Context): String = sym.denot.name.mangledString

  /** The inliner or closure optimiser runs before the local optimiser. */
  def globalOptimizer(using Context): Boolean = ctx.settings.optInlineEnabled || ctx.settings.optClosureInvocations

  /** A `KnownCallsite`'s (callsite class, callee declaration class, callee). */
  def callsite(callsite: Object): (String, String, MethodNode) =
    val cs = callsite.asInstanceOf[dotty.tools.backend.jvm.opt.KnownCallsite]
    (cs.callsiteClass.internalName, cs.callee.calleeDeclarationClass.internalName, cs.callee.callee)
