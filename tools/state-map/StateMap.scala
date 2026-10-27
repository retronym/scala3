//> using scala 3.10.0
//> using dep org.scala-lang::scala3-tasty-inspector:3.10.0

// Extracts the mutable state of dotc's classes from their TASTy into a JSON model: per field its kind (A–E,
// docs/_docs/internals/interactive-multi-reader.md §3), the discipline that protects it, feature tags and edges to
// other modelled classes. Every placement is by a rule listed in `Rules` and in the JSON's header; a field no rule
// places is `unclassified` / `unknown`. See README.md.

import scala.quoted.*
import scala.tasty.inspector.*
import scala.collection.mutable
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

final case class Rule(id: String, sets: String, doc: String)

object Rules:
  val kind: List[Rule] = List(
    Rule("@scratch, @lazyFact, @cache, @interned, @treeState", "A, B, C, D, E", "an annotation in `scala.annotation.internal` on the field, or else on its class: takes precedence over every rule below (`kindSource: annotated`); the rules' answer is kept as `inferredKind`"),
    Rule("A.reader", "A", "a field of `dotc.Reader`, the thread-confined per-request state (step 2)"),
    Rule("A.traversal", "A", "a field of a `TypeMap`, `TypeAccumulator`, `VariantTraversal`, tree map/accumulator/traverser, `TypeComparer` or `ConstraintHandling`: one traversal's or one reader's scratch (comparers are per reader, `Reader.comparers`; §8 I91 `TypeMap.variance`)"),
    Rule("B.denotation", "B", "`myFlags`, `myInfo`, `myAnnotations`, `myParamss`, `myPrivateWithin`, `myCompanion`, `myDecls`, `mySourceModule`, `myModuleClass` of a `SymDenotation`: the denotation's own state, written by its completion (§8 I91 'not caches')"),
    Rule("B.validity", "B", "`myValidFor`, `nextInRun` of a denotation: written by `Denotation.current` on the first read in a run (§3 B)"),
    Rule("A.iterator", "A", "a field of an `Iterator`: one traversal's"),
    Rule("B.completer", "B", "a field of a `SymDenotations.LazyType` (completers, symbol loaders, TASTy and Scala 2 completers)"),
    Rule("B.inherited", "B", "a field of an `InheritedCache` implementation (base data, member names: a second black hole, §3.1)"),
    Rule("B.lazy", "B", "a field of `LazyRef` or of a lazy annotation (`Annotations.LazyAnnotation`, `LazyBodyAnnotation` and their subclasses)"),
    Rule("B.unpickler", "B", "a field of a class in `core.tasty`, `core.classfile` or `core.unpickleScala2`: lazy TASTy/classfile reads (`symAtAddr`, `treeAtAddr`)"),
    Rule("B.scope", "B", "a field of `Scopes.MutableScope`: completions enter into shared scopes that rehash on `enter`"),
    Rule("C.stamped", "C", "the field a `Stamped` extension (`cached`, `cachedIf`, `peek`) is called on: a value and its stamp as one immutable object"),
    Rule("C.stamp", "C", "a stamp: declared `Period`, `RunId` or `Validity`, or, not a collection, named `…Period`, `…RunId`, `valid…`, `checked…` (`StampedCachesTest.stampFields`)"),
    Rule("C.publish", "C", "declared `Published[T]`, or read or written in a method that calls `acquire` or `release` on a `Published` field, or `sharing.Publish`, on the same object (the value the stamp publishes)"),
    Rule("C.holder", "C", "on a type, denotation or symbol, `@volatile` and declared a tuple or a non-value class with two or more fields, all `val`s: a multi-field cache packed into one immutable value"),
    Rule("D.designator", "D", "`myDesignator` of a `NamedType`: part of the interned key, rewritten by `setDenot` (§3 D)"),
    Rule("C.type-memo", "C", "any other field of a `Type` other than a `TypeVar`: types are hash-consed values, so a field on one memoises a function of the type"),
    Rule("C.lazy-init", "C", "a `private var myX: T | Null = null` (not a collection) read and written in one method: a lazily initialised value (the pattern §8's I91 audit enumerates)"),
    Rule("C.name", "C", "named `…cache…`/`…Cache…`, `cached…`, `last[A-Z]…`, `my…Hash`"),
    Rule("D.interner", "D", "a field of `util.WeakHashSet`, `util.ConcurrentWeakHashSet` or a class in `core/Uniques.scala`, or declared `Uniques`"),
    Rule("D.names", "D", "a field of a class in `core/Names.scala` (the name table)"),
    Rule("D.counter", "D", "named `_next…Id`, `next…Id`, `nextId`, `…SymId(s)`, or a field of a `FreshNameCreator`"),
    Rule("E.tree", "E", "a field of `ast.Trees.Tree`, `ast.Positioned`, `util.Attachment.Container` or `Attachment.Link` (`myTpe`, `span`, `myMods`, attachments)"),
    Rule("E.session", "E", "a field of `InteractiveDriver.Session` (the session's typed tree and skipped statements)"),
    Rule("E.unit", "E", "`tpdTree`, `untpdTree` of `CompilationUnit`")
  )

  val protection: List[Rule] = List(
    Rule("@guardedBy, @frozenAfter, @confined, @publishedBy, @benignRace", "completion-lock, root-claim, writer-action, frozen-after-completion, synchronized, thread-confined, Publish, volatile, holder-volatile, init-only, benign-race", "an annotation on the field, or else on its class: `@guardedBy(\"completionLock\")`, `(\"rootClaim\")`, `(\"writer\")` (a write action, which excludes readers) or another lock (synchronized); `@frozenAfter(\"completion\")` (filled while hidden, frozen once its owner's completion publishes it; checked at run time by `-Ycheck-frozen-scopes`); `@confined`; `@publishedBy(\"Publish\" | \"volatile\" | \"holder\" | \"init\")` (`init`: written only before the owner is shared); `@benignRace(why)`. Takes precedence over the rules below (`protectionSource: annotated`); the rules' answer is kept as `inferredProtection`, and `annotationCheck` says whether it agrees, disagrees (for `partial:P`: some accesses lack P) is unverified (the rules find no protection), or is redundant: it only repeats what a rule finds from the declaration alone (`P.stamped`, `P.publish-handle`, `P.claimed`, `P.guarded`, `P.holder`, `P.volatile`, `P.thread-local`; for a kind, `C.publish`, `C.stamped`), which the compiler or the JVM keeps true, so it can be dropped (validation lists each as `REDUNDANT`)"),
    Rule("@sharable, @unshared", "static-safe (@sharable), single-threaded (@unshared)", "the markers of `-Ycheck-reentrant` on the field or its owner (reentrancy across compiler instances, not sharing within one base), when no annotation above is present"),
    Rule("P.thread-local", "thread-confined", "declared `ThreadLocal`"),
    Rule("P.confined-owner", "thread-confined", "kind A (`A.reader`, `A.traversal`): the owner is one reader's or one traversal's"),
    Rule("P.post-typer", "post-typer", "reach `post-typer` (see `reach`): written in phases after typer, which the interactive compiler does not run (§8 I91 class 3)"),
    Rule("P.publish-handle", "Publish", "declared `Published[T]` (`dotty.tools.sharing.Published`: release store, acquire load, a plain access only through `plain`/`setPlain`) or `Payload[O, F, T]` (stored only in a `publishing` block of `F`, read only `after` its acquire, or with `plain`), or `Once[K, T]` (stored only by its `get`, under the claim on its owner, with release semantics, and read with acquire semantics in a concurrent mode)"),
    Rule("P.guarded", "synchronized", "declared `Guarded[K, T]` or `GuardedVar[K, T]` (`dotty.tools.sharing`): read and written only with the evidence of the monitor of `K` (`Monitor[K]`, from a `Guard`'s `locked`/`alwaysLocked`, `serialised`, `locking`, or `unlocked`), so no access outside it compiles"),
    Rule("P.claimed", "completion-lock", "declared `Claimed[K, T]` (`dotty.tools.sharing.Claimed`): read with `c()` and stored with `Claimed(v)`, both only with the evidence of the claim on `K` (`Holds[K]`, from `claim`, `once` or `claimAt(owner)`, or `unguarded`), so no access outside the claim compiles; `Claimed.initial` only in initialisers"),
    Rule("P.stamped", "Stamped", "declared `Stamped[K, T]`: a stamp and a value as one immutable object, so every read and store of the field, a reset to `Stamped.Empty` included, is of a whole pair"),
    Rule("P.holder", "holder-volatile", "`@volatile` and declared a tuple or a class whose fields are all `val`s"),
    Rule("P.volatile", "volatile", "`@volatile`, a single value"),
    Rule("P.concurrent-type", "concurrent", "declared a `java.util.concurrent` or `scala.collection.concurrent` type, an atomic, `util.ConcurrentWeakHashSet` or `util.SyncEqHashMap`"),
    Rule("P.on-base", "concurrent", "initialised by `Sharing.newMap`, `newEqHashMap` or `new…Uniques`: a concurrent structure when the base is concurrent"),
    Rule("P.asserted-completion", "completion-lock", "kind `B.denotation`, `B.completer` or `B.unpickler`: written while the completion lock or the root's claim is held by `lockAndComplete`/`Sharing.claim` further up the stack (dynamic, not visible at the access; evidence `asserted`)"),
    Rule("P.access", "(per access)", "every access outside initialisation (constructors, field initialisers) and resets (`= Nowhere`, `= NoRunId`), each classified innermost-first: the field a `Stamped` extension is called on → Stamped; in a `synchronized` block, `Sharing.serialised` or a `Sharing.Guard`'s `locked` → synchronized; in the `op` of `Sharing.claim` or `claimAt`, or the `compute` of `Sharing.once`, → completion-lock or root-claim as the call's `ClaimKind` says (`lockWhenSerialised = false`: root-claim); in the `op` of `CompletionLock.locked`, or in a method that takes a `Sharing.Holds` (the evidence of `claim` or `once` on a key, or of `claimAt(owner)` on a key computed from its owner, such as a TASTy file's, `Holds[unpickler.type]`) → completion-lock; in the `op` of `CompletionRoots.locked` → root-claim; in a method that calls `sharing.Publish` → Publish; in the else branch of `if <x>.concurrent`, `if <x>.parallel` or `if Config.<c> && <x>.concurrent` (or the then branch of the negation) → batch; else, in a private or local method, the one protection that every call of the method has (calls in a batch branch aside), recursively; else none. The field's protection: the one protection of all non-batch accesses; `batch-only` if every access is batch; `partial:<P>` if some are P and some none; `mixed:<P,Q>` for two protections; `unknown` if no access is seen")
  )

  val reach: List[Rule] = List(
    Rule("R.post-typer", "post-typer", "the owner is in `cc`, `backend` or `transform` (other than `SetRootTree`, `CookComments`), or is a `Phase` subclass (not `Phase` itself) other than the interactive compiler's (`Parser`, `TyperPhase`, `SetRootTree`, `CookComments`, `InteractiveCompiler.phases`); in a method that makes the evidence itself with `Sharing.unlocked(why)` or `Sharing.unguarded(why)`, an argued access → synchronized or completion-lock"),
    Rule("R.typer", "typer", "everything else: reachable from the interactive compiler's typer")
  )

  val excluded: List[Rule] = List(
    Rule("X.collection", "excluded", "the internals of a mutable collection class (`util.MutableMap`, `MutableSet`, `GenericHashMap`, `GenericHashSet`, `MutableIdentitySet`, `IntMap`, `PerfectHashing`, `SimpleIdentityMap`, `SimpleIdentitySet`): modelled where the collection is held"),
    Rule("X.synthetic", "excluded", "synthetic fields (`$values` of enums) and `lazy val`s (not modelled yet)")
  )

  val feature: List[(String, String)] = List(
    "cc" -> "package `cc`, or a class named `…Capture…`/`…Capability…`",
    "match-types" -> "a class named `MatchType…`, `MatchCase…`, `MatchReducer…`, or a field named `…reduce…`/`…Reduced…`",
    "inline-macros" -> "package `inlines`, `quoted`, `staging`, or a class named `Inline…`, `Inliner…`, `Splice…`, `Quote…`, `Macro…`",
    "explicit-nulls" -> "a class named `…Nullable…`, `…NotNull…`, `FlexibleType`, `…ExplicitNull…`",
    "tasty-classfile" -> "package `core.tasty`, `core.classfile`, or a class named `SymbolLoader…`, `…Unpickler…`, `ClassfileParser…`, `CompilationUnitInfo`",
    "scala2" -> "package `core.unpickleScala2`, or a class named `Scala2…`",
    "interactive" -> "package `interactive`, or `Reader`, `Sharing`, `CompletionRoots`, `CompletionLock`",
    "core" -> "package `core` (and its subpackages)"
  )

case class FieldInfo(
    key: String, owner: String, name: String, tpe: String, file: String, line: Int, offset: Int,
    annotations: List[String], isVar: Boolean, isPrivate: Boolean, mutableCollection: Option[String],
    typeClasses: List[String], isVolatile: Boolean, holderType: Boolean, threadLocal: Boolean,
    concurrentType: Boolean, initOnBase: Boolean, nullInit: Boolean,
    annotArgs: Map[String, String], ownerMarkers: List[String], ownerArgs: Map[String, String])

case class ClassInfo(name: String, simple: String, pkg: String, file: String, line: Int, bases: List[String],
    isModule: Boolean, fieldsAllVals: Boolean)

case class Access(field: String, write: Boolean, reset: Boolean, prot: String, method: String, init: Boolean,
    file: String, line: Int)

class Collector(includePkgs: List[String], repoRoot: Path) extends Inspector:
  val classes = mutable.LinkedHashMap[String, ClassInfo]()
  val fields = mutable.LinkedHashMap[String, FieldInfo]()
  val accesses = mutable.ArrayBuffer[Access]()
  val publishingMethods = mutable.Set[String]()
  // methods that make a monitor's or a claim's evidence with `Sharing.unlocked(why)` / `Sharing.unguarded(why)`: an
  // access there is argued, the reason in the call, and counts as the evidence it stands for
  val unlockedMethods = mutable.Set[String]()
  val unguardedMethods = mutable.Set[String]()
  // calls of private and local methods, (callee, caller, protection at the call): an access in a private method
  // inherits the protection that every call of the method has
  val calls = mutable.ArrayBuffer[(String, String, String)]()

  // a module class keeps its trailing `$`, so that it does not collide with its companion class
  def norm(full: String): String =
    val t = full.stripPrefix("dotty.tools.dotc.")
    t.replace("$", "") + (if t.endsWith("$") then "$" else "")

  def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
    import quotes.reflect.*

    var methodStack: List[Symbol] = Nil
    var protStack: List[String] = Nil
    var initDepth = 0

    def currentMethod: String =
      methodStack.find(m => !m.name.contains("$anonfun") && m.name != "$anonfun").map(m => norm(m.fullName)).getOrElse("")

    val inlineMemo = mutable.Map[Symbol, Option[(String, Set[String])]]()

    def included(sym: Symbol): Boolean =
      val n = norm(sym.fullName)
      val first = n.takeWhile(_ != '.')
      if first.headOption.exists(_.isUpper) then includePkgs.contains("")
      else includePkgs.exists(p => p.nonEmpty && (n.startsWith(p + ".")))

    def relPath(pos: Position): String =
      val p = pos.sourceFile.path
      val abs = Paths.get(p)
      val rel = if abs.isAbsolute then repoRoot.relativize(abs).toString else p
      rel.replace('\\', '/')

    def classesOf(tp: TypeRepr): List[Symbol] = tp.dealias match
      case AppliedType(tycon, args) => classesOf(tycon) ++ args.flatMap(classesOf)
      case OrType(a, b) => classesOf(a) ++ classesOf(b)
      case AndType(a, b) => classesOf(a) ++ classesOf(b)
      case AnnotatedType(t, _) => classesOf(t)
      case t: TypeRef if t.typeSymbol.isClassDef => List(t.typeSymbol)
      case t: TermRef => classesOf(t.widen)
      case t => if t.typeSymbol.isClassDef then List(t.typeSymbol) else Nil

    def baseNames(tp: TypeRepr): List[String] =
      tp.baseClasses.map(s => s.fullName.replace("$", ""))

    // java.util's collections and maps, not every class there (`Comparator`, which `Ordering` extends, is not state)
    val mutablePrefixes = List("scala.collection.mutable.", "scala.collection.concurrent.", "java.util.Collection",
      "java.util.Map", "java.util.BitSet", "java.util.concurrent.",
      "java.lang.ThreadLocal", "java.lang.StringBuilder", "scala.Array",
      "dotty.tools.dotc.util.MutableMap", "dotty.tools.dotc.util.MutableSet", "dotty.tools.dotc.util.GenericHashMap",
      "dotty.tools.dotc.util.GenericHashSet", "dotty.tools.dotc.util.WeakHashSet", "dotty.tools.dotc.util.ConcurrentWeakHashSet",
      "dotty.tools.dotc.util.SyncEqHashMap", "dotty.tools.dotc.util.EqHashMap", "dotty.tools.dotc.core.Scopes.MutableScope",
      "dotty.tools.sharing.Sharing.GuardedVar")
    val concurrentPrefixes = List("java.util.concurrent.", "scala.collection.concurrent.",
      "dotty.tools.dotc.util.ConcurrentWeakHashSet", "dotty.tools.dotc.util.SyncEqHashMap")

    def mutableCollection(tp: TypeRepr): Option[String] = tp.dealias match
      // a `Sharing.Guard`'s state: the value it guards
      case AppliedType(tc, List(_, t)) if tc.typeSymbol.name == "Guarded" || tc.typeSymbol.name == "Claimed" => mutableCollection(t)
      case _ => mutableCollection0(tp)

    def mutableCollection0(tp: TypeRepr): Option[String] =
      val top = classesOf(tp).filterNot(s => s.fullName == "scala.Null")
      top.headOption.flatMap { s =>
        val bs = baseNames(s.typeRef)
        bs.find(b => mutablePrefixes.exists(p => b.startsWith(p)) && !b.startsWith("java.util.Optional")
          && !b.startsWith("java.util.Collections") && b != "java.util.function")
          .orElse(if s.fullName == "scala.Array" then Some("scala.Array") else None)
          .map(_ => s.fullName.replace("$", ""))
      }

    // ---- the calls whose argument lists protect an access: (owner, method) -> (protection, protected parameter names)
    // `dotty.tools.sharing`'s operations, also when reached through `core.Sharing`'s exports
    def sharingName(fullName: String): String =
      fullName.replace("$", "").replace("dotty.tools.dotc.core.Sharing", "dotty.tools.sharing.Sharing")

    def protectedCall(fn: Symbol): Option[(String, Set[String])] =
      val owner = sharingName(fn.maybeOwner.fullName)
      (owner, fn.name) match
        case ("dotty.tools.sharing.Stamped", "cached" | "cachedIf" | "cachedWhen" | "peek") => Some("Stamped" -> Set("field"))
        // a claim's protection is its key's kind (`claimProtection`), read where the call's `ClaimKind` is known
        case ("dotty.tools.sharing.Sharing", "claim" | "claimAt") => Some("claim" -> Set("op"))
        case ("dotty.tools.sharing.Sharing", "once") => Some("claim" -> Set("compute"))
        case ("dotty.tools.sharing.Sharing", "serialised" | "locking") => Some("synchronized" -> Set("op"))
        case ("dotty.tools.sharing.Sharing.Guard", "locked" | "alwaysLocked") => Some("synchronized" -> Set("op"))
        case ("dotty.tools.sharing.CoarseLock", "locked") => Some("completion-lock" -> Set("op"))
        case ("dotty.tools.dotc.core.CompletionRoots", "locked") => Some("root-claim" -> Set("op"))
        case (_, "synchronized") if owner == "scala.Any" || owner == "java.lang.Object" || owner == "scala.AnyRef" =>
          Some("synchronized" -> Set("*"))
        case _ => inlineProtecting(fn)

    // The protection of a claim, from its `ClaimKind` argument (the given beside the key's class, or `claimAt`'s
    // explicit kind): `lockWhenSerialised = false` is a root's claim only, otherwise the completion lock as well.
    def claimProtection(argss: List[List[Term]]): String =
      def isKind(t: Term) = sharingName(t.tpe.widen.typeSymbol.fullName) == "dotty.tools.sharing.Sharing.ClaimKind"
      def lockFlag(sym: Symbol): Option[Boolean] =
        var flag: Option[Boolean] = None
        if sym.exists then
          try
            val rhs = sym.tree match
              case v: ValDef => v.rhs
              case d: DefDef => d.rhs
              case _ => None
            rhs.foreach { r =>
              new TreeTraverser:
                override def traverseTree(t: Tree)(o: Symbol): Unit = t match
                  case NamedArg("lockWhenSerialised", Literal(BooleanConstant(b))) => flag = Some(b)
                  case _ => super.traverseTree(t)(o)
              .traverseTree(r)(sym)
            }
          catch case _: Throwable => ()
        flag
      argss.flatten.find(isKind).flatMap(k => lockFlag(k.symbol)) match
        case Some(false) => "root-claim"
        case _ => "completion-lock"

    // an inline method that passes its parameter `p` to a protected argument of a protecting call protects `p`
    def inlineProtecting(fn: Symbol): Option[(String, Set[String])] =
      if !fn.exists || !fn.flags.is(Flags.Inline) || !fn.isDefDef then None
      else inlineMemo.getOrElseUpdate(fn, {
        inlineMemo(fn) = None // cycle guard
        val params = fn.paramSymss.flatten.filterNot(_.isTypeParam).toSet
        var found: Option[(String, Set[String])] = None
        try
          fn.tree match
            case dd: DefDef => dd.rhs.foreach { rhs =>
              new TreeTraverser:
                override def traverseTree(t: Tree)(o: Symbol): Unit = t match
                  case app: Apply =>
                    def flat(t: Term): (Term, List[List[Term]]) = t match
                      case Apply(f, as) => val (g, ls) = flat(f); (g, ls :+ as)
                      case TypeApply(f, _) => flat(f)
                      case _ => (t, Nil)
                    val (g, argss) = flat(app)
                    protectedCall(g.symbol) match
                      case Some((prot0, names)) =>
                        val prot = if prot0 == "claim" then claimProtection(argss) else prot0
                        val gp = g.symbol.paramSymss.filterNot(_.exists(_.isTypeParam))
                        for (args, i) <- argss.zipWithIndex; (a, j) <- args.zipWithIndex do
                          val pn = gp.lift(i).flatMap(_.lift(j)).map(_.name).getOrElse("")
                          a match
                            case id: Ident if (names("*") || names(pn)) && params(id.symbol) =>
                              found = Some((prot, found.map(_._2).getOrElse(Set.empty) + id.name))
                            case _ =>
                      case None =>
                    super.traverseTree(t)(o)
                  case _ => super.traverseTree(t)(o)
              .traverseTree(rhs)(fn)
            }
            case _ =>
        catch case _: Throwable => ()
        found
      })

    def isConfig(t: Term): Boolean = t match
      case Select(q, _) => q.symbol.exists && q.symbol.fullName.replace("$", "") == "dotty.tools.dotc.config.Config"
      case _ => false

    def isConcurrentFlag(t: Term): Option[Boolean] = t match
      case Select(_, "concurrent" | "parallel") => Some(true)
      case Ident("concurrent" | "parallel") => Some(true)
      case Select(inner, "unary_!") => isConcurrentFlag(inner).map(!_)
      case Apply(Select(inner, "unary_!"), Nil) => isConcurrentFlag(inner).map(!_)
      case Inlined(_, Nil, e) => isConcurrentFlag(e)
      // `Config.x && ctx.base.concurrent`: with the constant false neither branch touches the cache
      case Apply(Select(l, "&&"), List(r)) if isConfig(l) && isConcurrentFlag(r).contains(true) => Some(true)
      case Typed(e, _) => isConcurrentFlag(e)
      case _ => None

    def isFieldSym(s: Symbol): Boolean =
      s.exists && s.isValDef && s.maybeOwner.isClassDef && !s.flags.is(Flags.Module) && !s.flags.is(Flags.Lazy)
        && !s.flags.is(Flags.Param) // method parameters

    def isPrivateMethod(s: Symbol): Boolean =
      s.exists && s.isDefDef && !s.flags.is(Flags.Inline)
        && (s.flags.is(Flags.Private) || s.flags.is(Flags.PrivateLocal) || !s.maybeOwner.isClassDef)

    def recordCall(s: Symbol): Unit =
      if isPrivateMethod(s) then calls += ((norm(s.fullName), currentMethod, protStack.headOption.getOrElse("none")))

    def isReset(rhs: Term): Boolean = rhs match
      case Ident("Nowhere" | "NoRunId") | Select(_, "Nowhere" | "NoRunId") => true
      case Inlined(_, _, e) => isReset(e)
      case _ => false

    def record(sym: Symbol, write: Boolean, reset: Boolean, pos: Position): Unit =
      if isFieldSym(sym) then
        val key = norm(sym.owner.fullName) + "#" + sym.name
        val prot = protStack.headOption.getOrElse("none")
        accesses += Access(key, write, reset, prot, currentMethod, initDepth > 0, relPath(pos), pos.startLine + 1)

    def recordClass(cd: ClassDef): Unit =
      val sym = cd.symbol
      val name = norm(sym.fullName)
      val vals = cd.body.collect { case v: ValDef => v }
      val allVals = vals.forall(v => !v.symbol.flags.is(Flags.Mutable))
      val pkg = { val parts = name.split('.').toList; parts.takeWhile(p => p.headOption.exists(_.isLower)).mkString(".") }
      classes(name) = ClassInfo(name, sym.name.stripSuffix("$"), pkg, relPath(cd.pos), cd.pos.startLine + 1,
        baseNames(sym.typeRef).map(norm), sym.flags.is(Flags.Module), allVals)

    def recordField(vd: ValDef, owner: Symbol): Unit =
      val sym = vd.symbol
      val tpe = vd.tpt.tpe
      val isVar = sym.flags.is(Flags.Mutable)
      val coll = if isVar then mutableCollection(tpe) else mutableCollection(tpe)
      if (isVar || coll.isDefined) && !sym.flags.is(Flags.Lazy) && !sym.flags.is(Flags.Module)
        && !sym.flags.is(Flags.Synthetic) && !sym.name.startsWith("$") then
        val key = norm(owner.fullName) + "#" + sym.name
        def annotName(a: Term) = a.tpe.typeSymbol.fullName.replace("$", "").stripPrefix("dotty.tools.dotc.").stripPrefix("scala.annotation.").stripPrefix("scala.")
        // the string argument of an annotation, as `@guardedBy("this")`
        def annotArg(a: Term): String = a match
          case Apply(_, List(Literal(StringConstant(s)))) => s
          case Apply(_, List(NamedArg(_, Literal(StringConstant(s))))) => s
          case _ => ""
        val annots = sym.annotations.map(annotName)
        val annotArgs = sym.annotations.map(a => annotName(a) -> annotArg(a)).toMap
        val ownerSyms = owner :: (if owner.flags.is(Flags.Module) && owner.companionModule.exists then List(owner.companionModule) else Nil)
        val ownerMarkers = ownerSyms.flatMap(_.annotations.map(annotName))
          .filter(n => n == "internal.sharable" || n == "internal.unshared").distinct
        // the state-map vocabulary on the owning class applies to each of its fields that does not say otherwise
        val vocabulary = Set("internal.scratch", "internal.lazyFact", "internal.cache", "internal.interned", "internal.treeState",
          "internal.guardedBy", "internal.frozenAfter", "internal.confined", "internal.publishedBy", "internal.benignRace", "internal.featureArea")
        val ownerArgs = owner.annotations.filter(a => vocabulary(annotName(a))).map(a => annotName(a) -> annotArg(a)).toMap
        val tcs = classesOf(tpe).map(s => norm(s.fullName)).distinct
        val topCls = classesOf(tpe).filterNot(_.fullName == "scala.Null").headOption
        val holder = topCls.exists { c =>
          val n = c.fullName
          n.startsWith("scala.Tuple") || (c.isClassDef && !baseNames(c.typeRef).contains("scala.AnyVal") && !n.startsWith("scala.") && !n.startsWith("java.") &&
            c.fieldMembers.size >= 2 && c.fieldMembers.forall(f => !f.flags.is(Flags.Mutable)) && coll.isEmpty)
        }
        val bases = topCls.toList.flatMap(c => baseNames(c.typeRef))
        val initOnBase = vd.rhs.exists { r =>
          var found = false
          new TreeTraverser:
            override def traverseTree(t: Tree)(o: Symbol): Unit = t match
              case s: Select if Set("newMap", "newEqHashMap", "newUniques", "newAppliedUniques", "newNamedTypeUniques")(s.name)
                  && s.symbol.maybeOwner.fullName.endsWith("Sharing") => found = true
              case _ => super.traverseTree(t)(o)
          .traverseTree(r)(sym)
          found
        }
        val pos = vd.pos
        fields(key) = FieldInfo(key, norm(owner.fullName), sym.name,
          tpe.show(using Printer.TypeReprShortCode), relPath(pos), pos.startLine + 1, pos.start,
          annots, isVar, sym.flags.is(Flags.Private) || sym.flags.is(Flags.PrivateLocal), coll, tcs,
          annots.contains("volatile"), holder,
          bases.exists(_.startsWith("java.lang.ThreadLocal")),
          bases.exists(b => concurrentPrefixes.exists(b.startsWith)) || bases.exists(_.startsWith("java.util.concurrent")),
          initOnBase, vd.rhs.exists { case Literal(NullConstant()) => true case _ => false }, annotArgs, ownerMarkers, ownerArgs)

    object Walker extends TreeTraverser:
      def withProt(p: String)(op: => Unit): Unit =
        protStack = p :: protStack
        try op finally protStack = protStack.tail

      override def traverseTree(tree: Tree)(owner: Symbol): Unit = tree match
        case cd: ClassDef =>
          if included(cd.symbol) then
            recordClass(cd)
            cd.body.foreach { case vd: ValDef => recordField(vd, cd.symbol) case _ => }
          val saved = methodStack
          methodStack = Nil
          initDepth += 1
          traverseTree(cd.constructor)(cd.symbol)
          cd.parents.foreach(traverseTree(_)(cd.symbol))
          cd.body.foreach {
            case d: DefDef => initDepth -= 1; traverseTree(d)(cd.symbol); initDepth += 1
            case c: ClassDef => initDepth -= 1; traverseTree(c)(cd.symbol); initDepth += 1
            case t => traverseTree(t)(cd.symbol)
          }
          initDepth -= 1
          methodStack = saved
        case dd: DefDef =>
          methodStack = dd.symbol :: methodStack
          val savedInit = initDepth
          if dd.name == "<init>" then initDepth += 1
          // a method that takes the evidence of a claim (`Sharing.Holds`) runs inside `Sharing.claim`
          def takes(cls: String) = dd.termParamss.exists(_.params.exists(p => sharingName(p.tpt.tpe.dealias.typeSymbol.fullName) == cls))
          val evidence =
            if takes("dotty.tools.sharing.Sharing.Holds") then Some("completion-lock")
            else if takes("dotty.tools.sharing.Sharing.Monitor") then Some("synchronized")
            else None
          try evidence match
            case Some(p) => withProt(p)(super.traverseTree(tree)(owner))
            case None => super.traverseTree(tree)(owner)
          finally { methodStack = methodStack.tail; initDepth = savedInit }
        case Assign(lhs, rhs) =>
          record(lhs.symbol, write = true, reset = isReset(rhs), lhs.pos)
          lhs match
            case Select(q, _) => traverseTree(q)(owner)
            case _ =>
          traverseTree(rhs)(owner)
        case If(cond, thenp, elsep) if isConcurrentFlag(cond).isDefined =>
          traverseTree(cond)(owner)
          val positive = isConcurrentFlag(cond).get
          withProt(if positive then "concurrent-branch" else "batch")(traverseTree(thenp)(owner))
          withProt(if positive then "batch" else "concurrent-branch")(traverseTree(elsep)(owner))
        case app @ Apply(_, _) =>
          def flatten(t: Term): (Term, List[List[Term]]) = t match
            case Apply(f, as) => val (fn, ls) = flatten(f); (fn, ls :+ as)
            case TypeApply(f, _) => flatten(f)
            case _ => (t, Nil)
          val (fn, argss) = flatten(app)
          val fsym = fn.symbol
          recordCall(fsym)
          if fsym.exists && (fsym.maybeOwner.fullName.replace("$", "") == "dotty.tools.sharing.Publish"
              || fsym.maybeOwner.fullName == "dotty.tools.sharing.PublishedOps" && (fsym.name == "acquire" || fsym.name == "release"))
          then publishingMethods += currentMethod
          if fsym.exists && fsym.maybeOwner.fullName.replace("$", "").endsWith("Sharing") then
            if fsym.name == "unlocked" then unlockedMethods += currentMethod
            else if fsym.name == "unguarded" then unguardedMethods += currentMethod
          protectedCall(fsym) match
            case Some((prot0, names)) =>
              val prot = if prot0 == "claim" then claimProtection(argss) else prot0
              fn match
                // the monitor of `x.synchronized(...)`: reading `x` to lock it is the lock, not an access outside it
                case Select(q, "synchronized") => withProt(prot)(traverseTree(q)(owner))
                case _ => traverseTree(fn)(owner)
              val paramss = fsym.paramSymss.filterNot(_.exists(_.isTypeParam))
              argss.zipWithIndex.foreach { (args, i) =>
                val ps = paramss.lift(i).getOrElse(Nil)
                args.zipWithIndex.foreach { (a, j) =>
                  val pname = ps.lift(j).map(_.name).getOrElse("")
                  if names("*") || names(pname) then withProt(prot)(traverseTree(a)(owner))
                  else
                    // an unprotected argument of a protecting call: its accesses are not protected by it
                    val saved = protStack
                    protStack = protStack.dropWhile(_ == "concurrent-branch")
                    try traverseTree(a)(owner) finally protStack = saved
                }
              }
            case None => super.traverseTree(tree)(owner)
        case s @ Select(q, _) =>
          recordCall(s.symbol)
          record(s.symbol, write = false, reset = false, s.pos)
          traverseTree(q)(owner)
        case i @ Ident(_) =>
          recordCall(i.symbol)
          record(i.symbol, write = false, reset = false, i.pos)
        case _ => super.traverseTree(tree)(owner)
    end Walker

    for tasty <- tastys do
      try Walker.traverseTree(tasty.ast)(Symbol.spliceOwner)
      catch case e: Throwable => System.err.println(s"skipped ${tasty.path}: $e")
  end inspect

object StateMap:
  def jstr(s: String): String =
    val sb = StringBuilder("\"")
    s.foreach {
      case '"' => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"
      case c if c < ' ' => sb ++= f"\\u${c.toInt}%04x"
      case c => sb += c
    }
    sb += '"'
    sb.toString

  def jarr(xs: Iterable[String]): String = xs.mkString("[", ",", "]")
  def jobj(kvs: (String, String)*): String = kvs.map((k, v) => s"${jstr(k)}:$v").mkString("{", ",", "}")
  // one entry per line, so that a diff of two extractions is a diff of fields
  def jtop(kvs: (String, String)*): String = kvs.map((k, v) => s"${jstr(k)}: $v").mkString("{\n", ",\n", "\n}\n")

  // `fields`: the receiver of a `Stamped` call
  final case class CachingSite(file: String, line: Int, protection: String, helper: String, fields: List[String])

  /** The `Stamped` calls in the compiler's sources, by text. */
  def cachingSites(repoRoot: Path): List[CachingSite] =
    val src = repoRoot.resolve("compiler/src/dotty/tools/dotc")
    val StampedCall = raw"""(\w+)\.(cached|cachedIf|cachedWhen|peek)\b""".r
    Files.walk(src).iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sorted
      .flatMap { p =>
        val text = Files.readString(p)
        val rel = repoRoot.relativize(p).toString
        def line(m: scala.util.matching.Regex.Match) = text.substring(0, m.start).count(_ == '\n') + 1
        // a call on a field declared `Stamped[...]` in the file, not another `cached` or `peek`
        val stampedFields = raw"""var (\w+)\s*:\s*Stamped\[""".r.findAllMatchIn(text).map(_.group(1)).toSet
        StampedCall.findAllMatchIn(text).filter(m => stampedFields(m.group(1)))
          .map(m => CachingSite(rel, line(m), "Stamped", m.group(2), List(m.group(1)))).toList
      }

  def main(args: Array[String]): Unit =
    val opts = args.grouped(2).collect { case Array(k, v) => k.stripPrefix("--") -> v }.toMap
    val repoRoot = Paths.get(opts.getOrElse("repo", ".")).toAbsolutePath.normalize
    val classesDir = Paths.get(opts("classes"))
    val cp = opts.getOrElse("cp", "").split(java.io.File.pathSeparator).toList.filter(_.nonEmpty)
    val out = Paths.get(opts.getOrElse("out", "tools/state-map/state-map.json"))
    val tip = opts.getOrElse("tip", "HEAD")
    val ghBase = opts.getOrElse("github", "https://github.com/retronym/scala3/blob")
    val pkgs = opts.getOrElse("packages", ",core,typer,interactive,cc,transform,ast,util,inlines,quoted").split(',').toList


    val root = classesDir.resolve("dotty/tools/dotc")
    val tastyFiles = Files.walk(root).iterator.asScala.map(_.toString).filter(_.endsWith(".tasty")).toList.sorted
    val collector = Collector(pkgs, repoRoot)
    TastyInspector.inspectAllTastyFiles(tastyFiles, Nil, classesDir.toString :: cp)(collector)
    // the fields that publish others, and the ones they publish: declared `Published[T]`, `Payload[O, F, T]`, `Once[K, T]`
    val publishHandles = collector.fields.values.filter(f => f.tpe.startsWith("Published[") || f.tpe.startsWith("Payload[") || f.tpe.startsWith("Once["))
      .map(f => (f.owner, f.name)).toSet

    val model = Model(collector, publishHandles, repoRoot)
    val validation = model.validate(
      Files.readAllLines(repoRoot.resolve("tools/state-map/expected.tsv")).asScala.toList
        .filterNot(l => l.startsWith("#") || l.isBlank).map(_.split('\t').toList),
      cachingSites(repoRoot))
    val json = model.toJson(tip, ghBase, validation)
    Files.createDirectories(out.toAbsolutePath.getParent)
    Files.writeString(out, json)
    val js = out.resolveSibling(out.getFileName.toString.stripSuffix(".json") + ".data.js")
    Files.writeString(js, "window.STATE_MAP = " + json + ";\n")
    println(model.summary)
    println(validation.mkString("validation against §8 and the known sites:\n", "\n", "\n"))
    println(s"wrote ${out.toAbsolutePath} and ${js.toAbsolutePath}")

final case class Placed(f: FieldInfo, kind: String, kindRule: String, protection: String, protRule: String,
    evidence: String, features: List[String], role: String, reach: String, accessSummary: Map[String, Int],
    edges: List[String], unprotectedSites: List[String],
    kindSource: String, protectionSource: String, inferredKind: String, inferredKindRule: String,
    inferredProtection: String, inferredProtectionRule: String, disagreement: Option[String])

class Model(c: Collector, publishHandles: Set[(String, String)], repoRoot: Path):
  import StateMap.*

  private def cls(name: String) = c.classes.get(name)
  private def derives(owner: String, base: String) = cls(owner).exists(_.bases.contains(base)) || owner == base
  private def ownerFile(f: FieldInfo) = cls(f.owner).map(_.file).getOrElse(f.file)
  private def ownerPkg(f: FieldInfo) = cls(f.owner).map(_.pkg).getOrElse("")
  private def simple(f: FieldInfo) = cls(f.owner).map(_.simple).getOrElse(f.owner)

  private val accByField: Map[String, Seq[Access]] = c.accesses.toSeq.groupBy(_.field)

  private val callsTo: Map[String, Seq[(String, String, String)]] = c.calls.toSeq.distinct.groupBy(_._1)

  // the protection of a call site or an access at `prot` in `method`
  private def siteProt(prot: String, method: String, seen: Set[String]): String =
    if prot != "none" && prot != "concurrent-branch" then prot
    else if c.publishingMethods(method) then "Publish"
    else if c.unlockedMethods(method) then "synchronized"
    else if c.unguardedMethods(method) then "completion-lock"
    else methodProt(method, seen)

  // a private or local method's protection: the one protection that every call of it has (batch calls aside)
  private val methodMemo = mutable.Map[String, String]()
  private def methodProt(m: String, seen: Set[String]): String =
    if seen(m) then "none"
    else methodMemo.get(m) match
      case Some(p) => p
      case None =>
        val sites = callsTo.getOrElse(m, Nil).filter(_._2 != m)
        val ps = sites.map((_, caller, prot) => siteProt(prot, caller, seen + m)).filterNot(_ == "batch").toSet
        val p =
          if sites.isEmpty then "none"
          else if ps.isEmpty then "batch"
          else if ps.size == 1 then ps.head
          else "none"
        if seen.isEmpty then methodMemo(m) = p
        p

  private def accessProt(a: Access): String = siteProt(a.prot, a.method, Set.empty)

  private def relevant(f: FieldInfo): Seq[Access] =
    accByField.getOrElse(f.key, Nil).filterNot(a => a.init || a.reset)

  private def isStamp(f: FieldInfo) =
    val t = f.tpe.replace(" | Null", "")
    Set("Period", "RunId", "Validity")(t) || f.mutableCollection.isEmpty && (f.name.endsWith("Period") || f.name.endsWith("RunId")
      || raw"(valid|checked)[A-Z]\w*".r.matches(f.name))

  private val denotOwn = Set("myFlags", "myInfo", "myAnnotations", "myParamss", "myPrivateWithin", "myCompanion",
    "myDecls", "mySourceModule", "myModuleClass")

  private def isLazyInit(f: FieldInfo) =
    f.isPrivate && f.isVar && f.nullInit && f.name.startsWith("my") && f.tpe.endsWith("| Null") && f.mutableCollection.isEmpty
      && relevant(f).groupBy(_.method).exists((m, as) => m.nonEmpty && as.exists(_.write) && as.exists(!_.write))

  private val sharedOwners = List("core.Types.Type", "core.Denotations.PreDenotation", "core.SymDenotations.SymDenotation",
    "core.Symbols.Symbol")

  // the internals of a mutable collection class: modelled where the collection is held, not here
  val collectionInternals = Set("util.MutableMap", "util.MutableSet", "util.GenericHashMap", "util.GenericHashSet",
    "util.MutableIdentitySet", "util.IntMap", "util.PerfectHashing", "util.SimpleIdentityMap", "util.SimpleIdentitySet")
  def excluded(f: FieldInfo) = collectionInternals.exists(b => derives(f.owner, b))

  private def kindOf(f: FieldInfo): (String, String) =
    val o = f.owner
    val acc = relevant(f)
    def is(base: String) = derives(o, base)
    if o == "Reader" then ("A", "A.reader")
    else if is("core.Types.TypeMap") || is("core.Types.TypeAccumulator") || is("core.Types.VariantTraversal") || is("core.TypeComparer")
      || is("core.ConstraintHandling") || is("ast.Trees.Instance.TreeMap") || is("ast.Trees.Instance.TreeAccumulator")
    then ("A", "A.traversal")
    else if is("core.SymDenotations.SymDenotation") && denotOwn(f.name) then ("B", "B.denotation")
    else if (is("core.Denotations.SingleDenotation") || is("core.Denotations.Denotation")) && Set("myValidFor", "nextInRun")(f.name) then ("B", "B.validity")
    else if is("scala.collection.Iterator") then ("A", "A.iterator")
    else if is("core.SymDenotations.LazyType") then ("B", "B.completer")
    else if is("core.SymDenotations.InheritedCache") then ("B", "B.inherited")
    else if is("core.Types.LazyRef") || is("core.Annotations.LazyAnnotation") || is("core.Annotations.LazyBodyAnnotation") then ("B", "B.lazy")
    else if Seq("core.tasty", "core.classfile", "core.unpickleScala2").exists(p => ownerPkg(f) == p) then ("B", "B.unpickler")
    else if is("core.Scopes.MutableScope") then ("B", "B.scope")
    else if acc.exists(_.prot == "Stamped") then ("C", "C.stamped")
    else if isStamp(f) then ("C", "C.stamp")
    else if publishHandles((f.owner, f.name)) || acc.exists(a => c.publishingMethods(a.method) && a.prot != "batch") then ("C", "C.publish")
    else if f.isVolatile && f.holderType && sharedOwners.exists(is) then ("C", "C.holder")
    else if (is("core.Types.NamedType")) && f.name == "myDesignator" then ("D", "D.designator")
    else if is("core.Types.Type") && !is("core.Types.TypeVar") then ("C", "C.type-memo")
    else if isLazyInit(f) then ("C", "C.lazy-init")
    else if raw"(?i).*cache.*".r.matches(f.name) || f.name.startsWith("cached") || raw"last[A-Z]\w*".r.matches(f.name)
      || raw"my\w*Hash".r.matches(f.name) then ("C", "C.name")
    else if is("util.WeakHashSet") || is("util.ConcurrentWeakHashSet") || ownerFile(f).endsWith("core/Uniques.scala")
      || f.typeClasses.exists(t => derives(t, "util.WeakHashSet") || cls(t).exists(_.file.endsWith("core/Uniques.scala"))) then ("D", "D.interner")
    else if ownerFile(f).endsWith("core/Names.scala") then ("D", "D.names")
    else if raw"_?next\w*Id|_?nextId|_?\w*SymIds?".r.matches(f.name) || is("util.FreshNameCreator") then ("D", "D.counter")
    else if is("ast.Trees.Tree") || is("ast.Positioned") || is("util.Attachment.Container") || is("util.Attachment.Link") then ("E", "E.tree")
    else if o == "interactive.InteractiveDriver.Session" then ("E", "E.session")
    else if o == "CompilationUnit" && Set("tpdTree", "untpdTree")(f.name) then ("E", "E.unit")
    else ("unclassified", "")

  private def reachOf(f: FieldInfo): String =
    val p = ownerPkg(f)
    val interactivePhases = Set("parsing.Parser", "typer.TyperPhase", "transform.SetRootTree", "transform.CookComments")
    if p == "cc" || p.startsWith("cc.") || p.startsWith("backend") then "post-typer"
    else if p.startsWith("transform") && !interactivePhases(f.owner) then "post-typer"
    // `Phase` itself is not a phase: its fields are every phase's, the typer's among them
    else if f.owner != "core.Phases.Phase" && derives(f.owner, "core.Phases.Phase") && !interactivePhases.exists(ph => derives(f.owner, ph)) then "post-typer"
    else "typer"

  private def protOf(f: FieldInfo, kindRule: String, reach: String): (String, String, String) =
    val acc = relevant(f)
    if f.threadLocal then ("thread-confined", "P.thread-local", "type")
    else if kindRule.startsWith("A.") then ("thread-confined", "P.confined-owner", "owner")
    else if reach == "post-typer" then ("post-typer", "P.post-typer", "owner")
    else if publishHandles((f.owner, f.name)) then ("Publish", "P.publish-handle", "lexical")
    else if f.tpe.startsWith("Stamped[") || f.tpe.startsWith("dotty.tools.sharing.Stamped[") then ("Stamped", "P.stamped", "declaration")
    else if f.tpe.startsWith("Claimed[") || f.tpe.startsWith("dotty.tools.sharing.Claimed[") then ("completion-lock", "P.claimed", "declaration")
    else if Seq("Guarded[", "GuardedVar[", "dotty.tools.sharing.Guarded[", "dotty.tools.sharing.Sharing.GuardedVar[").exists(f.tpe.startsWith) then
      ("synchronized", "P.guarded", "declaration")
    else if f.isVolatile && f.holderType then ("holder-volatile", "P.holder", "annotation")
    else if f.isVolatile then ("volatile", "P.volatile", "annotation")
    else if f.concurrentType then ("concurrent", "P.concurrent-type", "type")
    else if f.initOnBase then ("concurrent", "P.on-base", "lexical")
    else if Set("B.denotation", "B.completer", "B.unpickler")(kindRule) then ("completion-lock", "P.asserted-completion", "asserted")
    else
      val ps = acc.map(accessProt)
      val nonBatch = ps.filterNot(_ == "batch").toSet
      val p =
        if acc.isEmpty then "unknown"
        else if nonBatch.isEmpty then "batch-only"
        else if nonBatch.size == 1 then nonBatch.head
        else if nonBatch.contains("none") then "partial:" + (nonBatch - "none").toList.sorted.mkString(",")
        else "mixed:" + nonBatch.toList.sorted.mkString(",")
      (p, "P.access", "lexical")

  private def featuresOf(f: FieldInfo): List[String] =
    val p = ownerPkg(f)
    val s = f.owner.split('.').filter(_.headOption.exists(_.isUpper)).mkString(".")
    val n = f.name
    val b = List.newBuilder[String]
    if p == "cc" || raw".*(Capture|Capability).*".r.matches(s) then b += "cc"
    if raw".*\b(MatchType|MatchCase|MatchReducer)\w*.*".r.matches(s) || raw"(?i).*reduce.*".r.matches(n) then b += "match-types"
    if Seq("inlines", "quoted", "staging").exists(p.startsWith) || raw".*\b(Inline|Inliner|Splice|Quote|Macro)\w*.*".r.matches(s) then b += "inline-macros"
    if raw".*(Nullable|NotNull|FlexibleType|ExplicitNull).*".r.matches(s) then b += "explicit-nulls"
    if p == "core.tasty" || p == "core.classfile" || raw".*\b(SymbolLoader|ClassfileParser|CompilationUnitInfo)\w*.*|.*Unpickler.*".r.matches(s) then b += "tasty-classfile"
    if p == "core.unpickleScala2" || raw".*\bScala2\w*.*".r.matches(s) then b += "scala2"
    if p.startsWith("interactive") || Set("Reader", "core.Sharing", "core.CompletionRoots", "core.CompletionLock")(f.owner) then b += "interactive"
    if p == "core" || p.startsWith("core.") then b += "core"
    b.result().distinct

  private val kindAnnots = Map("internal.scratch" -> "A", "internal.lazyFact" -> "B", "internal.cache" -> "C",
    "internal.interned" -> "D", "internal.treeState" -> "E")

  // the kind and protection a field's annotations declare, which take precedence over the rules
  private val protAnnots = Set("internal.guardedBy", "internal.frozenAfter", "internal.confined", "internal.publishedBy", "internal.benignRace")

  // a field's own annotation, else its class's: (args, "" or " on the class")
  private def kindArgs(f: FieldInfo) =
    if kindAnnots.keys.exists(f.annotArgs.contains) then (f.annotArgs, "") else (f.ownerArgs, " (class)")
  private def protArgs(f: FieldInfo) =
    if protAnnots.exists(f.annotArgs.contains) then (f.annotArgs, "") else (f.ownerArgs ++ f.annotArgs, " (class)")

  private def annotatedKind(f: FieldInfo): Option[(String, String)] =
    val (a, where) = kindArgs(f)
    kindAnnots.collectFirst { case (n, k) if a.contains(n) => (k, "@" + n.stripPrefix("internal.") + where) }

  private def annotatedProt(f: FieldInfo): Option[(String, String)] =
    val (a, where) = protArgs(f)
    def on(r: String) = r + (if protAnnots.exists(a.contains) && !protAnnots.exists(f.annotArgs.contains) then where else "")
    a.get("internal.guardedBy").map { lock =>
      val p = lock match
        case "completionLock" => "completion-lock"
        case "rootClaim" => "root-claim"
        case "writer" => "writer-action"
        case _ => "synchronized"
      (p, on(s"@guardedBy(\"$lock\")"))
    }.orElse(a.get("internal.frozenAfter").map(ev => (s"frozen-after-$ev", on(s"@frozenAfter(\"$ev\")"))))
     .orElse(a.get("internal.confined").map(_ => ("thread-confined", on("@confined"))))
     .orElse(a.get("internal.publishedBy").map { how =>
       val p = how match
         case "holder" => "holder-volatile"
         case "init" => "init-only"
         case other => other
       (p, on(s"@publishedBy(\"$how\")"))
     })
     .orElse(a.get("internal.benignRace").map(_ => ("benign-race", on("@benignRace"))))
     .orElse {
       // the markers of `-Ycheck-reentrant` (`CheckReentrant`), on the field or its owner: reentrancy across compiler
       // instances, not sharing within one base
       val ms = (a.keys.toList ++ f.ownerMarkers).filter(n => n == "internal.sharable" || n == "internal.unshared")
       if ms.contains("internal.sharable") then Some(("static-safe (@sharable)", "@sharable"))
       else if ms.contains("internal.unshared") then Some(("single-threaded (@unshared)", "@unshared"))
       else None
     }

  // the rules that place a field from its declaration alone, which an annotation can only repeat
  private val declarationProtRules = Set("P.stamped", "P.publish-handle", "P.claimed", "P.guarded", "P.holder", "P.volatile", "P.thread-local")
  private val declarationKindRules = Set("C.publish", "C.stamped")

  val placed: List[Placed] = c.fields.values.toList.filterNot(excluded).map { f =>
    val (ik, ikr) = kindOf(f)
    val reach = reachOf(f)
    val (ip, ipr, iev) = protOf(f, ikr, reach)
    val (k, kr, ks) = annotatedKind(f).map((k, r) => (k, r, "annotated")).getOrElse((ik, ikr, "inferred"))
    val (p, pr, ev, ps) = annotatedProt(f).map((p, r) => (p, r, "annotation", "annotated")).getOrElse((ip, ipr, iev, "inferred"))
    val marker = pr == "@sharable" || pr == "@unshared"
    // an annotation against the rules: `disagrees` when the rules place the field otherwise (`partial:P` for an
    // annotated P: some accesses lack it), `unverified` when they find no protection to compare with (a lock held
    // further up the stack, confinement), `agrees` otherwise
    // an annotation that only repeats what the field's declaration says (its type, `@volatile`, a `Published` handle):
    // the compiler or the JVM keeps it true, so the annotation can go
    val kindCheck =
      if ks != "annotated" then None
      else if ik == k && declarationKindRules(ikr) then Some(s"kind redundant: $ikr says it from the declaration")
      else if ik == "unclassified" then Some(s"kind unverified: no rule places it")
      else if ik != k then Some(s"kind disagrees: rules say $ik ($ikr)")
      else Some("kind agrees")
    val protCheck =
      if ps != "annotated" || marker then None
      else if p == "benign-race" && ip == "none" then Some("protection agrees: rules find none")
      // frozen after an event: the writes before it are the completer's, which no static rule tells apart, and the
      // ones after are checked when the program runs (`-Ycheck-frozen-scopes`)
      else if p.startsWith("frozen-after-") then Some("protection checked at run time (-Ycheck-frozen-scopes)")
      else if ip == p && declarationProtRules(ipr) then Some(s"protection redundant: $ipr says it from the declaration")
      else if ip == "none" || ip == "unknown" then Some(s"protection unverified: rules find $ip")
      else if ip != p then Some(s"protection disagrees: rules say $ip ($ipr)")
      else Some("protection agrees")
    val checks = List(kindCheck, protCheck).flatten
    val disagreement = Option.when(checks.nonEmpty)(checks.mkString("; "))
    val acc = relevant(f)
    val summary = acc.groupBy(accessProt).view.mapValues(_.size).toMap
    val edges = f.typeClasses.filter(t => t != f.owner && c.classes.contains(t)).distinct
    // a `Claimed` field's every access takes the claim's evidence, by its type: a site whose evidence is a given of
    // its class (a `TreeReader`'s) is not unprotected
    val sites = if pr == "P.claimed" then Nil else acc.filter(a => accessProt(a) == "none").map(a => s"${a.file}:${a.line} ${if a.write then "write" else "read"} in ${a.method}").distinct.take(40).toList
    val features = (featuresOf(f) ++ (f.ownerArgs ++ f.annotArgs).get("internal.featureArea").toList).distinct
    Placed(f, k, kr, p, pr, ev, features, if k == "C" then "cache" else "semantic", reach, summary, edges, sites,
      ks, ps, ik, ikr, ip, ipr, disagreement)
  }

  def summary: String =
    val kinds = List("A", "B", "C", "D", "E", "unclassified")
    val prots = placed.map(p => p.protection.takeWhile(_ != ':')).distinct.sorted
    val sb = StringBuilder()
    sb ++= s"${placed.size} fields in ${placed.map(_.f.owner).distinct.size} classes\n\n"
    sb ++= "| protection | " + kinds.mkString(" | ") + " | total |\n"
    sb ++= "|---|" + kinds.map(_ => "---:").mkString("|") + "|---:|\n"
    for pr <- prots do
      val row = kinds.map(k => placed.count(p => p.kind == k && p.protection.takeWhile(_ != ':') == pr))
      sb ++= s"| $pr | ${row.map(n => if n == 0 then "" else n.toString).mkString(" | ")} | ${row.sum} |\n"
    val tot = kinds.map(k => placed.count(_.kind == k))
    sb ++= s"| total | ${tot.mkString(" | ")} | ${tot.sum} |\n"
    val ann = placed.filter(p => p.kindSource == "annotated" || p.f.annotArgs.contains("internal.featureArea")
      || (p.protectionSource == "annotated" && p.protRule != "@sharable" && p.protRule != "@unshared"))
    sb ++= s"\nannotated: ${ann.size} fields; @sharable/@unshared: ${placed.count(p => p.protRule == "@sharable" || p.protRule == "@unshared")}\n"
    for p <- ann do
      sb ++= s"  ${p.f.owner}#${p.f.name}: ${p.kind} (${p.kindSource}), ${p.protection} (${p.protectionSource})${p.disagreement.map(d => s": $d").getOrElse("")}${p.f.annotArgs.get("internal.featureArea").map(a => s", feature $a").getOrElse("")}\n"
    val un = placed.filter(_.kind == "unclassified")
    val (unPost, unTyper) = un.partition(_.reach == "post-typer")
    sb ++= s"\nunclassified: ${un.size} (${unTyper.size} reachable from typer, ${unPost.size} post-typer)\n"
    for (pkg, ps) <- unTyper.groupBy(p => cls(p.f.owner).map(_.pkg).getOrElse("")).toList.sortBy(_._1) do
      sb ++= s"  [$pkg] ${ps.map(p => p.f.owner.stripPrefix(pkg + ".") + "#" + p.f.name).sorted.mkString(", ")}\n"
    sb.toString

  def validate(rows: List[List[String]], sites: List[StateMap.CachingSite]): List[String] =
    val out = List.newBuilder[String]
    // the rules' placement: annotations are checked by `annotationCheck`, not here
    def show(p: Placed) = s"${p.f.owner}#${p.f.name} is ${p.inferredKind}/${p.inferredProtection} (${p.inferredKindRule}, ${p.inferredProtectionRule})"
    for row <- rows do
      val List(keyRe, kind, prot, source) = row.padTo(4, "")
      val re = keyRe.r
      val hits = placed.filter(p => re.matches(p.f.key))
      if hits.isEmpty then out += s"MISSING  $source: no field matches /$keyRe/"
      for p <- hits do
        val kindOk = kind == "*" || kind == p.inferredKind
        val protOk = prot == "*" || prot.r.matches(p.inferredProtection)
        if !kindOk || !protOk then out += s"MISMATCH $source: ${show(p)}; §8 says ${kind}/${prot}"
    // annotations that repeat the declaration, to drop
    for p <- placed; d <- p.disagreement; part <- d.split("; ") if part.contains("redundant") do
      out += s"REDUNDANT ${p.f.owner}#${p.f.name}: ${p.protRule}, ${p.kindRule}: $part"
    // the Stamped call sites, by text, against the fields the rules put under Stamped
    val byFileName = placed.map(p => ((p.f.file, p.f.name), p)).toMap
    for (prot, ss) <- sites.groupBy(_.protection).toList.sortBy(_._1) do
      val stamped = ss.filter(_.helper != "peek")
      val siteFields = ss.flatMap(s => s.fields.map(n => (s.file, n))).toSet
      out += s"INFO     ${stamped.size} stamped caches through $prot (${stamped.groupBy(_.file).toList.sortBy(_._1).map((f, ss) => s"${f.split('/').last}: ${ss.size}").mkString(", ")}), ${ss.size - stamped.size} peeks"
      for (file, name) <- siteFields.toList.sorted do
        byFileName.get((file, name)) match
          case None => out += s"MISMATCH $prot site field $file `$name` is not in the model"
          case Some(p) if p.inferredProtection != prot => out += s"MISMATCH $prot site field ${show(p)}"
          case _ =>
      for p <- placed if p.inferredProtection == prot && !siteFields((p.f.file, p.f.name)) do
        out += s"MISMATCH ${show(p)} but is in no $prot call's fields"
    out.result()

  def toJson(tip: String, gh: String, validation: List[String]): String =
    val ruleJson = (Rules.kind ++ Rules.protection ++ Rules.reach ++ Rules.excluded).map(r => jobj("id" -> jstr(r.id), "sets" -> jstr(r.sets), "doc" -> jstr(r.doc)))
    val featJson = Rules.feature.map((k, d) => jobj("id" -> jstr(k), "doc" -> jstr(d)))
    val fieldsJson = placed.map { p =>
      val f = p.f
      jobj(
        "key" -> jstr(f.key), "owner" -> jstr(f.owner), "name" -> jstr(f.name), "type" -> jstr(f.tpe),
        "file" -> jstr(f.file), "line" -> f.line.toString, "url" -> jstr(s"$gh/$tip/${f.file}#L${f.line}"),
        "annotations" -> jarr(f.annotations.map(jstr)), "var" -> f.isVar.toString, "private" -> f.isPrivate.toString,
        "mutableCollection" -> f.mutableCollection.map(jstr).getOrElse("null"),
        "kind" -> jstr(p.kind), "kindRule" -> jstr(p.kindRule),
        "protection" -> jstr(p.protection), "protectionRule" -> jstr(p.protRule), "evidence" -> jstr(p.evidence),
        "features" -> jarr(p.features.map(jstr)), "role" -> jstr(p.role), "reach" -> jstr(p.reach),
        "accesses" -> jobj(p.accessSummary.toList.sorted.map((k, v) => k -> v.toString)*),
        "edges" -> jarr(p.edges.map(jstr)),
        "unprotectedSites" -> jarr(p.unprotectedSites.map(jstr)),
        "kindSource" -> jstr(p.kindSource), "protectionSource" -> jstr(p.protectionSource),
        "inferredKind" -> jstr(p.inferredKind), "inferredKindRule" -> jstr(p.inferredKindRule),
        "inferredProtection" -> jstr(p.inferredProtection), "inferredProtectionRule" -> jstr(p.inferredProtectionRule),
        "annotationCheck" -> p.disagreement.map(jstr).getOrElse("null"))
    }
    val owners = placed.map(_.f.owner).toSet ++ placed.flatMap(_.edges)
    val classesJson = c.classes.values.filter(ci => owners(ci.name)).toList.map { ci =>
      jobj("name" -> jstr(ci.name), "simple" -> jstr(ci.simple), "pkg" -> jstr(ci.pkg), "file" -> jstr(ci.file),
        "line" -> ci.line.toString, "url" -> jstr(s"$gh/$tip/${ci.file}#L${ci.line}"), "module" -> ci.isModule.toString,
        "bases" -> jarr(ci.bases.filter(b => owners(b) && b != ci.name).map(jstr)))
    }
    jtop(
      "header" -> jobj(
        "generator" -> jstr("tools/state-map/StateMap.scala"),
        "tip" -> jstr(tip), "github" -> jstr(gh),
        "kinds" -> jobj(
          "A" -> jstr("per-request scratch"), "B" -> jstr("lazy facts with a black hole"),
          "C" -> jstr("caches of derived values"), "D" -> jstr("interning and counters"), "E" -> jstr("trees"),
          "unclassified" -> jstr("no rule places it")),
        "rules" -> ruleJson.mkString("[\n", ",\n", "\n]"),
        "features" -> featJson.mkString("[\n", ",\n", "\n]"),
        "note" -> jstr("Rules are tried in order; the first that matches sets kind and protection. `evidence` says what the protection rests on: lexical (the accesses), annotation, type, owner, or asserted (dynamic, from §8, not visible at the access).")),
      "validation" -> validation.map(jstr).mkString("[\n", ",\n", "\n]"),
      "classes" -> classesJson.mkString("[\n", ",\n", "\n]"),
      "fields" -> fieldsJson.mkString("[\n", ",\n", "\n]"))
