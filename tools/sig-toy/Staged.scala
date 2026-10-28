import Toy.*
import scala.util.Random

/** Staged completion: does it still meet wait-for cycles (so abort fires, and lending matters), what speedup, and are
 *  the errors of programs with genuine cycles the same in every schedule? */
object Staged:
  def main(args: Array[String]): Unit =
    val programs = args.headOption.map(_.toInt).getOrElse(100)
    val gens = List(
      "explicit-ish" -> Main.Gen(12, 3, 4, 0.1, 0.2, 0.0, true),
      "inferred" -> Main.Gen(12, 3, 4, 0.3, 0.6, 0.0, true),
      "inferred+coarse" -> Main.Gen(12, 3, 4, 0.3, 0.6, 0.3, true),
      "genuine cycles" -> Main.Gen(12, 3, 4, 0.3, 0.6, 0.0, false),
      "big, inferred" -> Main.Gen(40, 4, 6, 0.3, 0.6, 0.0, true),
    )
    for (gname, g) <- gens do
      val rnd = Random(42)
      val progs = Vector.fill(programs)(Main.program(g, rnd))
      println(s"== $gname")
      for strat <- List(Strategy.Abort, Strategy.Lend); workers <- List(4, 16) do
        var aborted, equal, different, deadlock = 0
        var speed = 0.0; var nspeed = 0
        var errSets = 0; var errVar = 0
        for p <- progs do
          val (sr, sst) = compile(p, Config(Strategy.Abort, 1, false, staged = true), 0)
          val spec = sr.toOption.get
          val results = (1 to 5).map: seed =>
            val (r, st) = compile(p, Config(strat, workers, false, staged = true), seed)
            r match
              case Left(Outcome.Aborted) => aborted += 1
              case Left(_) => deadlock += 1
              case Right(res) =>
                if res == spec then equal += 1 else different += 1
                speed += sst.makespan.toDouble / st.makespan; nspeed += 1
            r.toOption
          if spec.errors.nonEmpty then
            errSets += 1
            if results.flatten.map(_.errors).distinct.size > 1 then errVar += 1
        val n = programs * 5
        println(f"  $strat%-5s x$workers%-2d equal $equal/$n different $different aborted $aborted deadlock $deadlock | speedup ${speed / nspeed}%.1fx | programs with errors $errSets, error set varies by schedule in $errVar")
