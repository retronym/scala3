package dotty.tools.dotc.classpath

import org.junit.Test
import java.nio.file.*
import java.nio.file.attribute.FileTime

import dotty.tools.dotc.core.Contexts.{Context, ContextBase, ctx}
import dotty.tools.io.AbstractFile



class ZipAndJarFileLookupFactoryTest {
  @Test def cacheInvalidation(): Unit = {
    if (scala.util.Properties.isWin) return // can't overwrite an open file on windows.

    val f = Files.createTempFile("test-", ".jar")
    Files.delete(f)

    given Context = new ContextBase().initialCtx
    assert(!ctx.settings.YdisableFlatCpCaching.value) // we're testing with our JAR metadata caching enabled.

    def createCp = ZipAndJarClassPathFactory.create(AbstractFile.getFile(f).nn)
    try {
      createZip(f, Array(), "p1/C.class")
      createZip(f, Array(), "p2/X.class")
      createZip(f, Array(), "p3/Y.class")
      val cp1 = createCp
      assert(cp1.findClassFile("p1.C").isDefined)

      // We expect get a cache hit as the underlying zip hasn't changed
      val cp2 = createCp
      assert(cp2 eq cp1)

      // check things work after the cache hit
      cp1.findClassFile("p2.X").get.toByteArray

      val lastMod1 = Files.getLastModifiedTime(f)
      // Create a new zip at the same path with different contents and last modified
      Files.delete(f)
      createZip(f, Array(), "p1/D.class")
      Files.setLastModifiedTime(f, FileTime.fromMillis(lastMod1.toMillis + 2000))

      // Our classpath cache should create a new instance
      val cp3 = createCp
      assert(cp1 ne cp3, (System.identityHashCode(cp1), System.identityHashCode(cp3)))
      // And that instance should see D, not C, in package p1.
      assert(cp3.findClassFile("p1.C").isEmpty)
      assert(cp3.findClassFile("p1.D").isDefined)
    } finally Files.delete(f)
  }

  @Test def cacheInvalidationOnSizeChange(): Unit = {
    if (scala.util.Properties.isWin) return // can't overwrite an open file on windows.

    val f = Files.createTempFile("test-", ".jar")
    Files.delete(f)

    given Context = new ContextBase().initialCtx

    def createCp = ZipAndJarClassPathFactory.create(AbstractFile.getFile(f).nn)
    try {
      createZip(f, Array(), "p1/C.class")
      val cp1 = createCp
      assert(cp1.findClassFile("p1.C").isDefined)

      // Overwrite the jar in place (same file key), with a different size but the same last modified time,
      // as can happen on file systems with a coarse timestamp resolution.
      val lastMod1 = Files.getLastModifiedTime(f)
      val other = Files.createTempFile("test-", ".jar")
      Files.delete(other)
      createZip(other, Array(), "p1/LongerName.class")
      try Files.write(f, Files.readAllBytes(other))
      finally Files.delete(other)
      Files.setLastModifiedTime(f, lastMod1)

      // The size differs, so we expect a cache miss.
      // (We don't check the contents seen through `cp2`: `cp1` still holds the jar open, and
      // `java.util.zip.ZipFile` shares the open file's index with a new `ZipFile` of the same file key and
      // last modified time.)
      val cp2 = createCp
      assert(cp1 ne cp2)
    } finally Files.delete(f)
  }

  def createZip(zipLocation: Path, content: Array[Byte], internalPath: String): Unit = {
    val env = new java.util.HashMap[String, String]()
    env.put("create", String.valueOf(Files.notExists(zipLocation)))
    val fileUri = zipLocation.toUri
    val zipUri = new java.net.URI("jar:" + fileUri.getScheme, fileUri.getPath, null)
    val zipfs = FileSystems.newFileSystem(zipUri, env)
    try {
      try {
        val internalTargetPath = zipfs.getPath(internalPath)
        Files.createDirectories(internalTargetPath.getParent)
        Files.write(internalTargetPath, content)
      } finally {
        if (zipfs != null) zipfs.close()
      }
    } finally {
      zipfs.close()
    }
  }
}
