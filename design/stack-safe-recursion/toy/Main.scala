package toy

import scala.util.Random
import Expr.*

object Main:
  type Typer = (Expr, Env) => T
  val typers: List[(String, Typer)] = List(
    "baseline" -> ((e, env) => Baseline.typed(e, None)(using env)),
    "spine"    -> ((e, env) => SpineTyper.typed(e, None)(using env)),
    "worklist" -> ((e, env) => WorklistTyper.typed(e, None)(using env)),
  )

  // Deep inputs, built bottom-up so construction itself is iterative
  def leftChain(n: Int): Expr = (1 to n).foldLeft(Num(0): Expr)((acc, i) => BinOp(acc, "+", Num(i)))
  def rightChain(n: Int): Expr = (n - 1 to 0 by -1).foldLeft(Num(n): Expr)((acc, i) => BinOp(Num(i), "+", acc))
  def elseLadder(n: Int): Expr =
    Let("x", Num(n / 2), (n - 1 to 0 by -1).foldLeft(Num(-1): Expr)((acc, i) =>
      If(BinOp(Var("x"), "<", Num(i)), Num(i), acc)))
  def letChain(n: Int): Expr =
    (n - 1 to 0 by -1).foldLeft(Var(s"x$n"): Expr)((acc, i) =>
      Let(s"x${i + 1}", if i == 0 then Num(0) else BinOp(Var(s"x$i"), "+", Num(1)), acc))
  def nestedCalls(n: Int): Expr = (1 to n).foldLeft(Num(0): Expr)((acc, i) => Call("max", List(Num(i), acc)))
  def chainWithError(n: Int): Expr =
    (1 to n).foldLeft(Num(0): Expr)((acc, i) => BinOp(acc, "+", if i == n / 2 then Str("oops") else Num(i)))

  def random(rnd: Random, depth: Int): Expr =
    if depth == 0 || rnd.nextInt(4) == 0 then
      rnd.nextInt(5) match
        case 0 => Num(rnd.nextInt(10))
        case 1 => Str("s")
        case 2 => Bool(rnd.nextBoolean())
        case _ => Var(if rnd.nextBoolean() then "x" else "y")
    else
      def r = random(rnd, depth - 1)
      rnd.nextInt(5) match
        case 0 => BinOp(r, if rnd.nextInt(3) == 0 then "<" else "+", r)
        case 1 => If(r, r, r)
        case 2 => Let(if rnd.nextBoolean() then "x" else "y", r, r)
        case 3 => Call(Seq("max", "str", "len", "nope")(rnd.nextInt(4)), List.fill(rnd.nextInt(3))(r))
        case _ => BinOp(r, "+", r)

  def differential(): Unit =
    val rnd = Random(42)
    val trees = Vector.fill(20_000)(random(rnd, 7))
    val env: Env = Map("x" -> Ty.IntT)
    val expected = trees.map(e => Show(Baseline.typed(e, None)(using env)))
    val errs = expected.count(_.contains("<error"))
    for th <- List(0, 1, 2, 5, 1000) do
      SpineTyper.threshold = th; WorklistTyper.threshold = th
      val spineOk = trees.indices.forall(i => Show(SpineTyper.typed(trees(i), None)(using env)) == expected(i))
      val wlOk = trees.indices.forall(i => Show(WorklistTyper.typed(trees(i), None)(using env)) == expected(i))
      println(f"differential threshold=$th%-4d spine=${if spineOk then "same" else "DIFF"} worklist=${if wlOk then "same" else "DIFF"}  (${trees.size} trees, $errs with errors)")
    SpineTyper.threshold = 200; WorklistTyper.threshold = 200

  /** Run on a thread with a fixed small stack so results don't depend on -Xss. */
  def onSmallStack[A](body: => A): Either[Throwable, A] =
    var res: Either[Throwable, A] = Left(AssertionError("not run"))
    val t = Thread(null, () => res = try Right(body) catch case ex: Throwable => Left(ex), "small", 512 * 1024)
    t.start(); t.join(); res

  def main(args: Array[String]): Unit =
    differential()
    val n = 100_000
    val inputs = List(
      "left chain  a+b+c+..."   -> leftChain(n),
      "right chain a+(b+(c+..))" -> rightChain(n),
      "else-if ladder"          -> elseLadder(n),
      "let chain"               -> letChain(n),
      "nested call args"        -> nestedCalls(n),
      "left chain, error mid"   -> chainWithError(n),
    )
    println(s"\nn = $n, 512 KiB stack")
    println(f"${"input"}%-26s ${typers.map(_._1).map(s => f"$s%-34s").mkString}")
    for (name, e) <- inputs do
      val cells = typers.map: (_, typer) =>
        val t0 = System.nanoTime()
        val r = onSmallStack(typer(e, Map.empty))
        val ms = (System.nanoTime() - t0) / 1_000_000
        r match
          case Right(t) =>
            val errs = Show(t).split("<error").length - 1
            f"ok ${t.ty} ${Show.size(t)}%d nodes${if errs > 0 then s" ${errs}err" else ""} ${ms}ms"
          case Left(_: StackOverflowError) => "StackOverflowError"
          case Left(ex) => ex.toString
      println(f"$name%-26s ${cells.map(c => f"$c%-34s").mkString}")
