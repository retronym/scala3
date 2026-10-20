// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.Symbol
import org.objectweb.asm.tree.MethodNode

/** `../opt/Compat.scala` for compilers without the JVM optimiser (3.3.x to 3.7.x). The server rewrites
 *  `org.objectweb.asm` to `scala.tools.asm` for compilers that use the shaded scala-asm. */
object Compat:
  def javaSimpleName(sym: Symbol)(using Context): String = sym.denot.name.mangledString
  def globalOptimizer(using Context): Boolean = false
  def callsite(callsite: Object): (String, String, MethodNode) =
    throw UnsupportedOperationException("no JVM optimiser in this compiler")
