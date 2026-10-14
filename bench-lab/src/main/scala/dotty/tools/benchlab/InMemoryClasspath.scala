package dotty.tools.benchlab

import java.net.URI
import java.nio.file.{FileSystems, Files, Path}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

import dotty.tools.dotc.classpath.{AggregateClassPath, ClassPath, DirectoryLookup, FileUtils, SourceFileEntry, VirtualDirectoryClassPath}
import dotty.tools.io.AbstractFile

/** The compile classpath, read into memory once per JVM: the entries of the library jars and the class files of some JDK
 *  modules, each as a read-only in-memory directory. A compiler run then finds every class and TASTy file without
 *  touching the file system or dotc's process-wide caches of jar indexes and the jrt file system.
 */
final class InMemoryClasspath(val roots: List[AbstractFile], val files: Int, val bytes: Long):
  /** A new `ClassPath` over the snapshot, for one compiler run, then the sources in `sourcePath`, if not null. */
  def classPath(sourcePath: AbstractFile | Null = null): ClassPath =
    val sources = if sourcePath == null then Nil else List(InMemoryClasspath.SourcePath(sourcePath))
    AggregateClassPath(roots.map(InMemoryClasspath.DirectoryClassPath(_)) ::: sources)

  override def toString = f"$files%d files, ${bytes / 1e6}%.0f MB"

object InMemoryClasspath:
  /** `VirtualDirectoryClassPath` that finds nested packages: `DirectoryLookup` asks for `java/lang/`, with a trailing
   *  separator, which `AbstractFile.lookupPath` resolves to an empty last name on a virtual directory, so only the root
   *  package was found.
   */
  private final class DirectoryClassPath(dir: AbstractFile) extends VirtualDirectoryClassPath(dir):
    override protected def getSubDir(packageDirName: String): Option[AbstractFile] =
      super.getSubDir(packageDirName.stripSuffix(java.io.File.separator))

  /** The sources in an in-memory directory, as `-sourcepath` would find them on disk (`DirectorySourcePath`). */
  private final class SourcePath(protected override val dir: AbstractFile) extends ClassPath with DirectoryLookup[SourceFileEntry]:
    type F = AbstractFile
    protected def getSubDir(packageDirName: String): Option[AbstractFile] =
      dir.lookupPath(packageDirName.stripSuffix(java.io.File.separator), java.io.File.separatorChar, directory = true)
    protected def listChildren(dir: AbstractFile, filter: Option[AbstractFile => Boolean]): Iterable[AbstractFile] =
      filter.fold(dir.iterator.toSeq)(dir.iterator.filter(_).toSeq)
    protected def getName(f: AbstractFile): String = f.name
    protected def toAbstractFile(f: AbstractFile): AbstractFile = f
    protected def isPackage(f: AbstractFile): Boolean = f.isDirectory && FileUtils.mayBeValidPackage(f.name)
    protected def createFileEntry(file: AbstractFile): SourceFileEntry = SourceFileEntry(file)
    protected def isMatchingFile(f: AbstractFile): Boolean = !f.isDirectory && FileUtils.endsSourceExtension(f.name)
    override def sources(inPackage: String): Iterable[SourceFileEntry] = files(inPackage)

  /** The snapshot for a corpus: `default`, or `jdk` if the corpus has no scala-library on its classpath. */
  def forCorpus(corpus: Corpus): InMemoryClasspath = if corpus.withScalaLibrary then default else jdk

  /** The JDK modules in `benchlab.jdkModules` alone, for the standard library itself. */
  lazy val jdk: InMemoryClasspath = load(Nil, jdkModules)

  private def jdkModules: List[String] =
    sys.props.getOrElse("benchlab.jdkModules", "java.base").nn.split(",").toList.map(_.nn.trim.nn).filter(_.nonEmpty)

  /** The scala-library jar (`benchlab.scalaLibrary`) and the JDK modules in `benchlab.jdkModules` (default `java.base`)
   *  of the running JDK.
   */
  lazy val default: InMemoryClasspath =
    val lib = sys.props.getOrElse("benchlab.scalaLibrary", sys.error("-Dbenchlab.scalaLibrary=<jar> not set"))
    load(List(Path.of(lib).nn), jdkModules)

  def load(jars: List[Path], jdkModules: List[String]): InMemoryClasspath =
    var files = 0
    var bytes = 0L
    def add(root: AbstractFile, rel: String, data: Array[Byte]): Unit =
      val parts = rel.split('/').toList
      val dir = parts.init.foldLeft(root)(_.subdirectoryNamed(_))
      val out = dir.fileNamed(parts.last).output
      try out.write(data) finally out.close()
      files += 1
      bytes += data.length
    // FileUtils.classNameToTasty: Foo$.class and Foo.class come from Foo.tasty
    def siblingTasty(rel: String): String =
      if !rel.endsWith(".class") then ""
      else
        val stem = rel.stripSuffix(".class")
        val base = stem.substring(stem.lastIndexOf('/') + 1)
        val cls = if base.endsWith("$") && base != "Null$" && base != "Nothing$" && base != "$" then stem.stripSuffix("$") else stem
        cls + ".tasty"
    def wanted(rel: String) = (rel.endsWith(".class") || rel.endsWith(".tasty")) && !rel.endsWith("module-info.class")

    val jarRoots = jars.map { jar =>
      val root = dotty.tools.io.virtualDirectory(s"memory-${jar.getFileName}") // not <...>: VirtualFile takes such a path as its name
      val zip = ZipFile(jar.toFile)
      try
        val entries = zip.entries.asScala.filter(e => !e.isDirectory && wanted(e.getName)).toList
        val names = entries.map(_.getName).toSet
        // dotc ignores a class file beside its TASTy file, but it finds that sibling through `AbstractFile.container`,
        // which in-memory files lack: leave such class files out, so it reads TASTy as it would from the jar
        for e <- entries if !names(siblingTasty(e.getName)) do
          val in = zip.getInputStream(e)
          try add(root, e.getName, in.readAllBytes()) finally in.close()
      finally zip.close()
      root
    }
    val jdkRoot = dotty.tools.io.virtualDirectory(s"memory-jdk-${Runtime.version.feature}")
    if jdkModules.nonEmpty then
      val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
      for m <- jdkModules do
        val base = jrt.getPath("/modules", m)
        val walk = Files.walk(base)
        try
          for p <- walk.iterator.asScala if Files.isRegularFile(p) do
            val rel = base.relativize(p).toString
            if wanted(rel) then add(jdkRoot, rel, Files.readAllBytes(p))
        finally walk.close()
    new InMemoryClasspath(jdkRoot :: jarRoots, files, bytes)
