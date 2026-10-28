import Toy.*
import scala.util.Random

/** Random programs, each compiled serially (the specification) and by each strategy over several seeds. */
object Main:
  final case class Gen(units: Int, classesPerUnit: Int, membersPerClass: Int, parentArg: Double, inferred: Double,
                       coarse: Double, acyclicMembers: Boolean)

  def program(g: Gen, rnd: Random): Prog =
    val names = Vector.tabulate(g.units, g.classesPerUnit)((u, k) => s"C${u}_$k")
    val flat = names.flatten
    val memberCount = flat.map(n => n -> (1 + rnd.nextInt(g.membersPerClass))).toMap
    def rank(c: String, m: Int) = flat.indexOf(c) * 100 + m
    def randomRef(below: Int): Option[Ref] =
      val cands = for c <- flat; m <- 0 until memberCount(c) if !g.acyclicMembers || rank(c, m) < below yield Ref(c, s"m$m")
      if cands.isEmpty then None else Some(cands(rnd.nextInt(cands.size)))
    val units = names.zipWithIndex.map: (row, u) =>
      row.map: n =>
        val i = flat.indexOf(n)
        // an acyclic hierarchy: parents among the classes before this one, in any unit
        val parents = if i == 0 then Nil else List.fill(rnd.nextInt(3))(flat(rnd.nextInt(i))).distinct.map: p =>
          Parent(p, if rnd.nextDouble() < g.parentArg then randomRef(Int.MaxValue) else None)
        val mems = Vector.tabulate(memberCount(n)): m =>
          val rhs =
            if rnd.nextDouble() < g.inferred then randomRef(rank(n, m)).map(Rhs.Inferred(_)).getOrElse(Rhs.Declared("Int"))
            else Rhs.Declared(flat(rnd.nextInt(flat.size)))
          Mem(s"m$m", rhs)
        // some classes share a coarse root, as the classes of one TASTy file do
        val root = if rnd.nextDouble() < g.coarse then s"file${i / 3}" else n
        Cls(n, parents, mems, root)
    Prog(units)

  def main(args: Array[String]): Unit =
    val programs = args.headOption.map(_.toInt).getOrElse(200)
    val seeds = 5
    val gens = List(
      "explicit-ish" -> Gen(12, 3, 4, parentArg = 0.1, inferred = 0.2, coarse = 0.0, acyclicMembers = true),
      "inferred" -> Gen(12, 3, 4, parentArg = 0.3, inferred = 0.6, coarse = 0.0, acyclicMembers = true),
      "inferred+coarse" -> Gen(12, 3, 4, parentArg = 0.3, inferred = 0.6, coarse = 0.3, acyclicMembers = true),
      "genuine cycles" -> Gen(12, 3, 4, parentArg = 0.3, inferred = 0.6, coarse = 0.0, acyclicMembers = false),
    )
    val cfgs = List(
      "abort" -> Config(Strategy.Abort, 8, headersFirst = false),
      "lend" -> Config(Strategy.Lend, 8, headersFirst = false),
      "lend, headers first" -> Config(Strategy.Lend, 8, headersFirst = true),
    )
    val serial = Config(Strategy.Abort, 1, headersFirst = false)
    for (gname, g) <- gens do
      val rnd = Random(42)
      val progs = Vector.fill(programs)(program(g, rnd))
      // the specification: one worker, units in order, each unit's class headers and members
      val specRuns = progs.map(p => compile(p, serial, 0))
      val specs = specRuns.map(_._1.toOption.get)
      val serialMakespan = specRuns.map(_._2.makespan).sum.toDouble / programs
      val serialUnstable = specRuns.count(_._2.unstable > 0)
      // the same, units in reverse: does the order matter, and only where an unstable read was made?
      val reversed = progs.map(p => compile(p, serial, 0, _.reverse))
      val revDiff = progs.indices.count(i => reversed(i)._1.toOption.get != specs(i))
      val revDiffStable = progs.indices.count(i => reversed(i)._1.toOption.get != specs(i) && specRuns(i)._2.unstable == 0 && reversed(i)._2.unstable == 0)
      println(f"== $gname: $programs programs; serially ${specs.count(_.errors.nonEmpty)} with cyclic errors, $serialUnstable with an unstable read; reversed unit order differs in $revDiff (of them with no unstable read either way: $revDiffStable)")
      for (cname, cfg) <- cfgs do
        var counts = Map.empty[Outcome, Int].withDefaultValue(0)
        var makespan = 0L
        var completed = 0
        var stableRuns = 0
        var stableDifferent = 0
        for (p, spec) <- progs.zip(specs); seed <- 1 to seeds do
          val (r, st) = compile(p, cfg, seed)
          val o = r match
            case Left(o) => o
            case Right(res) => if res == spec then Outcome.Equal else Outcome.Different
          counts = counts.updated(o, counts(o) + 1)
          if r.isRight then
            completed += 1
            makespan += st.makespan
            if st.unstable == 0 then
              stableRuns += 1
              if o == Outcome.Different then stableDifferent += 1
        val n = programs * seeds
        def pct(o: Outcome) = f"${100.0 * counts(o) / n}%5.1f%%"
        val speedup = if completed == 0 then 0.0 else serialMakespan / (makespan.toDouble / completed)
        println(f"  $cname%-20s equal ${pct(Outcome.Equal)} different ${pct(Outcome.Different)} aborted ${pct(Outcome.Aborted)} deadlock ${pct(Outcome.Deadlock)} | no unstable read: ${100.0 * stableRuns / n}%5.1f%% of runs, of them different: $stableDifferent | speedup ${speedup}%.1fx on ${cfg.workers}")
