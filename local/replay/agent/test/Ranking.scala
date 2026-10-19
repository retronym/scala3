// LLM-assisted (Claude), see LLM_POLICY.md. Exercises implicit ranking for the agent's extra hooks.
trait Tc[A]
object Lib:
  given general[A]: Tc[A] = new Tc[A] {}
  given specific: Tc[Int] = new Tc[Int] {}

object Amb:
  given a1: Tc[String] = new Tc[String] {}
  given a2: Tc[String] = new Tc[String] {}

object Use:
  import Lib.given
  def pick = summon[Tc[Int]]          // specific wins, general pruned (lowerPriorityThan)
  def ambiguous =
    import Amb.given
    summon[Tc[String]]                 // a1 vs a2 ambiguous at this level; healed? no: error-free via general? see stream
  def tentative(xs: List[Int]) = xs.foldLeft(0)(_ + _)
