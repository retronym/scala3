// Stage 2: Scala using Java classfiles from stage 1, jointly compiled with Java sources that use Scala.
package juse

import jlib.JLib_1
import jlib.JLib_1.*
import scala.jdk.CollectionConverters.*

class ScalaSide(val n: Int) extends JLib_1.AbstractBase[ScalaSide] with JLib_1.WithDefaults[String]:
  protected def self(): ScalaSide = this
  def get(): String = null
  def fromJava: Int = new JointJava().useScala(this)

object Use:
  final val c = JLib_1.CONST
  def main(args: Array[String]): Unit =
    val j = new JLib_1(1, "a", "b")
    println(JLib_1.max(java.util.List.of(1, 2, 3)) + JLib_1.sum(1, 2, 3) + JLib_1.sum() + JLib_1.join(",", "a", 1))
    println(j.generic[String, java.util.List[String]]("k", new java.util.ArrayList[String]()))
    j.old()
    try j.throwsIt() catch case e: java.io.IOException => ()
    val arr: Array[Array[Int]] = j.arrays(Array("a"), Array(1), Array(2))
    val raw = j.raw()
    println(raw.size())
    val f: java.util.function.Function[Int, String] = JLib_1.fn((i: Int) => i.toString)
    val sn = new JLib_1.StaticNested[String]
    sn.value = "v"
    new JLib_1.StaticNested.Deeper
    val inner = new j.Inner
    println(inner.outerProt())
    println(JLib_1.WithDefaults.of("x").getOr("y"))
    val op: JLib_1.IntOp = (a, b) => a * b
    println(op.apply(2, 3))
    println(JLib_1.Planet.EARTH.mass() + JLib_1.Planet.values().length + JLib_1.Planet.valueOf("MERCURY").apply(1, 2))
    JLib_1.Planet.EARTH match
      case JLib_1.Planet.MERCURY => println("m")
      case JLib_1.Planet.EARTH => println("e")
    val p = new JLib_1.Point(1, 2)
    println(p.x() + p.y() + JLib_1.Point.origin().compareTo(p) + new JLib_1.Point(5).x)
    val s: JLib_1.Shape = new JLib_1.Circle
    s match
      case c: JLib_1.Circle => println(c.r)
      case q: JLib_1.Square => println(q)
    println(new ScalaSide(1).touch().n + new ScalaSide(2).fromJava + new ScalaSide(3).getOr("d"))
    println(JLib_1.opt().orElse("none") + JLib_1.bounded(Integer.valueOf(1)))
    JLib_1.consume(s => println(s), () => "x", (a, b) => a + b)
    JLib_1.mutableStatic += 1
    val ann = classOf[JLib_1].getField("CONST").getAnnotation(classOf[JLib_1.Ann])
    println(ann.i() + ann.s() + ann.arr().length)
    println(List(1, 2).asJava.asScala.toList ++ java.util.Map.of("a", 1).asScala.keys)
    println(c + JLib_1.SCONST + JLib_1.LCONST)
    new JointJava.Nested[Int](3).get

@JLib_1.Ann(i = 5, s = "scala", arr = Array("x"), c = classOf[String], e = java.lang.annotation.ElementType.METHOD)
class Annotated:
  @JLib_1.Ann def m(@JLib_1.Ann x: Int): Int = x
