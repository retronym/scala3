package dotty.tools.pc.xray

import java.net.URI
import java.nio.file.{FileSystems, Files, Path}
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

import dotty.tools.dotc.{Driver, ParallelBatch, ParallelBatchContextBase, ParallelBatchReporter}
import dotty.tools.dotc.classpath.{AggregateClassPath, ClassPath, VirtualDirectoryClassPath}
import dotty.tools.dotc.config.{JavaPlatform, Platform}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.io.{AbstractFile, VirtualFile}

/** The batch view's compiler with no file I/O: the classpath (`java.class.path`, jars and directories, and the JDK's
 *  modules), the sources and the output in memory, read once per JVM, as jvm-perf-lab's compiler lab does
 *  (`InMemoryClasspath`). On this machine the endpoint security software makes every `stat` and `open` slow, which
 *  made the classpath's directory listings a third of the class headers' time; that is not the compiler's.
 *  `-Dxray.batch.diskIO` for the plain `Driver` instead.
 */
object VirtualIO:
  /** `VirtualDirectoryClassPath` that finds nested packages: `DirectoryLookup` asks for `java/lang/`, with a trailing
   *  separator, which `AbstractFile.lookupPath` resolves to an empty last name on a virtual directory. */
  private final class DirectoryClassPath(dir: AbstractFile) extends VirtualDirectoryClassPath(dir):
    override protected def getSubDir(packageDirName: String): Option[AbstractFile] =
      super.getSubDir(packageDirName.stripSuffix(java.io.File.separator))

  final case class Snapshot(roots: List[AbstractFile], files: Int, bytes: Long):
    def classPath: ClassPath = AggregateClassPath(roots.map(DirectoryClassPath(_)))
    override def toString = f"$files%d files, ${bytes / 1e6}%.0f MB"

  /** The run's classpath and every JDK module, read once. */
  lazy val classpath: Snapshot =
    val entries = sys.props("java.class.path").split(java.io.File.pathSeparator).toList.filter(_.nonEmpty).map(Path.of(_))
    load(entries)

  private def load(entries: List[Path]): Snapshot =
    var files = 0
    var bytes = 0L
    def add(root: AbstractFile, rel: String, data: Array[Byte]): Unit =
      val parts = rel.split('/').toList
      val dir = parts.init.foldLeft(root)(_.subdirectoryNamed(_))
      val out = dir.fileNamed(parts.last).output
      try out.write(data) finally out.close()
      files += 1
      bytes += data.length
    // FileUtils.classNameToTasty: Foo$.class and Foo.class come from Foo.tasty, which dotc finds as the class file's
    // sibling through `AbstractFile.container`, which in-memory files lack: leave such class files out
    def siblingTasty(rel: String): String =
      if !rel.endsWith(".class") then ""
      else
        val stem = rel.stripSuffix(".class")
        val base = stem.substring(stem.lastIndexOf('/') + 1)
        val cls = if base.endsWith("$") && base != "Null$" && base != "Nothing$" && base != "$" then stem.stripSuffix("$") else stem
        cls + ".tasty"
    def wanted(rel: String) = (rel.endsWith(".class") || rel.endsWith(".tasty")) && !rel.endsWith("module-info.class")
    def addAll(root: AbstractFile, all: List[(String, () => Array[Byte])]): Unit =
      val names = all.map(_._1).toSet
      for (rel, data) <- all if wanted(rel) && !names(siblingTasty(rel)) do add(root, rel, data())

    val roots = entries.filter(Files.exists(_)).map { entry =>
      val root = dotty.tools.io.virtualDirectory(s"memory-${entry.getFileName}")
      if Files.isDirectory(entry) then
        val walk = Files.walk(entry)
        val all = try walk.iterator.asScala.filter(Files.isRegularFile(_)).toList finally walk.close()
        addAll(root, all.map(p => entry.relativize(p).toString.replace(java.io.File.separatorChar, '/') -> (() => Files.readAllBytes(p))))
      else if entry.toString.endsWith(".jar") then
        val zip = ZipFile(entry.toFile)
        try addAll(root, zip.entries.asScala.filter(!_.isDirectory).toList.map(e => e.getName -> (() => zip.getInputStream(e).readAllBytes())))
        finally zip.close()
      root
    }
    val jdkRoot = dotty.tools.io.virtualDirectory(s"memory-jdk-${Runtime.version.feature}")
    val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
    val modules = Files.list(jrt.getPath("/modules"))
    try
      for base <- modules.iterator.asScala do
        val walk = Files.walk(base)
        try
          for p <- walk.iterator.asScala if Files.isRegularFile(p) do
            val rel = base.relativize(p).toString
            if wanted(rel) then add(jdkRoot, rel, Files.readAllBytes(p))
        finally walk.close()
    finally modules.close()
    Snapshot(jdkRoot :: roots, files, bytes)

  private var sourcesOf: (Path, List[AbstractFile]) | Null = null

  /** The sources under `root` as `VirtualFile`s at their paths on disk, read once per root. */
  def sources(root: Path, paths: List[String]): List[AbstractFile] = synchronized {
    if sourcesOf == null || sourcesOf._1 != root then
      sourcesOf = (root, paths.map(p => VirtualFile(p, Files.readAllBytes(Path.of(p)))))
    sourcesOf._2
  }

  /** A batch compiler on the snapshot: the parallel base and reporter, as `Driver.process` picks for
   *  `-Yparallel-typer`, with the snapshot as the platform's classpath and the output in memory. */
  final class VirtualDriver(snapshot: Snapshot) extends Driver:
    override protected def sourcesRequired: Boolean = false

    def compile(args: Array[String], files: List[AbstractFile], reporter: Reporter): Reporter =
      // the completion lock's granularity from the arguments, as `Driver.process` reads it
      val base = new ParallelBatchContextBase(ParallelBatch.sharingOf(args)):
        override protected def newPlatform(using Context): Platform = new JavaPlatform:
          private val cp = snapshot.classPath
          override def classPath(using Context): ClassPath = cp
      val root = base.initialCtx.fresh.setReporter(reporter)
      root.setReporter(ParallelBatchReporter(root.reporter))
      setup(args, root) match
        case Some((_, ctx)) =>
          val out = ctx.fresh.setSetting(ctx.settings.outputDir, dotty.tools.io.virtualDirectory("<xray batch output>"))
          doCompile(newCompiler(using out), files)(using out)
        case None => root.reporter
