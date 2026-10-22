package dotty.tools.dotc.classpath

import dotty.DottyBytecodeTest

import dotty.tools.dotc.core.Contexts.Context

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.file.{FileSystems, Files, Path}
import java.util.jar.Attributes
import java.util.jar.Attributes.Name
import org.junit.Assert.*
import org.junit.Test

import scala.jdk.CollectionConverters.*

class MultiReleaseJarTest extends DottyBytecodeTest {

  @Test
  def mrJar(): Unit = {

    val jar = Files.createTempFile("mr-jar-test-", ".jar")

    def classBytes(code: String): Array[Byte] =
      val outDir = compileCode(code :: Nil)
      getGeneratedClassfiles(outDir).head._2

    val defaultFooDef = "package p1; abstract class Foo { def foo1: Int }"
    val defaultBarDef = "package p2; abstract class Bar { def bar1: Int }"
    val java9FooDef = "package p1; abstract class Foo { def foo1: Int; def foo2: Int }"
    val java10BarDef = "package p2; abstract class Bar { def bar1: Int; def bar2: Int }"

    def apiMethods(jarPath: Path, release: String): Set[String] = {
      given ctx: Context = initCtx.fresh
      ctx.settings.Yusejavacp.update(true)
      ctx.settings.classpath.update(jarPath.toAbsolutePath.toString)
      ctx.settings.javaOutputVersion.update(release)
      ctx.initialize()
      val classNames = Seq("p1.Foo",  "p2.Bar")
      val classFiles = classNames.flatMap(ctx.platform.classPath.findClassFile)
      val classNodes = classFiles.map(classFile => loadClassNode(classFile.input))
      val methodNames = classNodes.flatMap(_.methods.asScala).map(_.name)
      methodNames.filter(_ != "<init>").toSet
    }

    try {
      createZip(jar, List(
        "/p1/Foo.class" -> classBytes(defaultFooDef),
        "/p2/Bar.class" -> classBytes(defaultBarDef),
        "/META-INF/versions/9/p1/Foo.class" -> classBytes(java9FooDef),
        "/META-INF/versions/10/p2/Bar.class" -> classBytes(java10BarDef),
        "/META-INF/MANIFEST.MF" -> createManifest)
      )

      // The same jar is seen by compilers with different `-release` in one JVM: the classpath cache must not
      // hand one compiler the multi-release view of another.
      assertEquals(Set("foo1", "bar1"), apiMethods(jar, "8"))
      assertEquals(Set("foo1", "foo2", "bar1"), apiMethods(jar, "9"))
      assertEquals(Set("foo1", "foo2", "bar1", "bar2"), apiMethods(jar, "10"))
      assertEquals(Set("foo1", "bar1"), apiMethods(jar, "8"))
    } finally
      try Files.deleteIfExists(jar)
      catch case _: IOException => ()
  }

  @Test
  def ctSymTest(): Unit = {

    def classExists(className: String, release: String): Boolean = {
      given ctx: Context = initCtx.fresh
      ctx.settings.Yusejavacp.update(true)
      ctx.settings.javaOutputVersion.update(release)
      ctx.initialize()
      val classFile = ctx.platform.classPath.findClassFile(className)
      classFile.isDefined
    }

    assertFalse(classExists("java.lang.invoke.LambdaMetafactory", "7"))
    assertTrue(classExists("java.lang.invoke.LambdaMetafactory", "8"))
    assertTrue(classExists("java.lang.invoke.LambdaMetafactory", "9"))
  }

  @Test
  def ctSymIsShared(): Unit = {
    def ctSymClassPath(release: String): ClassPath = {
      given ctx: Context = initCtx.fresh
      ctx.settings.Yusejavacp.update(true)
      ctx.settings.javaOutputVersion.update(release)
      ctx.initialize()
      val AggregateClassPath(entries) = ctx.platform.classPath: @unchecked
      entries.collectFirst { case cp: CtSymClassPath => cp }.get
    }

    // Compilers in one JVM with the same `-release` share the ct.sym classpath (and its zip file system),
    // and those with a different `-release` see their own release.
    val cp11 = ctSymClassPath("11")
    assertSame(cp11, ctSymClassPath("11"))
    val cp9 = ctSymClassPath("9")
    assertNotSame(cp11, cp9)
    assertTrue(cp11.findClassFile("java.net.http.HttpClient").isDefined)
    assertTrue(cp9.findClassFile("java.net.http.HttpClient").isEmpty)
  }

  private def createManifest = {
    val manifest = new java.util.jar.Manifest()
    manifest.getMainAttributes.put(Name.MANIFEST_VERSION, "1.0")
    manifest.getMainAttributes.put(new Attributes.Name("Multi-Release"), String.valueOf(true))
    val os = new ByteArrayOutputStream()
    manifest.write(os)
    val manifestBytes = os.toByteArray
    manifestBytes
  }

  private def createZip(zipLocation: Path, content: List[(String, Array[Byte])]): Unit = {
    val env = new java.util.HashMap[String, String]()
    Files.deleteIfExists(zipLocation)
    env.put("create", String.valueOf(true))
    val fileUri = zipLocation.toUri
    val zipUri = new java.net.URI("jar:" + fileUri.getScheme, fileUri.getPath, null)
    val zipfs = FileSystems.newFileSystem(zipUri, env)
    try {
      try {
        for ((internalPath, contentBytes) <- content) {
          val internalTargetPath = zipfs.getPath(internalPath)
          Files.createDirectories(internalTargetPath.getParent)
          Files.write(internalTargetPath, contentBytes)
        }
      } finally {
        if (zipfs != null) zipfs.close()
      }
    } finally {
      zipfs.close()
    }
  }

}
