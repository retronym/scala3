package dotty.tools.pc.xray

import java.net.URI
import java.nio.file.{Files, Paths}
import scala.language.unsafeNulls
import scala.jdk.CollectionConverters.*

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.interactive.{CachedLogicalPackage, InteractiveDriver}
import dotty.tools.dotc.util.SourceFile

/** Scratch: what `openAll` leaves typed at top level (everything but imports should be skipped). */
object OpenStats:
  def main(args: Array[String]): Unit =
    val root = Paths.get(sys.props.getOrElse("tp.root", "out/xray-sources/compiler/src/dotty/tools/dotc"))
    val rels = Files.walk(root).iterator.asScala.filter(p => p.toString.endsWith(".scala")).map(p => root.relativize(p).toString).toList.sorted
    val files = rels.map(n => (URI.create("file:///" + n), SourceFile.virtual(n, Files.readString(root.resolve(n)))))
    val cp = System.getProperty("java.class.path")
    // a fresh driver per open, as the throughput harness measures (`-Dos.sameDriver` reopens on one)
    val sameDriver = sys.props.contains("os.sameDriver")
    def newDriver() = new InteractiveDriver(List("-color:never", "-Yinteractive-parallel", "-classpath", cp), CachedLogicalPackage.none)
    var driver = newDriver()
    for i <- 1 to sys.props.getOrElse("os.reps", "3").toInt do
      if !sameDriver && i > 1 then { driver = newDriver(); System.gc() }
      val t0 = System.nanoTime()
      val ds = driver.withCancelChecker(() => false)(driver.openAll(files))
      System.err.println(f"[os] open ${(System.nanoTime() - t0) / 1e6}%.1f ms, ${ds.values.map(_.count(_.level >= 2)).sum} errors")
      for (u, l) <- ds.toList.sortBy(_._1.toString); d <- l if d.level >= 2 do
        System.err.println(s"[os]   ${u.getPath.stripPrefix("/")}:${d.pos.line + 1}: ${d.msg.message.linesIterator.next().take(160)}")
    given Context = driver.currentCtx
    val byKind = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var typedStats = 0
    def walk(t: tpd.Tree, file: String): Unit = t match
      case tpd.PackageDef(_, stats) => stats.foreach(walk(_, file))
      case _: tpd.Import | _: tpd.Export =>
      case t =>
        typedStats += 1
        val sym = t.symbol
        val key = s"${t.getClass.getSimpleName} synthetic=${sym.is(Synthetic)} flags=${sym.flagsString.take(60)}"
        byKind(key) += 1
        if sys.props.contains("os.verbose") then println(s"$file: ${sym.showFullName} [$key] size=${t.treeSize}")
    for (u, src) <- files do walk(driver.compilationUnits(u).tpdTree, src.path)
    println(s"typed top-level statements: $typedStats in ${files.size} files")
    byKind.toList.sortBy(-_._2).foreach((k, n) => println(f"$n%5d $k"))
