package dotty.tools.benchlab

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

import dotty.tools.io.{AbstractFile, VirtualFile}

/** Sources compiled together, read into memory once, with the options they need.
 *
 *  @param sourcePath       an in-memory directory of sources that dotc finds as on `-sourcepath`, or null
 *  @param withScalaLibrary the scala-library jar is on the classpath (not for the library itself)
 */
final case class Corpus(name: String, files: List[AbstractFile], options: List[String],
    sourcePath: AbstractFile | Null = null, withScalaLibrary: Boolean = true)

object Corpus:
  // Options from lampepfl/scala3-benchmarks' build.sbt, without -Werror.
  private val shared = List("-feature", "-deprecation")
  private val options = Map(
    "small" -> Nil,
    "dottyUtil" -> Nil,
    "re2s" -> Nil,
    "tastyQuery" -> List("-Yexplicit-nulls", "-Wconf:msg=Unnecessary .nn:s"),
    "scalaz" -> List("-nowarn", "-source", "3.0", "-Xkind-projector", "-language:implicitConversions"),
  )

  /** A corpus from a lampepfl/scala3-benchmarks checkout (`benchlab.corpora` is its `bench-sources`), or, given an
   *  absolute path, the sources under that directory, with the options of the known corpus of the same name, if any.
   */
  def load(name: String): Corpus =
    if name == "scalaLibrary" then return scalaLibrary
    val absolute = Path.of(name).isAbsolute
    val dir =
      if absolute then Path.of(name)
      else Path.of(sys.props.getOrElse("benchlab.corpora", sys.error("-Dbenchlab.corpora=<scala3-benchmarks>/bench-sources not set"))).resolve(name)
    val opts = options.get(dir.getFileName.toString) match
      case Some(o) => o
      case None if absolute => Nil
      case None => sys.error(s"unknown corpus $name; known: ${(options.keys.toList :+ "scalaLibrary").sorted.mkString(", ")}, or an absolute path")
    val paths = Files.walk(dir).iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sorted
    if paths.isEmpty then sys.error(s"no sources in $dir")
    Corpus(name, paths.map(p => VirtualFile(dir.relativize(p).toString, Files.readAllBytes(p))), shared ++ opts)

  /** The standard library of this checkout (`benchlab.scalaLibrarySources`, its source directories), compiled as
   *  `scala-library-bootstrapped` compiles it (`benchlab.scalaLibraryOptions`, a file of its options, one per line,
   *  without `-Werror`, `-opt` and `-sourcepath`), with no scala-library jar on the classpath. Its sources are also the
   *  in-memory sourcepath, for the library classes `Definitions` loads before the run enters the compilation units.
   */
  def scalaLibrary: Corpus =
    def prop(key: String) = sys.props.getOrElse(key, sys.error(s"-D$key not set"))
    val dirs = prop("benchlab.scalaLibrarySources").split(java.io.File.pathSeparator).toList.map(Path.of(_)).filter(Files.isDirectory(_))
    val options = Files.readAllLines(Path.of(prop("benchlab.scalaLibraryOptions"))).asScala.toList.filter(_.nonEmpty)
    val root = dotty.tools.io.virtualDirectory("memory-scala-library-sources")
    val files =
      for
        dir <- dirs
        p <- Files.walk(dir).iterator.asScala.toList.sorted
        rel = dir.relativize(p).toString
        if rel.endsWith(".scala") || rel.endsWith(".java")
      yield
        val parts = rel.split('/').toList
        val file = parts.init.foldLeft(root)(_.subdirectoryNamed(_)).fileNamed(parts.last)
        val out = file.output
        try out.write(Files.readAllBytes(p)) finally out.close()
        file
    if files.isEmpty then sys.error(s"no sources in ${dirs.mkString(", ")}")
    Corpus("scalaLibrary", files, options, sourcePath = root, withScalaLibrary = false)

  /** A corpus from sources given inline, for micro benchmarks. */
  def inline(name: String, sources: (String, String)*): Corpus =
    Corpus(name, sources.toList.map((path, text) => VirtualFile(path, text.getBytes("UTF-8"))), shared)
