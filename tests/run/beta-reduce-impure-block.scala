// Beta-reducing `{ stats; closure }.apply(args)` must evaluate `stats` before `args`.
class Ctx(val n: Int)

object Lib:
  inline def mapIt[T](xs: List[Int], inline wrap: List[Int] => Ctx ?=> T)(using Ctx): T =
    def loop(ys: List[Int])(using Ctx): T =
      ys match
        case y :: rest => loop(rest)(using Ctx(summon[Ctx].n + y))
        case Nil => wrap({ println("arg"); xs.reverse })
    loop(xs)

  inline def byName[T](inline f: (=> Int) => T): T =
    f({ println("byName"); 1 })

@main def Test =
  given Ctx = Ctx(0)
  println(Lib.mapIt(List(1, 2, 3), xs => c ?=> { println("body"); (xs, c.n) }))
  println({ println("stat"); (x: Int) => { println("body"); x } }.apply({ println("arg"); 7 }))
  println({ println("stat"); (x: Int) ?=> { println("body"); x } }.apply(using { println("arg"); 8 }))
  println(Lib.byName(x => { println("stat"); (y: Int) => { println("body"); x + x + y } }.apply({ println("arg"); 2 })))
