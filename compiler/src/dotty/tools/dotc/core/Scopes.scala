/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */

package dotty.tools
package dotc
package core

import scala.annotation.internal.{benignRace, cache, confined, frozenAfter, interned, publishedBy}
import Symbols.*
import Types.{TermRef, NoPrefix}
import Flags.*
import Names.*
import Contexts.*
import Phases.*
import Denotations.*
import printing.Texts.*
import printing.Printer
import SymDenotations.NoDenotation

import collection.mutable
import java.lang.invoke.VarHandle

object Scopes {

  /** Maximal fill factor of hash table */
  private inline val FillFactor = 2.0/3.0

  /** A hashtable is created once current size exceeds MinHash * FillFactor
   *  The initial hash table has twice that size (i.e 16).
   *  This value must be a power of two, so that the index of an element can
   *  be computed as element.hashCode & (hashTable.length - 1)
   */
  private inline val MinHashedScopeSize = 8

  /** The maximal permissible number of recursions when creating
   *  a hashtable
   */
  private inline val MaxRecursions = 1000

  /** A function that optionally produces synthesized symbols with
   *  the given name in the given context. Returns `NoSymbol` if the
   *  no symbol should be synthesized for the given name.
   */
  private type SymbolSynthesizer = Name => Context ?=> Symbol

  /** Diagnostics of changes to published scopes. With `-Ddotc.scopeWrites=<file>`, each change is counted by phase
   *  and writer, and the counts are written to the file when the JVM exits. A change outside a write action on a
   *  concurrent base (`unfrozen`) is counted the same way, under `UNFROZEN`, and fails under `-Ycheck-frozen-scopes`.
   */
  object ScopeWrites:
    private val path = System.getProperty("dotc.scopeWrites")
    val tracing: Boolean = path != null
    private val counts = new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.atomic.LongAdder]()

    private def writer: String =
      import scala.jdk.CollectionConverters.*
      StackWalker.getInstance().nn.walk { s =>
        s.nn.map(f => s"${f.nn.getClassName.nn.stripPrefix("dotty.tools.dotc.")}.${f.nn.getMethodName}")
          .nn.filter(f => !f.nn.startsWith("core.Scopes"))
          .nn.limit(8).nn.collect(java.util.stream.Collectors.toList())
      }.nn.asScala.mkString(" <- ")

    def record(kind: String)(using Context): Unit =
      counts.computeIfAbsent(s"${ctx.phase.phaseName} $kind <- $writer", _ => new java.util.concurrent.atomic.LongAdder()).nn.increment()

    def unfrozen(kind: String)(using Context): Unit =
      if tracing then
        counts.computeIfAbsent(s"UNFROZEN ${ctx.phase.phaseName} $kind <- $writer", _ => new java.util.concurrent.atomic.LongAdder()).nn.increment()
      if ctx.settings.YcheckFrozenScopes.value then
        throw AssertionError(s"a published scope changed ($kind) outside a write action: $writer")

    if tracing then
      Runtime.getRuntime.nn.addShutdownHook(Thread(() =>
        import scala.jdk.CollectionConverters.*
        val lines = counts.asScala.toList.map((k, v) => (v.sum, k)).sortBy(-_._1).map((n, k) => s"$n\t$k")
        java.nio.file.Files.write(java.nio.file.Paths.get(path), lines.asJava): Unit))

  class ScopeEntry private[Scopes] (val name: Name, _sym: Symbol, val owner: Scope) {

    // the scope's one writer changes an entry of a published scope (`MutableScope.writing`), or a write action
    @frozenAfter("completion") var sym: Symbol = _sym

    /** the next entry in the hash bucket
     */
    @frozenAfter("completion") var tail: ScopeEntry | Null = null

    /** the preceding entry in this scope
     */
    @frozenAfter("completion") var prev: ScopeEntry | Null = null

    override def toString: String = sym.toString
  }

  /** A scope contains a set of symbols. It can be an extension
   *  of some outer scope, from which it inherits all symbols.
   *  This class does not have any methods to add symbols to a scope
   *  or to delete them. These methods are provided by subclass
   *  MutableScope.
   */
  abstract class Scope extends printing.Showable {

    /** The last scope-entry from which all others are reachable via `prev` */
    private[dotc] def lastEntry: ScopeEntry | Null

    /** The number of symbols in this scope (including inherited ones
     *  from outer scopes).
     */
    def size: Int

    /** The number of scopes enclosing this scope. */
    def nestingLevel: Int

    /** The symbols in this scope in the order they were entered;
     *  inherited from outer ones first.
     */
    def toList(using Context): List[Symbol]

    /** Return all symbols as an iterator in the order they were entered in this scope.
     */
    def iterator(using Context): Iterator[Symbol] = toList.iterator

    /** Is the scope empty? */
    def isEmpty: Boolean = lastEntry == null

    /** Applies a function f to all Symbols of this Scope. */
    def foreach[U](f: Symbol => U)(using Context): Unit = toList.foreach(f)

    /** Selects all Symbols of this Scope which satisfy a predicate. */
    def filter(p: Symbol => Boolean)(using Context): List[Symbol] = toList.filter(p)

    /** Tests whether a predicate holds for at least one Symbol of this Scope. */
    def exists(p: Symbol => Boolean)(using Context): Boolean = toList.exists(p)

    /** Finds the first Symbol of this Scope satisfying a predicate, if any. */
    def find(p: Symbol => Boolean)(using Context): Symbol = toList.find(p).getOrElse(NoSymbol)

    /** Returns a new mutable scope with the same content as this one. */
    def cloneScope(using Context): MutableScope

    /** Lookup a symbol entry matching given name. */
    def lookupEntry(name: Name)(using Context): ScopeEntry | Null

    /** Lookup next entry with same name as this one */
    def lookupNextEntry(entry: ScopeEntry)(using Context): ScopeEntry | Null

    /** Lookup a symbol */
    final def lookup(name: Name)(using Context): Symbol = {
      val e = lookupEntry(name)
      if (e == null) NoSymbol else e.sym
    }

    /** Returns an iterator yielding every symbol with given name in this scope.
     */
    final def lookupAll(name: Name)(using Context): Iterator[Symbol] = new Iterator[Symbol] {
      var e = lookupEntry(name)
      def hasNext: Boolean = e != null
      def next(): Symbol = e match
        case null => throw new NoSuchElementException()
        case ee =>
          e = lookupNextEntry(ee)
          ee.sym
    }

    /** Does this scope contain a reference to `sym` when looking up `name`? */
    final def contains(name: Name, sym: Symbol)(using Context): Boolean =
      var e = lookupEntry(name)
      while e != null && e.sym != sym do e = lookupNextEntry(e)
      e != null

    /** The denotation set of all the symbols with given name in this scope
     *  Symbols occur in the result in reverse order relative to their occurrence
     *  in `this.toList`.
     */
    final def denotsNamed(name: Name)(using Context): PreDenotation = {
      var syms: PreDenotation = NoDenotation
      var e = lookupEntry(name)
      while (e != null) {
        syms = syms `union` e.sym.denot
        e = lookupNextEntry(e)
      }
      syms
    }

    /** The scope that keeps only those symbols from this scope that match the
     *  given predicates, renamed with the given rename function.
     *  If renaming is not needed for a symbol, the rename function should return `null`.
     *  If all symbols match and none are renamed, returns the scope itself, otherwise
     *  a copy with the matching and renamed symbols.
     */
    final def filteredScope(
        keep: Symbol => Boolean,
        rename: Symbol => Name | Null = _ => null)(using Context): Scope =
      var result: MutableScope | Null = null
      for sym <- iterator do
        def drop() =
          if result == null then result = cloneScope
          result.nn.unlink(sym)
        if keep(sym) then
          val newName = rename(sym)
          if newName != null then
            drop()
            result.nn.enter(newName, sym)
        else
          drop()
      // TODO: `if result == null then this else result` should work here, improve flow typing to handle it
      result match
        case null => this
        case r => r

    def implicitDecls(using Context): List[TermRef] = Nil

    def openForMutations: MutableScope = unsupported("openForMutations")

    final def toText(printer: Printer): Text = printer.toText(this)

    def checkConsistent()(using Context): Unit = ()

    /** Ensure that all elements of this scope have been entered.
     *  Overridden by SymbolLoaders.PackageLoader#PackageScope, where it
     *  makes sure that all names with `$`'s have been added.
     */
    protected def ensureComplete()(using Context): Unit = ()
  }

  /** A subclass of Scope that defines methods for entering and
   *  unlinking entries.
   *  Note: constructor is protected to force everyone to use the factory methods instead.
   */
  class MutableScope protected[Scopes](initElems: ScopeEntry | Null, initSize: Int, val nestingLevel: Int)
      extends Scope {

    /** Scope shares elements with `base` */
    protected[Scopes] def this(base: Scope)(using Context) =
      this(base.lastEntry, base.size, base.nestingLevel + 1)
      ensureCapacity(MinHashedScopeSize)

    def this(nestingLevel: Int) = this(null, 0, nestingLevel)

    // filled while hidden, then frozen: after its owner's completion publishes the scope (`published`) only its one
    // writer (`writing`) or a write action changes it, building the new entries aside (`extendAside`)
    @frozenAfter("completion") private[dotc] var lastEntry: ScopeEntry | Null = initElems

    /** The size of the scope */
    @frozenAfter("completion") private var _size = initSize

    override final def size: Int = _size

    /** the hash table
     */
    @frozenAfter("completion") private var hashTable: Array[ScopeEntry | Null] | Null = null

    /** a cache for all elements, to be used by symbol iterator.
     */
    @cache @benignRace("an immutable list of the entries; a reader that misses builds the same list, and a change nulls it. After the scope is published a change runs only in a write action, with no reader to store an older list after it")
    private var elemsCache: List[Symbol] | Null = null

    /** The synthesizer to be used, or `null` if no synthesis is done on this scope */
    @publishedBy("init") private var synthesize: SymbolSynthesizer | Null = null

    /** The entries the synthesizer made, by the name looked up: kept beside the scope, never entered into it, so that
     *  what enumerates the scope (`toList`, member names, `-Wshadow`) does not depend on which synthetic names earlier
     *  code happened to mention, and one name has one symbol however many readers ask for it at once. Shared with
     *  the scope's clones.
     */
    @interned private var synthesized: java.util.concurrent.ConcurrentHashMap[Name, ScopeEntry] | Null = null

    /** Which names the synthesizer can make: a lookup that misses consults the map of synthesized entries only for
     *  those, so a miss on any other name costs what it did before the map.
     */
    @publishedBy("init") private var synthesizable: (Name => Context ?=> Boolean) | Null = null

    /** Use specified synthesize for this scope, for the names `canSynthesize` accepts */
    def useSynthesizer(canSynthesize: Name => Context ?=> Boolean)(s: SymbolSynthesizer)(using Context): Unit =
      // the package's own loader may have published the scope already, but no reader exists until `init` returns
      assert(!ctx.definitions.initialized, "a synthesizer is installed while the base is set up, before any reader")
      synthesizable = canSynthesize
      synthesize = s
      synthesized = new java.util.concurrent.ConcurrentHashMap

    protected def newScopeLikeThis(): MutableScope = new MutableScope(nestingLevel)

    /** Clone scope, taking care not to force the denotations of any symbols in the scope.
     */
    def cloneScope(using Context): MutableScope = {
      val entries = new mutable.ArrayBuffer[ScopeEntry]
      var e = lastEntry
      while ((e != null) && e.owner == this) {
        entries += e
        e = e.prev
      }
      val scope = newScopeLikeThis()
      for (i <- entries.length - 1 to 0 by -1) {
        val e = entries(i)
        scope.newScopeEntry(e.name, e.sym)
      }
      scope.synthesizable = synthesizable
      scope.synthesize = synthesize
      scope.synthesized = synthesized
      scope
    }

    // the scope whose entries this one makes, when it is built aside to extend that scope (`extendAside`)
    @confined private var extending: MutableScope | Null = null

    /** Set when the owner's completion publishes this scope (`SymDenotation.lockAndComplete0`). A change after
     *  that bumps the base's `structureEpoch`, and on a concurrent base must be made by a write action: a scope is
     *  filled while it is hidden and then frozen (`changed`).
     */
    @frozenAfter("completion") private[dotc] var published = false

    /** `op`'s changes to this scope as one: on a batch base the flat classes of a package are entered in place, one
     *  structural change however many classes they are, as `extendAside` publishes them on a concurrent base.
     */
    private[core] final def asOneChange(op: => Unit)(using Context): Unit =
      val was = published
      published = false
      try op
      finally
        published = was
        changed("extend")

    // the thread changing this scope as its one writer (`writing`)
    @benignRace("written by the writer under the scope's claim; read only to compare with this thread, which only its own store can make equal")
    private var writer: Thread | Null = null

    /** `op` as this scope's one writer: on a concurrent base, holding a claim keyed by the scope, which is how a
     *  published scope may change outside a write action (a package's flat classes, a classfile completer's deletion
     *  of the symbols it found absent). Readers stay lock free: an entry is added by `extendAside`, which replaces the
     *  scope whole, or removed by `unlink`, which leaves the removed entry's own links as they were.
     */
    private[core] final def writing[T](op: => T)(using Context): T =
      if !ctx.base.concurrent then op
      else Sharing.exclusive[this.type, T](this, CompletionLock.scopeWrite, this):
        val was = writer
        writer = Thread.currentThread
        try op finally writer = was

    // a change during the base's setup (`Definitions.init`) has no reader
    private def changed(kind: String)(using Context): Unit =
      if published then
        val base = ctx.base
        base.bumpStructureEpoch()
        if ScopeWrites.tracing then ScopeWrites.record(kind)
        if base.concurrent && (writer ne Thread.currentThread) && (base.writeActionThread ne Thread.currentThread)
            && ctx.definitions.initialized then
          ScopeWrites.unfrozen(kind)

    /** create and enter a scope entry with given name and symbol */
    protected def newScopeEntry(name: Name, sym: Symbol)(using Context): ScopeEntry = {
      ensureCapacity(hashTable match { case null => MinHashedScopeSize; case ht => ht.length })
      val e = new ScopeEntry(name, sym, if extending == null then this else extending.nn)
      e.prev = lastEntry
      lastEntry = e
      val ht = hashTable
      if (ht != null) enterInHash(e, ht)
      _size += 1
      elemsCache = null
      changed("enter")
      e
    }

    private def enterInHash(e: ScopeEntry, ht: Array[ScopeEntry | Null])(using Context): Unit = {
      val idx = e.name.hashCode & (ht.length - 1)
      e.tail = ht(idx)
      assert(e.tail != e)
      ht(idx) = e
    }

    /** With several readers: extend this scope as a whole, without changing what a reader may be walking. `fill`
     *  enters into a scope built aside from copies of this scope's entries (owned by this scope); then the copies
     *  and their table replace this scope's, the table last and after a release fence. A reader finds the old
     *  table with its chains untouched, or the new one complete (`lookupEntry` pairs the load with an acquire on a
     *  concurrent base). The caller makes it the only writer (`PackageLoader`: the loader's claim).
     */
    private[core] final def extendAside(fill: MutableScope => Unit)(using Context): Unit =
      val aside = newScopeLikeThis()
      aside.extending = this
      val entries = new mutable.ArrayBuffer[ScopeEntry]
      var e = lastEntry
      while e != null && e.owner == this do
        entries += e
        e = e.prev
      for i <- entries.length - 1 to 0 by -1 do aside.newScopeEntry(entries(i).name, entries(i).sym)
      fill(aside)
      lastEntry = aside.lastEntry
      _size = aside._size
      elemsCache = null
      VarHandle.releaseFence()
      hashTable = aside.hashTable
      changed("extend")

    /** enter a symbol in this scope. */
    final def enter[T <: Symbol](sym: T)(using Context): T = {
      if (sym.isType && ctx.phaseId <= typerPhase.id)
        assert(lookup(sym.name) == NoSymbol,
          s"duplicate ${sym.debugString}; previous was ${lookup(sym.name).debugString}") // !!! DEBUG
      enter(sym.name, sym)
    }

    final def enter[T <: Symbol](name: Name, sym: T)(using Context): T = {
      // a published scope on a concurrent base, outside a write action and its one writer: replaced whole, since an
      // enter in place can rehash, which rewrites the chains a reader may be walking
      if needsWriter then writing(extendAside(_.enter(name, sym)))
      else newScopeEntry(name, sym)
      sym
    }

    // a change to this scope must be made by its one writer (`writing`): it is published, the base concurrent, and
    // the change is not made by a write action or by the writer already
    private def needsWriter(using Context): Boolean =
      published && ctx.base.concurrent && (writer ne Thread.currentThread)
        && (ctx.base.writeActionThread ne Thread.currentThread) && ctx.definitions.initialized

    /** enter a symbol, asserting that no symbol with same name exists in scope */
    final def enterUnique(sym: Symbol)(using Context): Unit = {
      assert(lookup(sym.name) == NoSymbol, (sym.showLocated, lookup(sym.name).showLocated))
      enter(sym)
    }

    private def ensureCapacity(tableSize: Int)(using Context): Unit =
      if (size >= tableSize * FillFactor) createHash(tableSize * 2)

    private def createHash(tableSize: Int)(using Context): Unit =
      if (size > tableSize * FillFactor) createHash(tableSize * 2)
      else {
        // filled, then published: a lookup on another reader finds the old table or the new one, never one that is
        // still being filled
        val ht = new Array[ScopeEntry | Null](tableSize)
        enterAllInHash(lastEntry, ht)
        hashTable = ht
        // checkConsistent() // DEBUG
      }

    private def enterAllInHash(e: ScopeEntry | Null, ht: Array[ScopeEntry | Null], n: Int = 0)(using Context): Unit =
      if (e != null)
        if (n < MaxRecursions) {
          enterAllInHash(e.prev, ht, n + 1)
          enterInHash(e, ht)
        }
        else {
          var entries: List[ScopeEntry] = List()
          var ee: ScopeEntry | Null = e
          while (ee != null) {
            entries = ee :: entries
            ee = ee.prev
          }
          entries.foreach(enterInHash(_, ht))
        }

    /** Remove entry from this scope (which is required to be present) */
    final def unlink(e: ScopeEntry)(using Context): Unit =
      // in place, by single stores that leave `e`'s own links as they were: a reader at `e` goes on past it
      if needsWriter then writing(unlink0(e)) else unlink0(e)

    private def unlink0(e: ScopeEntry)(using Context): Unit = {
      if (lastEntry == e)
        lastEntry = e.prev
      else {
        var e1 = lastEntry.nn
        while (e1.prev != e) e1 = e1.prev.nn
        e1.prev = e.prev
      }
      val ht = hashTable
      if (ht != null) {
        val index = e.name.hashCode & (ht.length - 1)
        var e1 = ht(index)
        if (e1 == e)
          ht(index) = e.tail
        else {
          while (e1.nn.tail != e) e1 = e1.nn.tail
          e1.nn.tail = e.tail
        }
      }
      elemsCache = null
      _size -= 1
      changed("unlink")
    }

    /** remove symbol from this scope if it is present */
    final def unlink(sym: Symbol)(using Context): Unit =
      unlink(sym, sym.name)

    /** remove symbol from this scope if it is present under the given name */
    final def unlink(sym: Symbol, name: Name)(using Context): Unit = {
      var e = lookupEntry(name)
      while (e != null) {
        if (e.sym == sym) unlink(e)
        e = lookupNextEntry(e)
      }
    }

    /** Replace symbol `prev` (if it exists in current scope) by symbol `replacement`.
     *  @pre `prev` and `replacement` have the same name.
     */
    final def replace(prev: Symbol, replacement: Symbol)(using Context): Unit =
      if needsWriter then writing(replace0(prev, replacement)) else replace0(prev, replacement)

    private def replace0(prev: Symbol, replacement: Symbol)(using Context): Unit = {
      require(prev.name == replacement.name)
      var e = lookupEntry(prev.name)
      while (e != null) {
        if (e.sym == prev) e.sym = replacement
        e = lookupNextEntry(e)
      }
      elemsCache = null
      changed("replace")
    }

    /** Lookup a symbol entry matching given name.
     */
    override def lookupEntry(name: Name)(using Context): ScopeEntry | Null = {
      var e: ScopeEntry | Null = null
      val ht = hashTable
      if (ht != null) {
        if ctx.base.concurrent then VarHandle.acquireFence() // a table published by `extendAside`
        e = ht(name.hashCode & (ht.length - 1))
        while ((e != null) && e.name != name)
          e = e.tail
      }
      else {
        e = lastEntry
        while ((e != null) && e.name != name)
          e = e.prev
      }
      if e != null then e
      else synthesize match
        case null => null
        case s => if synthesizable.nn(name) then synthesizedEntry(s, name) else null
    }

    /** The synthesized entry for `name`, made once: a reader that loses the race to make it takes the winner's, and
     *  the loser's symbol is never seen. The entry is the scope's (`owner`) but in no chain of it, so
     *  `lookupNextEntry` finds nothing after it.
     */
    private def synthesizedEntry(s: SymbolSynthesizer, name: Name)(using Context): ScopeEntry | Null =
      val memo = synthesized.nn
      val known = memo.get(name)
      if known != null then known
      else
        val sym = s(name)
        if !sym.exists then null
        else
          val fresh = new ScopeEntry(sym.name, sym, this)
          val won = memo.putIfAbsent(name, fresh)
          if won == null then fresh else won

    /** lookup next entry with same name as this one */
    override final def lookupNextEntry(entry: ScopeEntry)(using Context): ScopeEntry | Null = {
      if hashTable != null then
        var e: ScopeEntry | Null = entry.tail
        while e != null && e.name != entry.name do e = e.tail
        e
      else
        var e: ScopeEntry | Null = entry.prev
        while e != null && e.name != entry.name do e = e.prev
        e
    }

    /** Returns all symbols as a list in the order they were entered in this scope.
     *  Does _not_ include the elements of inherited scopes.
     */
    override final def toList(using Context): List[Symbol] = {
      // built in a local and published once: with several readers another
      // one may enter a symbol (and null the cache) while this one builds it
      // (I77, a null `elemsCache` in a package's member names)
      val known = elemsCache
      if (known != null) known
      else {
        ensureComplete()
        var elems: List[Symbol] = Nil
        var e = lastEntry
        while ((e != null) && e.owner == this) {
          elems = e.sym :: elems
          e = e.prev
        }
        elemsCache = elems
        elems
      }
    }

    override def implicitDecls(using Context): List[TermRef] = {
      ensureComplete()
      val irefs = new mutable.ListBuffer[TermRef]
      var e = lastEntry
      while (e != null) {
        if (e.sym.isOneOf(GivenOrImplicitVal)) {
          val d = e.sym.denot
          irefs += TermRef(NoPrefix, d.symbol.asTerm).withDenot(d)
        }
        e = e.prev
      }
      irefs.toList
    }

    /** Vanilla scope - symbols are stored in declaration order.
     */
    final def sorted(using Context): List[Symbol] = toList

    override def openForMutations: MutableScope = this

    /** Check that all symbols in this scope are in their correct hashtable buckets. */
    override def checkConsistent()(using Context): Unit = {
      ensureComplete()
      var e = lastEntry
      while (e != null) {
        var e1 = lookupEntry(e.name)
        while (e1 != e && e1 != null) e1 = lookupNextEntry(e1)
        assert(e1 == e, s"PANIC: Entry ${e.nn.name} is badly linked")
        e = e.prev
      }
    }
  }

  /** Create a new scope */
  def newScope(using Context): MutableScope =
    new MutableScope(ctx.nestingLevel + 1)

  def newScope(nestingLevel: Int): MutableScope = new MutableScope(nestingLevel)

  /** Create a new scope with given initial elements */
  def newScopeWith(elems: Symbol*)(using Context): MutableScope = {
    val scope = newScope
    elems foreach scope.enter
    scope
  }

  /** The empty scope (immutable).
   */
  object EmptyScope extends Scope {
    override private[dotc] def lastEntry: ScopeEntry | Null = null
    override def size: Int = 0
    override def nestingLevel: Int = 0
    override def toList(using Context): List[Symbol] = Nil
    override def cloneScope(using Context): MutableScope = newScope(nestingLevel)
    override def lookupEntry(name: Name)(using Context): ScopeEntry | Null = null
    override def lookupNextEntry(entry: ScopeEntry)(using Context): ScopeEntry | Null = null
  }
}
