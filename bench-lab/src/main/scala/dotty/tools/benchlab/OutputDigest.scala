package dotty.tools.benchlab

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import dotty.tools.io.AbstractFile

/** `OutputDigest <dir> <corpus>...`: compiles each corpus once, as `CompileBenchmark` does, and writes
 *  `<dir>/<corpus>.sha256`, a line `<sha256> <path>` per output file (class files and TASTy), sorted by path. Two
 *  compilers that produce byte-identical output for a corpus write identical files; diff them to compare builds.
 *
 *  Run it in sbt with the benchmarks' system properties: `scala3-bench-lab/Jmh/runMain dotty.tools.benchlab.OutputDigest`.
 */
object OutputDigest:
  def main(args: Array[String]): Unit =
    val dir = Path.of(args.head)
    Files.createDirectories(dir)
    for corpus <- args.tail do
      val compiler = InMemoryCompiler(Corpus.load(corpus))
      compiler.compile()
      val lines = files(compiler.output, "").sortBy(_._1).map((path, f) => s"${sha256(f.toByteArray)} $path")
      val out = dir.resolve(s"$corpus.sha256")
      Files.write(out, (lines.mkString("\n") + "\n").getBytes("UTF-8"))
      val classes = lines.count(_.endsWith(".class"))
      println(s"[bench-lab] $corpus: ${compiler.corpus.files.size} sources -> ${lines.size} output files ($classes class files) -> $out")

  private def files(f: AbstractFile, prefix: String): List[(String, AbstractFile)] =
    if f.isDirectory then f.iterator.toList.flatMap(c => files(c, s"$prefix${c.name}${if c.isDirectory then "/" else ""}"))
    else List(prefix -> f)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
