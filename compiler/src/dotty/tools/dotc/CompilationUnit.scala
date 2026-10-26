package dotty.tools
package dotc

import core.*
import Contexts.*
import SymDenotations.ClassDenotation
import Symbols.*
import Comments.Comment
import util.{FreshNameCreator, SourceFile, NoSource}
import util.Spans.Span
import ast.{tpd, untpd}
import tpd.{Tree, TreeTraverser}
import ast.Trees.{Import, Ident}
import typer.Nullables
import core.Decorators.*
import config.{SourceVersion, Feature}
import scala.annotation.internal.{benignRace, cache, confined, featureArea, guardedBy, publishedBy, scratch, sharable, treeState}
import scala.util.control.{ControlThrowable, NoStackTrace}
import transform.MacroAnnotations.isMacroAnnotation
import scala.io.Codec

class CompilationUnit protected (val source: SourceFile, val info: CompilationUnitInfo | Null) {

  override def toString: String = source.toString

  // Written by the parser or the namer before the unit is shared, and after that only by the interactive driver's write
  // actions (`adopt`, the body edit), which run with no reader.
  @treeState @guardedBy("writer") var untpdTree: untpd.Tree = untpd.EmptyTree

  // A persistent value: a reader takes the current tree and a resume publishes a new one with its statements spliced in
  // (docs/_docs/internals/interactive-multi-reader.md §3.2). The splices are serialised by the session's monitor, which
  // is not this unit's and is held only for the read-modify-write, so the reads here do not hold it: a reader sees the
  // tree before or after a splice, both complete. Phases write it in the batch pipeline, one thread.
  @treeState @publishedBy("volatile") @volatile var tpdTree: tpd.Tree = tpd.EmptyTree

  private val myIsJava: Boolean = source.ext.isJava // `source` and `ext` are immutable, so cache the answer

  /** Is this the compilation unit of a Java file */
  def isJava: Boolean = myIsJava

  /** Is this the compilation unit of a Java file, or TASTy derived from a Java file */
  def typedAsJava: Boolean =
    val ext = source.ext
    ext.isJava || ext.isTasty && tastyInfo.exists(_.attributes.isJava)

  def tastyInfo: Option[TastyInfo] =
    val local = info
    if local == null then None else local.tastyInfo


  /** The source version for this unit, as determined by a language import */
  @treeState @publishedBy("init") var sourceVersion: Option[SourceVersion] = None

  /** Pickled TASTY binaries, indexed by class. */
  // set by the Pickler (or ReadTasty), after typer
  @scratch @confined var pickled: Map[ClassSymbol, () => Array[Byte]] = Map()

  /** The fresh name creator for the current unit.
   *  FIXME(#7661): This is not fine-grained enough to enable reproducible builds,
   *  see https://github.com/scala/scala/commit/f50ec3c866263448d803139e119b33afb04ec2bc
   */
  val freshNames: FreshNameCreator = new FreshNameCreator.Default

  /** Will be set to `true` if there are inline call that must be inlined after typer.
   *  The information is used in phase `Inlining` in order to avoid traversing trees that need no transformations.
   */
  // set after typer, by PostTyper and the phases that follow it
  @scratch @confined var needsInlining: Boolean = false

  /** Set to `true` if there are inline traits (with or without Specialized context bounds)
   *  This is used by:
   *  DesugarSpecializedTraits, SpecializeInlineTraits, PruneInlinedMethods, PruneInlineTraits
   */
  @scratch @confined var hasSpecializations: Boolean = false

  @scratch @confined var hasMacroAnnotations: Boolean = false

  def hasUnrollDefs: Boolean = unrolledClasses.nonEmpty
  @scratch @confined var unrolledClasses: Set[Symbol] = Set.empty

  /** Set to `true` if inliner added anonymous mirrors that need to be completed */
  // set by the Synthesizer only after typer
  @scratch @confined var needsMirrorSupport: Boolean = false

  /** Will be set to `true` if contains `Quote`.
   *  The information is used in phase `Staging`/`Splicing`/`PickleQuotes` in order to avoid traversing trees that need no transformations.
   */
  // Monotone: every writer stores true, among them the typer of an inlined body, which concurrent readers of the unit
  // may be; it is read by the phases after typer.
  @scratch @benignRace("every writer stores true; read after typer") var needsStaging: Boolean = false

  /** Will be set to true if the unit contains a captureChecking language import */
  // the language imports: set when the unit is parsed (or unpickled), before it is shared
  @featureArea("cc") @treeState @publishedBy("init") var needsCaptureChecking: Boolean = false

  /** Will be set to true if the unit contains a captureChecking language import */
  @treeState @publishedBy("init") var needsSeparationChecking: Boolean = false

  /** Will be set to true if unit was compiled with `safe` language import. */
  @treeState @publishedBy("init") var safeMode: Boolean = false

  /** Will be set to true if unit was compiled with `magic` language import. */
  @treeState @publishedBy("init") var magic: Boolean = false

  /** Will be set to true if the unit contains a pureFunctions language import */
  @treeState @publishedBy("init") var knowsPureFuns: Boolean = false
  
  /** Will be set to true if the unit contains an inlineTrait language import */
  @treeState @publishedBy("init") var knowsInlineTraits: Boolean = false

  // Only ever set to true, under the run's `suspension` monitor (`suspend`); the typer phase reads it as a hint, and
  // a new run resets it with no reader.
  @scratch @benignRace("set true only, under the monitor of the run's `suspension`; a hint when read")
  var suspended: Boolean = false
  @scratch @benignRace("set true only, under the monitor of the run's `suspension`")
  var suspendedAtInliningPhase: Boolean = false

  /** Can this compilation unit be suspended */
  def isSuspendable: Boolean = true

  /** List of all comments present in this compilation unit */
  @treeState @publishedBy("init") var comments: List[Comment] = Nil

  /** This is used to record dependencies to invalidate during incremental
   *  compilation, but only if `ctx.runZincPhases` is true.
   */
  val depRecorder: sbt.DependencyRecorder = sbt.DependencyRecorder()

  /** Suspends the compilation unit by throwing a SuspendException
   *  and recording the suspended compilation unit
   */
  def suspend(hint: => String)(using Context): Nothing =
    assert(isSuspendable)
    // Clear references to symbols that may become stale. No need to call
    // `depRecorder.sendToZinc()` since all compilation phases will be rerun
    // when this unit is unsuspended.
    depRecorder.clear()
    if !suspended then
      ctx.run.nn.suspension.add(this, hint, ctx.phase == Phases.inliningPhase)
    throw CompilationUnit.SuspendException()

  @cache @benignRace("any reader derives an equal immutable map from the unit's untyped tree")
  private var myAssignmentSpans: Map[Int, List[Span]] | Null = null

  /** A map from (name-) offsets of all local variables in this compilation unit
   *  that can be tracked for being not null to the list of spans of assignments
   *  to these variables.
   */
  def assignmentSpans(using Context): Map[Int, List[Span]] =
    myAssignmentSpans match
      case null =>
        val spans = Nullables.assignmentSpans
        myAssignmentSpans = spans
        spans
      case spans => spans
}

@sharable object NoCompilationUnit extends CompilationUnit(NoSource, info = null) {

  override def isJava: Boolean = false

  override def suspend(hint: => String)(using Context): Nothing =
    throw CompilationUnit.SuspendException()

  override def assignmentSpans(using Context): Map[Int, List[Span]] = Map.empty
}

object CompilationUnit {

  class SuspendException extends Exception with NoStackTrace

  /** Thrown from `Reader.checkCancelledTyping` to abort an interactive run whose
   *  progress callback reports cancellation. The run's partial results are
   *  discarded by the catcher (`InteractiveDriver.run`); lazy state that was
   *  being computed when the exception unwinds (symbol completions, lazy
   *  annotations, symbol loaders) is reset so that the next run recomputes it.
   *  The cause is a `CancellationException` so that clients which classify
   *  exceptions by their cause chain (e.g. Metals) treat it as a cancellation.
   */
  class CancelledCompilation(reason: String = "") extends ControlThrowable:
    @scratch @publishedBy("init") private val myCause = new java.util.concurrent.CancellationException("compilation cancelled")
    override def getCause: Throwable = myCause
    /** Why: a checker, a pending writer, or a completion an earlier cancellation unwound (`UnwoundCompletion`), for
     *  the one who meets it where no checker was installed. */
    override def getMessage: String = if reason.isEmpty then "compilation cancelled" else reason

  /** Make a compilation unit for top class `clsd` with the contents of the `unpickled` tree */
  def apply(clsd: ClassDenotation, unpickled: Tree, forceTrees: Boolean)(using Context): CompilationUnit =
    val compilationUnitInfo = clsd.symbol.compilationUnitInfo.nn
    val file = compilationUnitInfo.associatedFile
    apply(SourceFile(file, ctx.settings.sourceroot.value, Codec(ctx.settings.encoding.value)), unpickled, forceTrees, compilationUnitInfo)

  /** Make a compilation unit, given picked bytes and unpickled tree */
  def apply(source: SourceFile, unpickled: Tree, forceTrees: Boolean, info: CompilationUnitInfo)(using Context): CompilationUnit = {
    assert(!unpickled.isEmpty, unpickled)
    val unit1 = new CompilationUnit(source, info)
    unit1.tpdTree = unpickled
    if (forceTrees) {
      val force = new Force
      force.traverse(unit1.tpdTree)
      unit1.needsStaging = force.containsQuote
      unit1.needsInlining = force.containsInline
      unit1.hasMacroAnnotations = force.containsMacroAnnotation
    }
    unit1
  }

  /** Create a compilation unit corresponding to an in-memory String.
   *  Used for `compiletime.testing.typeChecks`.
   */
  def apply(path: String, source: String): CompilationUnit = {
    val src = SourceFile.virtual(path, source)
    new CompilationUnit(src, null)
  }

  /** Create a compilation unit corresponding to `source`.
   *  If `mustExistIfNotNull` is true, this will fail if `source` is not null but does not exist.
   */
  def apply(source: SourceFile, mustExistIfNotNull: Boolean = true)(using Context): CompilationUnit = {
    val file = source.file
    val src =
      if (!mustExistIfNotNull)
        source
      else if (file == null) {
        source
      }
      else if (file.isDirectory) {
        report.error(em"expected file, received directory '${source.path}'")
        NoSource
      }
      else if(!file.exists) {
        report.error(em"source file not found: ${source.path}")
        NoSource
      }
      else source
    val info = if src.exists then CompilationUnitInfo(src.file) else null
    new CompilationUnit(src, info)
  }

  /** Force the tree to be loaded */
  private class Force extends TreeTraverser {
    var containsQuote = false
    var containsInline = false
    var containsCaptureChecking = false
    var containsMacroAnnotation = false
    def traverse(tree: Tree)(using Context): Unit = {
      if tree.symbol.is(Flags.Inline) then
        containsInline = true
      tree match
        case _: tpd.Quote =>
          containsQuote = true
        case tree: tpd.Apply if tree.symbol == defn.QuotedTypeModule_of =>
          containsQuote = true
        case Import(qual, selectors) =>
          tpd.languageImport(qual) match
            case Some(prefix) =>
              for case untpd.ImportSelector(untpd.Ident(imported), untpd.EmptyTree, _) <- selectors do
                Feature.handleGlobalLanguageImport(prefix, imported)
            case _ =>
        case _ =>
      for annot <- tree.symbol.annotations do
        if annot.isMacroAnnotation then
          ctx.compilationUnit.hasMacroAnnotations = true
      traverseChildren(tree)
    }
  }
}
