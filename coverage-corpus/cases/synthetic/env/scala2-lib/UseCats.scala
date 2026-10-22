//> using dep org.typelevel:cats-core_2.13:2.13.0
// Uses a Scala 2.13-compiled library: its signatures come from Scala 2 pickles (Scala2Unpickler).
import cats.*
import cats.data.*
import cats.syntax.all.*

object UseCats:
  final case class Config(port: Int, host: String)

  def validate(port: Int, host: String): ValidatedNel[String, Config] =
    (if port > 0 then port.validNel else "bad port".invalidNel,
     if host.nonEmpty then host.validNel else "bad host".invalidNel).mapN(Config.apply)

  def program[F[_]: Monad](read: F[String]): F[Int] =
    for
      a <- read
      b <- read.map(_.length)
    yield a.length + b

  def traverseAll[F[_]: Traverse, G[_]: Applicative, A, B](fa: F[A])(f: A => G[B]): G[F[B]] = fa.traverse(f)

  def kleisli: Kleisli[Option, Int, String] = Kleisli((i: Int) => Option(i.toString)).andThen(s => Option(s + "!"))

  def stateful: State[Int, Int] =
    for
      x <- State.get[Int]
      _ <- State.set(x + 1)
      y <- State.inspect[Int, Int](_ * 2)
    yield y

  def main(args: Array[String]): Unit =
    println(validate(80, "h").product(validate(-1, "")).leftMap(_.toList.mkString))
    println(program[Option](Some("ab")) |+| program[Id]("abc").some)
    println(traverseAll(List(1, 2, 3))(i => Either.cond(i > 0, i, s"neg $i")))
    println(NonEmptyList.of(1, 2, 3).reduceLeft(_ + _) + NonEmptyList.one(1).head)
    println(Eval.later(1).flatMap(i => Eval.now(i + 1)).value)
    println(kleisli.run(3) |+| Option("x"))
    println(stateful.run(1).value)
    println(Monoid[Map[String, Int]].combineAll(List(Map("a" -> 1), Map("a" -> 2))))
    println(Show[Int].show(1) + 1.show + List(1, 2).combineAll + Option(1).orEmpty)
    val w: Writer[List[String], Int] = Writer(List("log"), 1).flatMap(i => Writer.tell(List("more")).as(i + 1))
    println(w.run)
    val e: EitherT[Option, String, Int] = EitherT.rightT[Option, String](1).semiflatMap(i => Option(i + 1))
    println(e.value)
    val ior: Ior[String, Int] = Ior.both("w", 1)
    ior match
      case Ior.Left(a) => println(a)
      case Ior.Right(b) => println(b)
      case Ior.Both(a, b) => println(a + b)
    val v: Validated[String, Int] = Validated.valid(1)
    v match
      case Validated.Valid(a) => println(a)
      case Validated.Invalid(e) => println(e)
    println(Functor[List].compose[Option].map(List(Some(1), None))(_ + 1))
    println(Semigroup[Int].combineN(2, 3) + Order[Int].compare(1, 2) + Eq[String].eqv("a", "a").hashCode)
    println((1, "a").bimap(_ + 1, _ + "b").toString + List(1, 2).foldMap(_.toString))
    println(Chain(1, 2, 3).toList ++ NonEmptyChain(1).toChain.toList)
    println(Const[String, Int]("c").retag[Boolean].getConst)
    println(OptionT.liftF(List(1, 2)).map(_ + 1).value)
    println(List(Option(1), None).sequence.void)
    // deprecated members: forces Scala 2 annotation arguments (Scala2Unpickler.readTree)
    import scala.annotation.nowarn
    val dep = (Functor[List].fmap(List(1))(_ + 1): @nowarn) ++ (List(1, 2).foldMapA(i => Option(i)).toList: @nowarn)
    println(dep)
    println(Applicative[Option].map2(Some(1), Some(2))(_ + _).toString + Option(1).tupleRight("x").toString)
