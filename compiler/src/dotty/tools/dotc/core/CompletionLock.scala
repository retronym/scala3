package dotty.tools
package dotc
package core

import java.util.concurrent.locks.ReentrantLock

import Symbols.Symbol

import scala.annotation.internal.{guardedBy, publishedBy, scratch}

/** The completion lock of a `ContextBase` read by several threads
 *  (interactive mode, step 3 of `docs/_docs/internals/interactive-multi-reader.md`):
 *  a reentrant lock that also keeps what its holder is doing (the outermost
 *  acquisition, the root of the holder's work, and the innermost), so that a
 *  thread that has to wait records how long it waited and what it waited
 *  for (`Reader.recordLockWait`). The labels are kept as objects and only
 *  rendered by a thread that waited. Batch compilation never takes it.
 */
final class CompletionLock(base: Contexts.ContextState) extends ReentrantLock:
  import CompletionLock.What

  // written by the holder only; read by waiters before they block
  @scratch @publishedBy("packed") @volatile private var rootWhat: What | Null = null
  @scratch @publishedBy("packed") @volatile private var currentWhat: What | Null = null
  @scratch @publishedBy("volatile") @volatile private var holderName: String = ""
  // the holder's, touched only between `lockFor` having acquired the lock and `unlockFor` releasing it
  @scratch @guardedBy("completionLock")
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

  /** What a holder of the lock is doing: a kind and an optional subject (a
   *  symbol, a completer), rendered only when a waiter reports it.
   */
  final class What(val kind: String, val subject: AnyRef | Null):
    def render: String = subject match
      case null => kind
      case sym: Symbol => s"$kind ${nameOf(sym)}"
      case str: String => s"$kind $str"
      case l: Label => s"$kind ${l.text}"
      case other => s"$kind ${other.getClass.getSimpleName}"
    override def toString: String = render

  /** A symbol's name and owner's name, read without a context (the waiter
   *  renders it after the fact; the denotation is the last known one).
   */
  private def nameOf(sym: Symbol): String =
    try
      val d = sym.lastKnownDenotation
      val owner = d.maybeOwner
      if owner ne Symbols.NoSymbol then s"${owner.lastKnownDenotation.name}.${d.name}" else d.name.toString
    catch case scala.util.control.NonFatal(_) => s"#${sym.id}"

  /** A subject rendered when a waiter reports it (e.g. the statement a pass
   *  is typing by then). */
  final class Label(f: () => String):
    def text: String = f()

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
