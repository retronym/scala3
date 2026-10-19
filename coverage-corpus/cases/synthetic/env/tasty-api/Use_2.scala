// Stage 2: uses the API compiled in stage 1 (read back from TASTy).
import api.*
import api.Opaques.*
import api.Exts.*
import api.given
import scala.language.implicitConversions

object Test:
  def main(args: Array[String]): Unit =
    val m = Meters(1.0) + Meters(2.0)
    val f = Flags(1) | Flags(2)
    assert(f.has(Flags(1)))
    val i: Id[Int] = id(1)
    println(Color.Red.hex + Color.parse("Green") + Color.valueOf("Blue").ordinal + Color.fromOrdinal(0))
    val t: Tree[Int] = Tree.Node(Tree.Leaf(1), Tree.Empty)
    println(t.size + JEnum.A.compareTo(JEnum.B))
    println(eval(If(BoolLit(true), Pair(IntLit(1), BoolLit(false)), Pair(IntLit(2), BoolLit(true)))))
    import Show.given
    println(List(1, 2).shown + Option("x").shown + Person("p", 3).shown + (Circle(1): Shape).shown)
    println(List(1, 2).map(_ + 1).flatMap(x => List(x, x)))
    val e: Either[String, Int] = summon[Monad[EitherT[String]]].pure(1)
    val el: Char = firstElem("abc")
    val c: Concat[(Int, String), Tuple1[Boolean]] = (1, "a", true)
    val entry = new Entry { type Key = Int; type Value = String; def key = 1; def value = "v" }
    val k: entry.Key = keyOf(entry)
    val k2: Int = depFun(entry)
    println(polyFun(1) ++ polyFun[String]("s"))
    val rec: Rec = new { def name = "n"; val age = 1 }
    import scala.reflect.Selectable.reflectiveSelectable
    println(rec.name + rec.age)
    println(describe(new HasName with HasAge { def name = "n"; def age = 1 }) + widen(1) + widen("s"))
    val impl = Impl()
    println(impl.defaults()() + impl.defaults(b = "c")(false) + impl.varargs(1, 2, 3) + impl.varargs(Seq(1, 2)*))
    println(impl.byName({ println("x"); 1 }) + impl.overloaded(1) + impl.overloaded("s") + impl.lz)
    println(impl.inl(1) + impl.loop(10) + impl.Inner.x + new impl.InnerClass(1).outer.tag + impl.local)
    impl.anon.run(); impl.sam.run()
    val s: String = impl.tinl(1).toString
    println(impl ++ impl)
    impl.concreteVar += 1
    println(impl.poly[Any, Int, AnyVal](1))
    val im: impl.Abstract = 1
    println(Impl.staticField)
    impl match { case Impl(tag) => println(tag) }
    println(new ValueClass(1).double.underlying)
    val cc = CC(1)(2)
    println(cc.copy(a = 2)(extra = 3).extra + cc.rest.size + CC.unapply(cc)._1)
    println(3.squared + ("abc": Int) + (true: Int))
    println("a b c".words.toString + "a b".word(1))
    given Ord[Int] = (a, b) => a - b
    println(List(3, 1, 2).sortedWith)
    val cp = Copier()
    cp.print("x"); cp.flushIt(); cp.scan()
    println(power(2.0, 10) + typeName[Int] + typeName[String] + typeName[Boolean])
    val one: Int = choose(true)
    val oneS: String = choose(false)
    println(assertPositive(3))
    println(showExpr(1 + 2) + fieldNames[Person] + unrolledSum(1, 2, 3))
    val n = nt
    println(n.name + n.age + bigTuple(23) + bigTuple.size)
    println(withCtx(summon[Show[Int]].show(1)) + f22(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23))
    println(singleton(42) + lit + valueOf3)
    println(new D1().m)
    val jl = new JavaLike
    println(jl.size() + jl.get(0) + jl.compareTo(jl) + jl.iterator().next())
    println(old())
    val ann = classOf[Impl].getAnnotations
    summon[CanEqual[Color, Color]]
    summon[scala.deriving.Mirror.ProductOf[Person]].fromProduct(("x", 1))
    summon[scala.deriving.Mirror.SumOf[Shape]].ordinal(Circle(1))
