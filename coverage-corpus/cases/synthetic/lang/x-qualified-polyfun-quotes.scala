//> using options -experimental
import scala.language.experimental.quotedPatternsWithPolymorphicFunctions
import scala.quoted.*
def impl(e: Expr[Any])(using Quotes): Expr[Int] = e match
  case '{ [A] => (x: A) => $b[A](x): A } => '{ 1 }
  case '{ (x: Int) => $f(x): Int } => '{ 2 }
  case _ => '{ 0 }
