// Control flow: switches (table/lookup/string), try/finally in expression position, return through finally, while loops, labels.
package control

def table(i: Int) = i match
  case 0 => "zero"; case 1 => "one"; case 2 => "two"; case 3 => "three"; case 4 => "four"; case _ => "many"
def lookup(i: Int) = i match
  case -1000 => 1; case 7 => 2; case 1048576 => 3; case Int.MaxValue => 4; case _ => 0
def strSwitch(s: String) = s match
  case "alpha" | "beta" => 1; case "gamma" => 2; case "Aa" => 3; case "BB" => 4; case _ => 0
def charSwitch(c: Char) = (c: @annotation.switch) match
  case 'a' => 1; case 'b' => 2; case 'c' => 3; case _ => 0
def longMatch(l: Long) = l match { case 1L => 1; case 2L => 2; case _ => 0 }
def tryExpr(x: String) = List(try x.toInt catch { case _: NumberFormatException => -1 }, 2)
def tryInArgs(x: String) = math.max(try x.toInt catch { case _: Exception => 0 }, try 1 finally println("f"))
def returnFinally(x: Int): Int =
  try
    if x > 0 then return x
    -x
  finally println("finally")
def nestedTry(x: Int) =
  try
    try { if x == 0 then throw IllegalStateException("z"); 1 / x }
    catch case e: ArithmeticException => -1
    finally println("inner")
  catch
    case e: IllegalStateException => -2
    case scala.util.control.NonFatal(e) => -3
  finally println("outer")
def loops(n: Int) =
  var i = 0; var acc = 0
  while { i += 1; i < n } do
    if i % 2 == 0 then acc += i else acc -= 1
  var j = n
  while j > 0 && acc < 1000 || j == 7 do j -= 1
  acc + j
def breaks(xs: List[Int]) =
  import scala.util.boundary, boundary.break
  boundary:
    for x <- xs do if x > 3 then break(x)
    -1
def ifChain(x: Int) = if x < 0 then "neg" else if x == 0 then "zero" else if x < 10 then "small" else "big"
def boolOps(a: Boolean, b: Boolean, c: Boolean) = (a && b || !c, a & b | c ^ a, !(a || b) && c)
def throwExpr(x: Int): Int = if x > 0 then x else throw IllegalArgumentException("neg")
def synchronizedBlock(o: AnyRef) = o.synchronized { 1 + 1 }
def stringConcat(a: Int, b: String, c: Double, d: Char, e: Any) = "x" + a + b + c + d + e + null + true + 1L
@main def Test =
  println((0 to 6).map(table) ++ List(lookup(7), strSwitch("Aa"), strSwitch("BB"), charSwitch('b'), longMatch(2)))
  println(tryExpr("1") ++ tryExpr("x") :+ tryInArgs("3") :+ returnFinally(1) :+ returnFinally(-2) :+ nestedTry(0) :+ nestedTry(2))
  println(loops(10) + breaks(List(1, 5)) + ifChain(5) + boolOps(true, false, true) + synchronizedBlock("x") + stringConcat(1, "b", 2.0, 'c', None))
  println(try throwExpr(-1) catch case e: IllegalArgumentException => e.getMessage)
