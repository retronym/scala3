package dotty.tools
package dotc
package typer

import core.*
import Contexts.*, Symbols.*, Names.*, NameOps.*, Flags.*, Types.*
import ast.{tpd, untpd}
import util.Spans.*
import collection.mutable

/** Targeted typing of one statement list (interactive mode, see
 *  `Run.typingTarget` and `InteractiveDriver.runUpTo`).
 *
 *  Of the statements of a package, a template or a block on the path to the
 *  target only the statement whose span contains the target is typed (a class
 *  and its synthetic companion share the span, so both are); `skip` records
 *  the others on the run, with the context they would have been typed in, for
 *  the driver to type later in the same run. The rules, in the order `skip`
 *  applies them:
 *
 *   - always typed: imports and exports shape later contexts; package clauses
 *     are containers; zero-extent and synthetic definitions are what the next
 *     statement is built from.
 *   - source check: a span is an offset in its own source; an inlined macro
 *     body carries the macro's. (`Targeting.of`)
 *   - typed whole: a class or block reached other than along the path (typed
 *     ahead by a completer, a refinement's class) is typed whole, because its
 *     typed form is cached and reused. (The marking sites in `Typer`)
 *   - inline: never inside an inline method: its body is registered whole.
 *   - needed-before: the statements before the target's that it names are
 *     typed in order, so a chain of locals is not typed ahead recursively.
 *   - next-after: a target between statements types the next one (scaladoc
 *     completion needs the documented def).
 *   - the expansion of a kept definition that holds the target or comes next
 *     after it is typed whole (an `extension` block expands into methods whose
 *     own spans need not contain a target in the block's comment).
 *   - block stubs: a skipped local leaves a stub so a scope rebuilt from the
 *     typed tree sees it.
 *
 *  Design and findings: docs §3, I19-I28.
 */
private[typer] final class Targeting(
    typer: Typer, stats: List[untpd.Tree], exprOwner: Symbol, inBlock: Boolean, buf: mutable.ListBuffer[tpd.Tree])(using Context):
  import Targeting.*

  private val run = ctx.run.nn
  private val target = run.typingTarget

  private val nextAfterTarget: untpd.Tree | Null =
    if !stats.exists(_.span.contains(target)) then
      stats.find(s => s.span.exists && s.span.start >= target.end).orNull
    else null

  /** The span of a kept definition whose expansion is typed whole */
  private var forcedSpan: Span = NoSpan

  private val neededInBlock: Set[untpd.Tree] =
    if !inBlock then Set.empty
    else
      val targetIdx = stats.indexWhere(_.span.contains(target))
      if targetIdx < 0 then Set.empty
      else
        val before = stats.take(targetIdx).toArray
        val defined = before.map(definedNames)
        val needed = new Array[Boolean](before.length)
        var wanted = usedNames(stats(targetIdx))
        var i = before.length - 1
        while i >= 0 do
          if !needed(i) && defined(i).exists(wanted) then
            needed(i) = true
            wanted = wanted ++ usedNames(before(i))
          i -= 1
        before.indices.filter(needed).map(before(_)).toSet

  /** Is `stat` skipped? If so it is recorded (and, in a block, leaves a stub
   *  in `buf`); `ctx` is the context `stat` would be typed in.
   */
  def skip(stat: untpd.Tree)(using Context): Boolean =
    val skipped =
      stat.span.exists && !stat.span.isZeroExtent
      && !stat.isInstanceOf[untpd.PackageDef]
      && !isSyntheticDef(stat) // a desugaring's `$anonfun` in a closure's block, a pattern's temporaries: the statement after them needs them
      && !neededInBlock.contains(stat)
      && !stat.span.contains(target)
      && !(stat eq nextAfterTarget)
      && !forcedSpan.contains(stat.span)
    if skipped then
      if inBlock then buf ++= stubsOf(stat)
      run.skippedStats += Run.SkippedStat(stat, ctx, exprOwner, inBlock)
    else stat match
      case _: untpd.DefTree | _: untpd.ExtMethods =>
        if stat.span.contains(target) || (stat eq nextAfterTarget) then forcedSpan = stat.span
      case _ =>
    skipped

  /** A skipped definition of a block leaves a typed stub in the block: its
   *  name and symbol, no right-hand side, an untyped type tree, so that a
   *  scope rebuilt from the typed tree (`Interactive.contextOfPath` enters
   *  the symbol of every `MemberDef` of a `Block`) sees the local, while its
   *  info stays lazy until something asks for it. It carries the span of the
   *  statement, which `InteractiveDriver.spliceAll` matches it by.
   */
  private def stubsOf(stat: untpd.Tree)(using Context): List[tpd.Tree] = typer.expanded(stat) match
    case untpd.Thicket(trees) => trees.flatMap(stubsOf)
    case t: untpd.DefTree =>
      t.getAttachment(typer.SymOfTree) match
        case Some(sym) if sym.exists =>
          val stub: untpd.Tree = t match
            case v: untpd.ValDef => untpd.ValDef(v.name, untpd.TypeTree(), untpd.EmptyTree)
            case d: untpd.DefDef => untpd.DefDef(d.name, Nil, untpd.TypeTree(), untpd.EmptyTree)
            case td: untpd.TypeDef => untpd.TypeDef(td.name, untpd.EmptyTree)
            case _ => untpd.EmptyTree
          if stub.isEmpty then Nil
          else List(stub.withSpan(t.span).withType(if sym.isType then sym.typeRef else sym.termRef))
        case _ => Nil
    case _ => Nil

private[typer] object Targeting:

  /** The targeting of the list `stats` owned by `exprOwner`, if it is subject
   *  to it: there is a target, `Run.targetNextStats` marked this very list,
   *  and it is a list of the request's own source. Consumes the mark.
   */
  def of(typer: Typer, stats: List[untpd.Tree], exprOwner: Symbol, buf: mutable.ListBuffer[tpd.Tree])(using Context): Targeting | Null =
    val run = ctx.run.nn
    if !run.typingTarget.exists then null
    else
      val marked = exprOwner eq run.targetedStatsOwner
      val inBlock = run.targetedStatsInBlock
      run.targetedStatsOwner = NoSymbol
      run.targetedStatsInBlock = false
      if marked && (ctx.compilationUnit ne NoCompilationUnit) && (ctx.source eq ctx.compilationUnit.source)
      then new Targeting(typer, stats, exprOwner, inBlock, buf)
      else null

  private def isSyntheticDef(stat: untpd.Tree): Boolean = stat match
    case mdef: untpd.MemberDef => mdef.mods.is(Synthetic) || mdef.mods.is(Artifact)
    case _ => false

  /** The names a statement defines, and the names it mentions: a conservative
   *  footprint (a referenced name that some earlier statement defines makes
   *  that statement needed).
   */
  private def definedNames(t: untpd.Tree)(using Context): Set[Name] = t match
    case d: untpd.ValDef => Set(d.name)
    case d: untpd.DefDef => Set(d.name)
    case d: untpd.TypeDef => Set(d.name)
    case m: untpd.ModuleDef => Set(m.name, m.name.moduleClassName)
    case untpd.PatDef(_, pats, _, _) =>
      val acc = new untpd.UntypedTreeAccumulator[Set[Name]]:
        def apply(x: Set[Name], t: untpd.Tree)(using Context): Set[Name] = t match
          case untpd.Bind(n, body) => foldOver(x + n, body)
          case untpd.Ident(n) => x + n
          case _ => foldOver(x, t)
      pats.foldLeft(Set.empty[Name])((x, p) => acc(x, p))
    case _ => Set.empty

  private def usedNames(t: untpd.Tree)(using Context): Set[Name] =
    val acc = new untpd.UntypedTreeAccumulator[Set[Name]]:
      def apply(x: Set[Name], t: untpd.Tree)(using Context): Set[Name] = t match
        case untpd.Ident(n) => foldOver(x + n, t)
        case untpd.Select(q, n) => foldOver(x + n, t)
        case _ => foldOver(x, t)
    acc(Set.empty, t)
