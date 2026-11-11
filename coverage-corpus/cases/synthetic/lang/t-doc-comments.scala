//> using options -Xread-docs -Xcook-docs
/** A collection.
 *  @define Coll collection
 *  @define willNotTerminateInf Note: may not terminate for infinite-sized collections.
 *  @tparam A the element type
 */
trait Coll[A]:
  /** Selects the first element of this $Coll.
   *  $willNotTerminateInf
   *  @return the first element
   *  @throws NoSuchElementException if empty
   *  @see [[last]]
   *  @example {{{ Coll(1).head }}}
   */
  def head: A
  /** Builds a new $Coll by applying `f`.
   *  @usecase def map[B](f: A => B): Coll[B]
   *    @inheritdoc
   *  @param f the function
   *  @tparam B the result element type
   */
  def map[B](f: A => B): Coll[B]
  /** The last element. ''italic'' '''bold''' __underline__ ^sup^ ,,sub,, `mono` [[https://scala-lang.org link]]
   *
   *  - bullet one
   *  - bullet two
   *    1. nested
   *
   *  = Heading =
   *  @note a note
   *  @since 1.0
   *  @author someone
   *  @deprecated use head
   */
  def last: A

/** @define Coll list */
class MyList[A](xs: List[A]) extends Coll[A]:
  /** @inheritdoc
   *  Additional: uses $Coll. */
  def head: A = xs.head
  /** @inheritdoc */
  def map[B](f: A => B): MyList[B] = MyList(xs.map(f))
  def last: A = xs.last

/** Object docs with a [[MyList]] link and a $undefinedVariable. */
object Docs:
  /** @param x the x
   *  @return x
   */
  def id(x: Int): Int = x
  /** @constructor makes one
   *  @param n count */
  class WithCtor(n: Int)
