package dotty.tools.pc.xray

import java.nio.file.{Files, Path, Paths}

/** A small source set for `Throughput -Dtp.root=<dir>` that hammers one
 *  implicit pattern from every reader: the compiler's `Formatting.scala`
 *  (from source, so each round's run completes its givens afresh while the
 *  rest of the compiler comes from the classpath) and files of conversions to
 *  `Formatting.Shown`, written as interpolations, as views, as summons and
 *  longhand. A dozen rounds at k=8 found the races of I78-I79: `asSeenFrom`'s cache, the
 *  package objects' cycle breaker, lazy annotations and `AppliedType`'s
 *  caches, each of which the 484-file loop hits about once a round or less.
 *
 *    ShownSoak <dir> [files] [methods]
 *    Throughput -Dtp.root=<dir> -Dtp.loop=20 -Dtp.ks=8 -Dtp.policies=rr
 */
object ShownSoak:
  def main(args: Array[String]): Unit =
    val dir = Paths.get(args(0))
    val files = args.lift(1).map(_.toInt).getOrElse(16)
    val methods = args.lift(2).map(_.toInt).getOrElse(40)
    Files.createDirectories(dir)
    Files.copy(Paths.get("compiler/src/dotty/tools/dotc/printing/Formatting.scala"), dir.resolve("Formatting.scala"),
      java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    for f <- 0 until files do
      val sb = new StringBuilder
      sb ++= """|package dotty.tools.dotc.soak
                |
                |import dotty.tools.dotc.core.*, Contexts.*, Symbols.*, Types.*, Names.*, Decorators.*
                |import dotty.tools.dotc.printing.Formatting.{Shown, Show}
                |import dotty.tools.dotc.printing.Formatting.ShownDef
                |
                |""".stripMargin
      sb ++= s"object Soak$f:\n"
      for m <- 0 until methods do
        sb ++= s"""|  def interp$m(s: String, sym: Symbol, tp: Type, n: Name, k: Int, ts: TyperState, c: Class[?])(using Context): String =
                   |    i"$m $$s $$sym $$tp $$n $$k $$ts $$c"
                   |  def view$m(s: String, sym: Symbol, tp: Type, n: Name, k: Int)(using Context): List[Shown] =
                   |    val a: Shown = s; val b: Shown = sym; val c: Shown = tp; val d: Shown = n; val e: Shown = k
                   |    List(a, b, c, d, e)
                   |  def show$m(using Context) = (summon[Show[String]], summon[Show[Symbol]], summon[Show[Type]], summon[Show[Name]], summon[Show[Int]])
                   |  def conv$m(using Context) = (summon[Conversion[String, Shown]], summon[Conversion[Symbol, Shown]], summon[Conversion[Type, Shown]])
                   |  def longhand$m(s: String, sym: Symbol)(using Context): List[Shown] =
                   |    List(ShownDef.Shown.given_Conversion_A_Shown[String](using ShownDef.Show.given_Show_String).apply(s),
                   |      ShownDef.Shown.given_Conversion_A_Shown[Symbol](using ShownDef.Show.given_Show_Showable).apply(sym))
                   |""".stripMargin
      Files.writeString(dir.resolve(s"Soak$f.scala"), sb.toString)
    println(s"[soak] ${files + 1} files in $dir")
