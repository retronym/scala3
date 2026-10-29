package dotty.tools.dotc.classpath

import dotty.tools.io.AbstractFile

/** The x-ray's in-memory classpath (`dotty.tools.pc.xray.VirtualIO`), in this package because the classpath classes
 *  it builds on are private to it.
 */
object XrayClassPath:
  /** `VirtualDirectoryClassPath` that finds nested packages: `DirectoryLookup` asks for `java/lang/`, with a trailing
   *  separator, which `AbstractFile.lookupPath` resolves to an empty last name on a virtual directory. */
  private final class NestedVirtualDirectoryClassPath(dir: AbstractFile) extends VirtualDirectoryClassPath(dir):
    override protected def getSubDir(packageDirName: String): Option[AbstractFile] =
      super.getSubDir(packageDirName.stripSuffix(java.io.File.separator))

  /** The classpath of the virtual directories `roots`, in order. */
  def ofVirtualDirectories(roots: List[AbstractFile]): ClassPath =
    AggregateClassPath(roots.map(NestedVirtualDirectoryClassPath(_)))
