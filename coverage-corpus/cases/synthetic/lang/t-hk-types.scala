trait Functor[F[_]]:
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]
trait Applicative[F[_]] extends Functor[F]:
  def pure[A](a: A): F[A]
  def ap[A, B](ff: F[A => B])(fa: F[A]): F[B]
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B] = ap(pure(f))(fa)
trait Traverse[T[_]] extends Functor[T]:
  def traverse[G[_]: Applicative, A, B](ta: T[A])(f: A => G[B]): G[T[B]]
given Applicative[Option] with
  def pure[A](a: A) = Some(a)
  def ap[A, B](ff: Option[A => B])(fa: Option[A]) = ff.flatMap(fa.map)
given Traverse[List] with
  extension [A](fa: List[A]) def fmap[B](f: A => B) = fa.map(f)
  def traverse[G[_], A, B](ta: List[A])(f: A => G[B])(using G: Applicative[G]): G[List[B]] =
    ta.foldRight(G.pure(List.empty[B]))((a, acc) => G.ap(G.ap(G.pure((b: B) => (bs: List[B]) => b :: bs))(f(a)))(acc))
type Const[C] = [X] =>> C
type Id[X] = X
given Applicative[Id] with
  def pure[A](a: A) = a
  def ap[A, B](ff: A => B)(fa: A) = ff(fa)
trait ~>[F[_], G[_]] { def apply[A](fa: F[A]): G[A] }
val headOpt: List ~> Option = new (List ~> Option) { def apply[A](fa: List[A]) = fa.headOption }
val polyNat: [A] => List[A] => Option[A] = [A] => (l: List[A]) => l.headOption
case class Fix[F[_]](unfix: F[Fix[F]])
enum ListF[+A, +R]:
  case NilF
  case ConsF(h: A, t: R)
type Kinded[F[_[_]]] = F[List]
trait HK3[F[_, _, _]]
type Curried = [A] =>> [B] =>> Either[A, B]
type Partial[A] = Curried[A]
def useCurried: Partial[String][Int] = Right(1)
type Lam = [X, Y <: X] =>> Map[X, Y]
type Bounded[F[X] <: Iterable[X]] = F[Int]
def bnd: Bounded[List] = List(1)
trait Monad[M[_]]:
  def flatMap[A, B](m: M[A])(f: A => M[B]): M[B]
type StateM[S] = [A] =>> S => (S, A)
given [S]: Monad[StateM[S]] with
  def flatMap[A, B](m: S => (S, A))(f: A => S => (S, B)) = s => { val (s1, a) = m(s); f(a)(s1) }
def test =
  val r = summon[Traverse[List]].traverse(List(1, 2))(i => Option(i))
  val r2 = summon[Traverse[List]].traverse[Id, Int, Int](List(1, 2))(_ + 1)
  (r, r2, headOpt(List(1)), polyNat(List("a")), Fix[[R] =>> ListF[Int, R]](ListF.NilF), useCurried, bnd,
   summon[Monad[StateM[Int]]].flatMap((s: Int) => (s, 1))(a => s => (s, a + 1))(0))
