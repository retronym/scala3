trait Graph:
  type Node
  type Edge = (Node, Node)
  def nodes: List[Node]
  def edges: List[Edge]
  class Path(val steps: List[Node]):
    def +(n: Node): Path = Path(steps :+ n)
object G extends Graph:
  type Node = Int
  def nodes = List(1, 2)
  def edges = List((1, 2))
def firstNode(g: Graph): g.Node = g.nodes.head
def paths(g: Graph)(n: g.Node): g.Path = new g.Path(List(n))
class Outer:
  class Inner
  def mk = new Inner
  def take(i: Inner) = i
  type T <: AnyRef
def proj(i: Outer#Inner) = i
trait Key { type Value; def default: Value }
object IntKey extends Key { type Value = Int; def default = 0 }
object StrKey extends Key { type Value = String; def default = "" }
class Store:
  private var m = Map.empty[Key, Any]
  def get(k: Key): k.Value = m.getOrElse(k, k.default).asInstanceOf[k.Value]
  def put(k: Key)(v: k.Value): Unit = m += (k -> v)
trait Cake { self =>
  trait Component { def outer: self.type = self }
}
abstract class Abs:
  type A
  val a: A
  def get: A = a
class AbsInt extends Abs:
  type A = Int
  val a = 1
def deps(x: Abs): x.A = x.get
def deps2(x: Abs)(y: x.A): List[x.A] = List(y)
def singletonOps(o: Outer): o.type = o
def test =
  val o = new Outer
  val i: o.Inner = o.mk
  o.take(i)
  proj(i)
  val s = Store()
  s.put(IntKey)(3)
  val v: Int = s.get(IntKey)
  val n: G.Node = firstNode(G)
  val p = paths(G)(1) + 2
  val ai = new AbsInt
  val x: Int = deps(ai)
  deps2(ai)(1)
  singletonOps(o)
  val c = new Cake {}
  val comp = new c.Component {}
  (v, n, p.steps, x, comp.outer)
