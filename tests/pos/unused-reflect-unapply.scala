//> using options -Wunused:all

// CheckUnused looks up dotty.tools.dotc.ast.Trees$Unapply reflectively. On a case-insensitive
// file system that finds Trees$UnApply.class and throws NoClassDefFoundError (wrong name).
import scala.quoted.*

def funOf(using Quotes)(t: quotes.reflect.Tree): Option[quotes.reflect.Term] =
  import quotes.reflect.*
  t match
    case Unapply(fun, _, _) => Some(fun)
    case _ => None
