package dotty.tools.pc

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util as ju

import scala.jdk.CollectionConverters.*
import scala.meta.pc.SemanticdbFileManager
import scala.meta.pc.SourcePathMode

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.{
  CachedLogicalPackage,
  InteractiveDriver,
  LogicalPackagesProvider,
  ParsedLogicalPackage
}
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

/** CachingDriver is a wrapper class that provides a compilation cache for
 *  InteractiveDriver. CachingDriver skips running compilation if
 *    - the target URI of `run` is the same as the previous target URI
 *    - the content didn't change since the last compilation.
 *
 *  This compilation cache enables Metals to skip compilation and re-use the
 *  typed tree under the situation like developers sequentially hover on the
 *  symbols in the same file without any changes.
 *
 *  Note: we decided to cache only if the target URI is the same as in the
 *  previous run because of `InteractiveDriver.currentCtx` that should return
 *  the context that refers to the last compiled source file. It would be ideal
 *  if we could update currentCtx even when we skip the compilation, but we
 *  struggled to do that. See the discussion
 *  https://github.com/scalameta/metals/pull/4225#discussion_r941138403 To avoid
 *  the complexity related to currentCtx, we decided to cache only when the
 *  target URI only if the same as the previous run.
 */
class CachingDriver private (
    override val settings: List[String],
    sourcePackage: CachedLogicalPackage
) extends InteractiveDriver(settings, sourcePackage):

  private var lastCompiledURI: URI | Null = null
  private var previousDiags = List.empty[Diagnostic]

  private def alreadyCompiled(uri: URI, content: String): Boolean =
    lastCompiledURI == uri && compilationUnits.get(uri).forall(_.source.textContent() == content)

  override def run(uri: URI, source: SourceFile): List[Diagnostic] =
    if !alreadyCompiled(uri, source.textContent()) then
      try previousDiags = super.run(uri, source)
      catch case ex: CompilationUnit.CancelledCompilation =>
        // The aborted run's `newRun` already reset the caches of the context
        // base (`ContextBase.reset`), so the previous run's trees can no longer
        // be served from the cache: the next request compiles the file again.
        lastCompiledURI = null
        throw ex
    lastCompiledURI = uri
    previousDiags

  /** A targeted run leaves a prefix of the typed tree in the compilation unit
   *  of `uri`: it is served as the result of `run` only once it covers the
   *  whole file. Conversely a whole-file run on the same content serves any
   *  target.
   */
  override def runUpTo(uri: URI, source: SourceFile, target: Span): List[Diagnostic] =
    if !targetedRequests then run(uri, source) // the driver's own check would call its `run`, not this caching one
    else if alreadyCompiled(uri, source.textContent()) then previousDiags
    else
      lastCompiledURI = null
      val diags = super.runUpTo(uri, source, target)
      if isFullyTyped(uri) then
        lastCompiledURI = uri
        previousDiags = diags
      diags

  def freshDriver(): InteractiveDriver =
    new InteractiveDriver(settings, sourcePackage)

end CachingDriver

object CachingDriver:
  def apply(
      settings: List[String],
      sourcePath: ju.function.Supplier[ju.List[Path]],
      semanticdbFileManager: SemanticdbFileManager,
      sourcePathMode: SourcePathMode
  ): CachingDriver =
    def sourcePackagesExtractor(using Context) = sourcePathMode match
      case SourcePathMode.DISABLED => None
      case SourcePathMode.PRUNED | SourcePathMode.FULL =>
        val sourcePathFiles = sourcePath.get().asScala.toSeq
        val logicalSourcePath = sourcePathFiles.mkString(File.pathSeparator)
        if sourcePathFiles.nonEmpty then Some(new LogicalPackagesProvider(logicalSourcePath).root) else None
      case SourcePathMode.MBT =>
        Some(ParsedLogicalPackage.fromMbtIndex(semanticdbFileManager.listAllPackages()))
    new CachingDriver(settings, CachedLogicalPackage(sourcePackagesExtractor))
