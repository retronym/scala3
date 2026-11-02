import Toy.*
import scala.util.Random

/** Is the result of a run without unstable reads unique, whatever the schedule and number of workers? Is needing an
 *  unstable read a property of the program, or of the schedule? */
object Canon:
  def main(args: Array[String]): Unit =
    val programs = args.headOption.map(_.toInt).getOrElse(100)
    val gens = List(
      "explicit-ish" -> Main.Gen(12, 3, 4, 0.1, 0.2, 0.0, true),
      "inferred" -> Main.Gen(12, 3, 4, 0.3, 0.6, 0.0, true),
      "inferred+coarse" -> Main.Gen(12, 3, 4, 0.3, 0.6, 0.3, true),
      "genuine cycles" -> Main.Gen(12, 3, 4, 0.3, 0.6, 0.0, false),
    )
    val runs = for workers <- List(2, 4, 8, 16); seed <- 1 to 6 yield (workers, seed)
    val staged = args.contains("staged")
    val lnp = args.contains("lookup")
    for (gname, g) <- gens do
      val rnd = Random(42)
      val progs = Vector.fill(programs)(Main.program(g, rnd))
      var alwaysStable, neverStable, mixed, uniqueStable, multiStable, serialAgrees, serialStable = 0
      var unstableDet, unstableVar, unstableEqSerial = 0
      for p <- progs do
        val rs = runs.map((n, s) => compile(p, Config(Strategy.Lend, n, headersFirst = false, staged = staged, lookupNeedsParents = lnp), s))
        val stable = rs.filter(_._2.unstable == 0).map(_._1.toOption.get)
        if stable.size == rs.size then alwaysStable += 1
        else if stable.isEmpty then neverStable += 1
        else mixed += 1
        if stable.nonEmpty then
          if stable.distinct.size == 1 then uniqueStable += 1 else multiStable += 1
        if stable.isEmpty then
          val all = rs.flatMap(_._1.toOption)
          if all.distinct.size == 1 then unstableDet += 1 else unstableVar += 1
        val (sr, sst) = compile(p, Config(Strategy.Abort, 1, headersFirst = false, staged = staged, lookupNeedsParents = lnp), 0)
        if sst.unstable == 0 then serialStable += 1
        if stable.nonEmpty && sr.toOption.get == stable.head then serialAgrees += 1
        if stable.isEmpty && rs.flatMap(_._1.toOption).forall(_ == sr.toOption.get) then unstableEqSerial += 1
      println(f"== $gname: $programs programs x ${runs.size} schedules (lend; 2..16 workers)")
      println(f"   stable in every schedule $alwaysStable, in none $neverStable, in some $mixed")
      println(f"   never stable: same result in every schedule $unstableDet, varies $unstableVar, always equal to serial $unstableEqSerial")
      println(f"   programs with a stable run: one result $uniqueStable, several $multiStable; serial (1 worker) stable $serialStable, equal to the stable parallel result $serialAgrees")
