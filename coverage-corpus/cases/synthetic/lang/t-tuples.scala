def test =
  val t = (1, "a", true)
  val t2 = 0 *: t
  val t3 = t ++ (2.0, 'c')
  val h = t.head; val tl = t.tail; val l = t.last; val in = t.init
  val sz = t.size
  val m = t.map[[X] =>> List[X]]([X] => (x: X) => List(x))
  val z = t.zip((1, 2, 3))
  val tk = t.take(2); val dr = t.drop(1); val sp = t.splitAt(1)
  val rev = t.reverse
  val e: EmptyTuple = EmptyTuple
  val big = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
  val bh = big.head; val b23 = big(23); val bs = big.size; val bt = big.tail; val bc = big ++ big
  val arr = t.toArray; val lst = t.toList; val it = t.productIterator.toList
  val fromArr = Tuple.fromArray(Array(1, 2))
  val ap = t(1)
  val (x, y, w) = t
  val nested = ((1, 2), (3, (4, 5)))
  val ((n1, n2), (n3, (n4, n5))) = nested
  type M = Tuple.Map[(Int, String), Option]
  val mm: M = (Some(1), Some("s"))
  type C = Tuple.Concat[(Int, String), (Boolean, Char)]
  val cc: C = (1, "s", true, 'c')
  type U = Tuple.Union[(Int, String)]
  val uu: U = "s"
  type F = Tuple.Filter[(1, "a", 2), [X] =>> X match { case Int => true; case _ => false }]
  type E = Tuple.Elem[(Int, String), 1]
  val ee: E = "s"
  val nt = (name = "x", age = 1)
  val ntn = nt.name; val nta = nt.age
  val ntt: (String, Int) = nt.toTuple
  val nn = nt(0); val aa = nt(1)
  def named(p: (x: Int, y: Int)) = p.x + p.y
  val np = named((x = 1, y = 2))
  val nts = List((a = 1, b = "x")).map(_.a)
  type NT = NamedTuple.NamedTuple[("a", "b"), (Int, String)]
  val ntx: NT = (a = 1, b = "s")
  type Names = NamedTuple.Names[NT]
  type DropN = NamedTuple.Drop[NT, 1]
  case class City(name: String, pop: Int)
  type CF = NamedTuple.From[City]
  val cf: CF = (name = "x", pop = 1)
  t.toString + t2 + t3 + h + tl + l + in + sz + m + z + tk + dr + sp + rev + e + bh + b23 + bs + bt + bc.size + arr.toList +
    lst + it + fromArr + ap + x + y + w + n1 + n2 + n3 + n4 + n5 + mm + cc + uu + ee + ntn + nta + ntt + nn + aa + np + nts + ntx + cf
