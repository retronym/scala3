package dotty.tools.benchlab

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

import dotty.tools.io.VirtualFile

/** Sources compiled together, read into memory once, with the options they need. */
final case class Corpus(name: String, files: List[VirtualFile], options: List[String])

object Corpus:
  // Options from lampepfl/scala3-benchmarks' build.sbt, without -Werror.
  private val shared = List("-feature", "-deprecation")
  private val options = Map(
    "small" -> Nil,
    "dottyUtil" -> Nil,
    "re2s" -> Nil,
    "scalaz" -> List("-nowarn", "-source", "3.0", "-Xkind-projector", "-language:implicitConversions"),
  )

  /** A corpus from a lampepfl/scala3-benchmarks checkout (`benchlab.corpora` is its `bench-sources`), or, given an
   *  absolute path, the sources under that directory, with the options of the known corpus of the same name, if any.
   */
  def load(name: String): Corpus =
    val absolute = Path.of(name).isAbsolute
    val dir =
      if absolute then Path.of(name)
      else Path.of(sys.props.getOrElse("benchlab.corpora", sys.error("-Dbenchlab.corpora=<scala3-benchmarks>/bench-sources not set"))).resolve(name)
    val opts = options.get(dir.getFileName.toString) match
      case Some(o) => o
      case None if absolute => Nil
      case None => sys.error(s"unknown corpus $name; known: ${options.keys.toList.sorted.mkString(", ")}, or an absolute path")
    val paths = Files.walk(dir).iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sorted
    if paths.isEmpty then sys.error(s"no sources in $dir")
    Corpus(name, paths.map(p => VirtualFile(dir.relativize(p).toString, Files.readAllBytes(p))), shared ++ opts)

  /** A corpus from sources given inline, for micro benchmarks. */
  def inline(name: String, sources: (String, String)*): Corpus =
    Corpus(name, sources.toList.map((path, text) => VirtualFile(path, text.getBytes("UTF-8"))), shared)
