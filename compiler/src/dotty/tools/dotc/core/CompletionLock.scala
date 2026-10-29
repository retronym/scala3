package dotty.tools
package dotc
package core

import Symbols.Symbol

import _root_.dotty.tools.sharing.CoarseLock

/** The completion lock of a `ContextBase` read by several threads
 *  (interactive mode, step 3 of `docs/_docs/internals/interactive-multi-reader.md`):
 *  the library's `CoarseLock`, a reentrant lock that also keeps what its holder
 *  is doing, so that a thread that has to wait records how long it waited and
 *  what for (`Reader.recordLockWait`). Batch compilation never takes it.
 *
 *  With serialised readers, the base's `ClaimRegistry`: a claim takes the lock if its kind says so.
 */
object CompletionLock:
  /** The completion lock of `base`. */
  def apply(base: Contexts.ContextState): CoarseLock =
    CoarseLock((nanos, root, current, holder) => base.reader.recordLockWait(nanos, root, current, holder))

  /** What a holder of the lock or a claim is doing (`dotty.tools.sharing.What`), whose subject may be a symbol. */
  type What = _root_.dotty.tools.sharing.What
  def What(kind: String, subject: AnyRef | Null): What = SymbolWhat(kind, subject)

  /** A `What` that renders a symbol subject by its name and owner's name, read without a context (the waiter
   *  renders it after the fact; the denotation is the last known one).
   */
  // coarse: every claim but a Namer signature's guards more than one symbol (a TASTy file, a bring-forward, an
  // inherited cache), so a wait-for cycle through it is broken after a bound (I74)
  private final class SymbolWhat(kind: String, subject: AnyRef | Null) extends What(kind, subject, coarse = kind != namerKind):
    override protected def renderSubject(s: AnyRef): String = s match
      case sym: Symbol =>
        try
          val d = sym.lastKnownDenotation
          val owner = d.maybeOwner
          if owner ne Symbols.NoSymbol then s"${owner.lastKnownDenotation.name}.${d.name}" else d.name.toString
        catch case scala.util.control.NonFatal(_) => s"#${sym.id}"
      case other => super.renderSubject(other)

  type Label = _root_.dotty.tools.sharing.Label
  def Label(f: () => String): Label = _root_.dotty.tools.sharing.Label(f)

  /** The kind of a completion, from its completer. */
  def completionKind(completer: AnyRef): String = completer match
    case _: SourcefileLoader => "load source"
    case _: SymbolLoader => "load classpath"
    case c if c.getClass.getName.contains("TreeUnpickler") => "unpickle TASTy"
    case c if c.getClass.getName.contains("Namer") => namerKind
    case c => s"complete (${c.getClass.getSimpleName})"

  /** The kind of a Namer completion's claim: a root that is one symbol. */
  val namerKind: String = "Namer signature"

  val bringForward: What = What("bring forward", null)
  val inheritedCache: What = What("inherited cache", null)
  val lazyTastyRead: What = What("lazy TASTy read", null)
  val denotRing: What = What("new version of a denotation", null)
  val commit: What = What("commit typer state", null)
  val cleanup: What = What("cleanup", null)
  val peek: What = What("wait for a completion (isCompleting)", null)
  val readAction: What = What("read action (serialised mode)", null)
  val lazyRef: What = What("lazy reference", null)
  val lazyAnnotation: What = What("lazy annotation", null)
  val packageObjects: What = What("package objects", null)
  val scopeWrite: What = What("change to a published scope", null)
