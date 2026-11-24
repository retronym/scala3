//> using options -opt -opt-inline:** -Yopt-inline-heuristics:everything

// p.Pkg is package-private. Its ClassBType is created from its symbol, as it is used in this
// compilation run. The class literal in `cls` cannot be inlined into Test.
package p {
  object D {
    def cls: Class[?] = classOf[Pkg]
  }
}

object Test {
  def main(args: Array[String]): Unit = println(p.D.cls)
}
