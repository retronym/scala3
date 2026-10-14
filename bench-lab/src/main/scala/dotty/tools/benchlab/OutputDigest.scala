package dotty.tools.benchlab

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import dotty.tools.io.AbstractFile

/** `OutputDigest <dir> <corpus>...`: compiles each corpus once, as `CompileBenchmark` does, and writes
 *  `<dir>/<corpus>.sha256`, a line `<sha256> <path>` per output file (class files and TASTy), sorted by path, and the
 *  files themselves under `<dir>/<corpus>/`. Two compilers that produce byte-identical output for a corpus write
 *  identical manifests; diff them to compare builds, and the files to see how they differ.
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
      val root = compiler.output.path + "/"
      val lines = files(compiler.output).map(f => (f.path.stripPrefix(root), f)).sortBy(_._1).map((path, f) => s"${sha256(f.toByteArray)} $path")
      for f <- files(compiler.output) do
        val p = dir.resolve(corpus).resolve(f.path.stripPrefix(root))
        Files.createDirectories(p.getParent)
        Files.write(p, f.toByteArray)
      val out = dir.resolve(s"$corpus.sha256")
      Files.write(out, (lines.mkString("\n") + "\n").getBytes("UTF-8"))
      val classes = lines.count(_.endsWith(".class"))
      println(s"[bench-lab] $corpus: ${compiler.corpus.files.size} sources -> ${lines.size} output files ($classes class files) -> $out")

  private def files(f: AbstractFile): List[AbstractFile] =
    if f.isDirectory then f.iterator.toList.flatMap(files) else List(f)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
