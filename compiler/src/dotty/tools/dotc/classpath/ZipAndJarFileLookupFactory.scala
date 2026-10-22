/*
 * Copyright (c) 2014 Contributor. All rights reserved.
 */
package dotty.tools.dotc
package classpath

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import dotty.tools.io.{AbstractFile, FileZipArchive}
import dotty.tools.dotc.core.Contexts.*
import FileUtils.*

/**
 * A trait providing an optional cache for classpath entries obtained from zip and jar files.
 * It allows us to e.g. reduce significantly memory used by PresentationCompilers in Scala IDE
 * when there are a lot of projects having a lot of common dependencies.
 */
sealed trait ZipAndJarFileLookupFactory {
  // Keyed by `-release` as well as the path: the cached classpath views a multi-release jar through the
  // `META-INF/versions` of that release.
  private val cache = new FileBasedCache[String, ClassPath]

  def create(zipFile: AbstractFile)(using Context): ClassPath =
    val release = ctx.settings.javaOutputVersion.value
    val jFile = zipFile.file
    if jFile == null then
      createForZipFile(zipFile, jFile, release)
    else if ctx.settings.YdisableFlatCpCaching.value then
      val cp = createForZipFile(zipFile, jFile, release)
      cp match
        case closeable: java.io.Closeable => ctx.base.registerUncachedClassPath(closeable)
        case _ =>
      cp
    else
      cache.getOrCreate(release, jFile.toPath, () => createForZipFile(zipFile, jFile, release))

  protected def createForZipFile(zipFile: AbstractFile, jFile: File | Null, release: String): ClassPath

}

/**
 * Manages creation of classpath for class files placed in zip and jar files.
 * It should be the only way of creating them as it provides caching.
 */
object ZipAndJarClassPathFactory extends ZipAndJarFileLookupFactory {
  private class ZipArchiveClassPath(protected override val zipFile: File, protected override val release: String)
    extends ZipArchiveFileLookup[BinaryFileEntry] {

    override def findClassFile(className: String): Option[AbstractFile] =
      file(className).map(_.file)

    override def classes(inPackage: String): Iterable[BinaryFileEntry] = files(inPackage)

    override protected def createFileEntry(file: AbstractFile): BinaryFileEntry = BinaryFileEntry(file)

    override protected def isRequiredFileType(file: AbstractFile): Boolean =
      file.exists && (file.ext.isTasty || (file.ext.isClass && !file.hasSiblingTasty))
  }

  override protected def createForZipFile(zipFile: AbstractFile, jFile: File | Null, release: String): ClassPath =
    if (jFile == null) throw new IllegalArgumentException(s"Abstract files which don't have an underlying file are not supported. There was $zipFile")
    else ZipArchiveClassPath(jFile, release)
}

/**
 * Manages creation of classpath for source files placed in zip and jar files.
 * It should be the only way of creating them as it provides caching.
 */
object ZipAndJarSourcePathFactory extends ZipAndJarFileLookupFactory {
  private class ZipArchiveSourcePath(protected override val zipFile: File, protected override val release: String) extends ZipArchiveFileLookup[SourceFileEntry] {
    override def sources(inPackage: String): Iterable[SourceFileEntry] = files(inPackage)

    override protected def createFileEntry(file: AbstractFile): SourceFileEntry = SourceFileEntry(file)
    override protected def isRequiredFileType(file: AbstractFile): Boolean = file.ext.isSourceExtension
  }

  override protected def createForZipFile(zipFile: AbstractFile, jFile: File | Null, release: String): ClassPath =
    assert(jFile != null, "Zip file in ZipAndJarSourcePathFactory cannot be null")
    ZipArchiveSourcePath(jFile, release)
}

final class FileBasedCache[K, T] {
  private case class Stamp(lastModified: FileTime, size: Long, fileKey: Object)
  private val cache = collection.mutable.Map.empty[(K, java.nio.file.Path), (Stamp, T)]

  def getOrCreate(k: K, path: java.nio.file.Path, create: () => T): T = cache.synchronized {
    val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
    val lastModified = attrs.lastModifiedTime()
    // only null on some platforms, but that's okay, we just use the last modified timestamp and size as our stamp
    val fileKey = attrs.fileKey()
    val stamp = Stamp(lastModified, attrs.size(), fileKey)
    val key = (k, path)
    cache.get(key) match {
      case Some((cachedStamp, cached)) if cachedStamp == stamp => cached
      case previous =>
        // Close the entry we're replacing: an open `ZipFile` would also hand its stale index to a new one of the
        // same file key and last modified time. A compiler still using it will reopen it.
        previous match
          case Some((_, closeable: AutoCloseable)) => closeable.close()
          case _ =>
        val value = create()
        cache.put(key, (stamp, value))
        value
    }
  }

  def clear(): Unit = cache.synchronized {
    cache.valuesIterator.foreach {
      case (_, closeable: AutoCloseable) => closeable.close()
      case _ =>
    }
    cache.clear()
  }
}
