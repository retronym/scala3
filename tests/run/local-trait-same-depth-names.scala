// Same-named local traits and classes at the same depth under the same class
// need distinct expanded names, as a class can inherit from both of them.

// a local class with the name of a member class that it extends
class Lib(val name: String) {
  class C { def member = name + toString.take(0) }
  def f(other: Lib) = {
    class C extends other.C { def local = Lib.this.name }
    val c = new C
    println(s"${c.member} ${c.local}")
  }
}

// the same with traits
class Lib2(val name: String) {
  trait C { def member = name + toString.take(0) }
  def f(other: Lib2) = {
    trait C extends other.C { def local = Lib2.this.name }
    val c = new C {}
    println(s"${c.member} ${c.local}")
  }
}

object Test {
  def outerAccessors(): Unit = {
    trait T { def go = "outer" + toString.take(0) }
    class B extends T
    locally(())
    {
      trait T { def gi = "inner" + toString.take(0) }
      class C extends B with T
      val c = new C
      println(s"${c.go} ${c.gi}")
    }
  }

  def outerAccessorsInOneClass(): Unit = {
    trait T { def go = "outer" + toString.take(0) }
    type OT = T
    {
      trait T { def gi = "inner" + toString.take(0) }
      class C extends OT with T
      val c = new C
      println(s"${c.go} ${c.gi}")
    }
  }

  def lazyValAndVar(): Unit = {
    trait T { private lazy val x = "outer"; private var y = "outer"; def go = { y += "!"; (() => x + " " + y)() } }
    class B extends T
    locally(())
    {
      trait T { private lazy val x = "inner"; private var y = "inner"; def gi = { y += "?"; (() => x + " " + y)() } }
      class C extends B with T
      val c = new C
      println(c.go)
      println(c.gi)
    }
  }

  def methodCalledFromNestedClass(): Unit = {
    trait T { private def x = "outer"; class I { def get = x }; def go = new I().get }
    class B extends T
    locally(())
    {
      trait T { private def x = "inner"; class J { def get = x }; def gi = new J().get }
      class C extends B with T
      val c = new C
      println(c.go)
      println(c.gi)
    }
  }

  abstract class Base { def f: String = "base" }

  def superAccessors(): Unit = {
    trait T extends Base { override def f = "outer(" + super.f + ")" }
    class B extends Base with T
    locally(())
    {
      trait T extends Base { override def f = "inner(" + super.f + ")" }
      class C extends B with T
      println(new C().f)
    }
  }

  abstract class Abstract { def f: String }
  class Impl extends Abstract { def f = "impl" }

  def abstractOverride(): Unit = {
    trait T extends Abstract { abstract override def f = "outer(" + super.f + ")" }
    class B extends Impl with T
    locally(())
    {
      trait T extends Abstract { abstract override def f = "inner(" + super.f + ")" }
      class C extends B with T
      println(new C().f)
    }
  }

  trait Values { def x: String; def y: String }

  // the setters of concrete trait vals are named after the trait
  def traitSetters(): Unit = {
    trait T extends Values { val x = "outer x"; val y = "outer y" }
    class B extends T
    locally(())
    {
      trait T extends Values { override val x = "inner x"; override val y = "inner y" }
      class C extends B with T
      val c: Values = new C
      println(s"${c.x}, ${c.y}")
    }
  }

  def traitParameters(): Unit = {
    trait T(x: String) { def go = x }
    class B extends T("outer")
    locally(())
    {
      trait T(x: String) { def gi = x }
      class C extends B with T("inner")
      val c = new C
      println(s"${c.go} ${c.gi}")
    }
  }

  def privateObjects(): Unit = {
    trait T { private object o { val x = "outer" }; def go = (() => o.x)() }
    class B extends T
    locally(())
    {
      trait T { private object o { val x = "inner" }; def gi = (() => o.x)() }
      class C extends B with T
      val c = new C
      println(s"${c.go} ${c.gi}")
    }
  }

  def inlineAccessors(): Unit = {
    trait T { private val x = "outer"; inline def x1 = x; def go = x1 }
    class B extends T
    locally(())
    {
      trait T { private val x = "inner"; inline def x2 = x; def gi = x2 }
      class C extends B with T
      val c = new C
      println(s"${c.go} ${c.gi}")
    }
  }

  // the enclosing classes of the two traits are local classes with the same name
  def enclosingLocalClasses(): Unit = {
    class O { trait T { private var x = "outer"; def go = x }; class B extends T }
    val o = new O
    locally(())
    {
      class O { trait T { private var x = "inner"; def gi = x }; class C extends o.B with T }
      val o2 = new O
      val c = new o2.C
      println(s"${c.go} ${c.gi}")
    }
  }

  // same-named local classes, one extending the other
  def classes(): Unit = {
    class C { private def x = "outer"; class I { def get = x + toString.take(0) }; def go = new I().get }
    type OC = C
    locally(())
    {
      class C extends OC { private def x = "inner"; class J { def get = x + toString.take(0) }; def gi = new J().get }
      val c = new C
      println(s"${c.go} ${c.gi}")
    }
  }

  // same depth, with the inner trait in a method rather than a block
  def nestedMethods(): Unit = {
    def first(): Unit = {
      trait T { private var x = "outer"; def go = x + toString.take(0) }
      class B extends T
      def second(): Unit = ()
      {
        trait T { private var x = "inner"; def gi = x + toString.take(0) }
        class C extends B with T
        val c = new C
        println(s"${c.go} ${c.gi}")
      }
    }
    first()
  }

  class InClass(tag: String) {
    def test(): Unit = {
      trait T { def go = "outer " + tag }
      class B extends T
      locally(())
      {
        trait T { def gi = "inner " + tag }
        class C extends B with T
        val c = new C
        println(s"${c.go}, ${c.gi}")
      }
    }
  }

  def sameNamedEnclosingClasses(): Unit = {
    class A(val s: String) { trait T { def go = s + toString.take(0) } }
    val a1 = new A("first")
    locally(())
    {
      class A(val s2: String) { trait T { def gi = s2 + toString.take(0) }; class C extends a1.T with T }
      val a2 = new A("second")
      val c = new a2.C
      println(s"${c.go} ${c.gi}")
    }
  }

  // names computed before and after LambdaLift must agree
  def capturingPrivateVar(): Unit = {
    val outer = "outer".toString
    trait T { private var x = "x"; class I { def get = { x += "!"; x + " " + outer } }; def go = new I().get }
    class B extends T
    locally(())
    {
      val inner = "inner".toString
      trait T { private var x = "y"; class J { def get = { x += "?"; x + " " + inner } }; def gi = new J().get }
      class C extends B with T
      val c = new C
      println(s"${c.go}, ${c.gi}")
    }
  }

  def main(args: Array[String]): Unit = {
    outerAccessors()
    outerAccessorsInOneClass()
    lazyValAndVar()
    methodCalledFromNestedClass()
    superAccessors()
    abstractOverride()
    traitSetters()
    traitParameters()
    privateObjects()
    inlineAccessors()
    enclosingLocalClasses()
    classes()
    nestedMethods()
    new InClass("tag").test()
    new Lib("this").f(new Lib("other"))
    new Lib2("this").f(new Lib2("other"))
    sameNamedEnclosingClasses()
    capturingPrivateVar()
  }
}
