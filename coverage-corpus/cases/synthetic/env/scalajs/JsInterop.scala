//> using options -scalajs
//> using dep org.scala-js:scalajs-library_2.13:1.22.0
// Scala.js: JS types, exports and dynamic calls (PrepJSInterop, ExplicitJSClasses, the IR backend).
package sjs

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.|

@js.native @JSGlobal("Date")
class JSDate(ms: Double) extends js.Object:
  def getTime(): Double = js.native
  @JSName("toISOString") def iso(): String = js.native

@js.native @JSGlobal
object Math extends js.Object:
  def max(xs: Double*): Double = js.native

@js.native
trait Point extends js.Object:
  val x: Double = js.native
  var y: Double = js.native
  @JSBracketAccess def apply(k: String): js.Any = js.native

@js.native @JSImport("fs", "readFileSync")
def readFile(path: String, enc: String): String = js.native

class ScalaJSClass(val n: Int) extends js.Object:
  def plus(m: Int): Int = n + m
  var mutable: String = "m"
  @JSName("renamed") def orig(): Int = 1
  def defaults(a: Int = 1, b: String = "b"): String = a.toString + b
  def varargs(xs: Int*): Int = xs.sum
  override def toString(): String = s"SJC($n)"
  val nested = new js.Object { val inner = 1 }

trait JSTrait extends js.Object:
  val name: String
  val opt: js.UndefOr[Int] = js.undefined

object Holder:
  class InnerJS(val k: Int) extends js.Object
  def mk = new InnerJS(1)

@JSExportTopLevel("Exported")
class Exported(val v: Int):
  @JSExport def doubled: Int = v * 2
  @JSExport("tripled") def triple(): Int = v * 3
  @JSExport def overloaded(x: Int): Int = x
  @JSExport def overloaded(x: String): String = x

@JSExportTopLevel("exportedFun")
def exportedFun(a: Int, b: String = "d"): String = a.toString + b

@JSExportAll
class ExportAll(val a: Int):
  def b: Int = a

object Main:
  @JSExportTopLevel("main") def main(): Unit =
    val d = new JSDate(0)
    println(d.getTime() + d.iso() + Math.max(1, 2, 3))
    val p = js.Dynamic.literal(x = 1.0, y = 2.0).asInstanceOf[Point]
    p.y = 3
    println(p.x + p.y + p("x").toString)
    val dyn = js.Dynamic.global.console
    dyn.log("hi", 1, true)
    val arr = js.Array(1, 2, 3)
    arr.push(4)
    println(arr.map(_ * 2).filter(_ > 2).length + arr(0))
    val dict = js.Dictionary("a" -> 1)
    dict("b") = 2
    val f: js.Function1[Int, Int] = (x: Int) => x + 1
    val f2: js.Function2[Int, Int, Int] = _ + _
    val thisF: js.ThisFunction0[js.Any, Int] = (t: js.Any) => 1
    println(f(1) + f2(1, 2))
    val u: Int | String = 1
    val ud: js.UndefOr[String] = js.undefined
    println(js.typeOf(u) + js.isUndefined(ud))
    val sjc = new ScalaJSClass(3)
    println(sjc.plus(1) + sjc.orig() + sjc.defaults() + sjc.varargs(1, 2) + sjc.mutable + sjc.nested.toString)
    val jt = new JSTrait { val name = "n" }
    println(jt.name + jt.opt)
    println(Holder.mk.k.toString + js.constructorOf[ScalaJSClass] + js.Object.keys(sjc).length)
    val prom = js.Promise.resolve[Int](1).`then`[Int](x => x + 1)
    val sym = js.Symbol("s")
    val obj = js.Dynamic.literal()
    obj.updateDynamic("k")(1)
    println(obj.selectDynamic("k").toString + js.JSON.stringify(obj) + (sym: Any).toString)
    try js.special.debugger() catch case js.JavaScriptException(e) => println(e)
    val n: Long = 1L << 40; val c: Char = 'x'; val bd = BigDecimal(1.5)
    println(n + c + bd.toString + List(1, 2).map(_.toString).mkString)
