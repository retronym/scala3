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

  /** A corpus from a lampepfl/scala3-benchmarks checkout (`benchlab.corpora` is its `bench-sources`). */
  def load(name: String): Corpus =
    val opts = options.getOrElse(name, sys.error(s"unknown corpus $name; known: ${options.keys.toList.sorted.mkString(", ")}"))
    val root = Path.of(sys.props.getOrElse("benchlab.corpora", sys.error("-Dbenchlab.corpora=<scala3-benchmarks>/bench-sources not set")))
    val dir = root.resolve(name)
    val paths = Files.walk(dir).iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sorted
    if paths.isEmpty then sys.error(s"no sources in $dir")
    Corpus(name, paths.map(p => VirtualFile(dir.relativize(p).toString, Files.readAllBytes(p))), shared ++ opts)

  /** A corpus from sources given inline, for micro benchmarks. */
  def inline(name: String, sources: (String, String)*): Corpus =
    Corpus(name, sources.toList.map((path, text) => VirtualFile(path, text.getBytes("UTF-8"))), shared)
