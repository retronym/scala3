package dotty.tools
package dotc
package core

import java.util.concurrent.locks.ReentrantLock

import Symbols.Symbol

import _root_.dotty.tools.sharing.{ClaimRegistry, Sharing as Shared}


/** The completion lock of a `ContextBase` read by several threads
 *  (interactive mode, step 3 of `docs/_docs/internals/interactive-multi-reader.md`):
 *  a reentrant lock that also keeps what its holder is doing (the outermost
 *  acquisition, the root of the holder's work, and the innermost), so that a
 *  thread that has to wait records how long it waited and what it waited
 *  for (`Reader.recordLockWait`). The labels are kept as objects and only
 *  rendered by a thread that waited. Batch compilation never takes it.
 *
 *  With serialised readers, the base's `ClaimRegistry`: a claim takes the lock if its kind says so.
 */
final class CompletionLock(base: Contexts.ContextState) extends ReentrantLock, ClaimRegistry:
  import CompletionLock.What

  def claim[T](key: AnyRef, kind: Shared.ClaimKind[Nothing], cycleSubject: AnyRef, op: () => T): T =
    if kind.lockWhenSerialised then locked(kind.what)(op()) else op()

  // written by the holder only; read by waiters before they block
  @volatile private var rootWhat: What | Null = null
  @volatile private var currentWhat: What | Null = null
  @volatile private var holderName: String = ""
  // the holder's, touched only between `lockFor` having acquired the lock and `unlockFor` releasing it
  private val stack = new java.util.ArrayDeque[What]()

  /** Acquire the lock to do `what`. */
  def lockFor(what: What): Unit =
    if !tryLock() then
      // rendered when the wait begins, while the holder is still doing it
      // (contended acquisitions only)
      val root = String.valueOf(rootWhat)
      val current = String.valueOf(currentWhat)
      val holder = holderName
      val t0 = System.nanoTime()
      lock()
      base.reader.recordLockWait(System.nanoTime() - t0, root, current, holder)
    if getHoldCount == 1 then
      rootWhat = what
      holderName = Thread.currentThread.getName
    stack.push(what)
    currentWhat = what

  /** Release an acquisition made by `lockFor`. */
  def unlockFor(): Unit =
    stack.pop()
    currentWhat = stack.peek()
    if getHoldCount == 1 then
      rootWhat = null
      holderName = ""
    unlock()

  /** Who holds the lock and the root of what it is doing, or "" (for tools:
   *  the x-ray's owner-of-root display).
   */
  def holderNow: String =
    val r = rootWhat
    if r == null then "" else s"$holderName: ${r.render}"

  /** Run `op` holding the lock for `what`. */
  inline def locked[T](what: What)(inline op: T): T =
    lockFor(what)
    try op finally unlockFor()

object CompletionLock:

  /** What a holder of the lock or a claim is doing (`dotty.tools.sharing.What`), whose subject may be a symbol. */
  type What = _root_.dotty.tools.sharing.What
  def What(kind: String, subject: AnyRef | Null): What = SymbolWhat(kind, subject)

  /** A `What` that renders a symbol subject by its name and owner's name, read without a context (the waiter
   *  renders it after the fact; the denotation is the last known one).
   */
  private final class SymbolWhat(kind: String, subject: AnyRef | Null) extends What(kind, subject):
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
  val commit: What = What("commit typer state", null)
  val cleanup: What = What("cleanup", null)
  val peek: What = What("wait for a completion (isCompleting)", null)
  val readAction: What = What("read action (serialised mode)", null)
  val lazyRef: What = What("lazy reference", null)
  val lazyAnnotation: What = What("lazy annotation", null)
  val packageObjects: What = What("package objects", null)
  val scopeWrite: What = What("change to a published scope", null)
