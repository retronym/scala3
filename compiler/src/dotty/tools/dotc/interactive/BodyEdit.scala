package dotty.tools
package dotc
package interactive

import ast.{Positioned, tpd, untpd}
import core.*
import Contexts.*, Flags.*, Names.*, StdNames.nme, Symbols.*
import util.{SourceFile, Spans}
import Spans.{Span, NoSpan}
import util.Spans.spanCoord

import scala.collection.mutable

/** An edit of the content of an interactive session, classified against the
 *  session's parse of the old content, and the means to carry the session
 *  across it: the offset map, the span shift, the copy of the edited
 *  definition. Design: docs/_docs/internals/interactive-session.md §12.
 */
private[interactive] object BodyEdit:

  /** The edit region, between the common prefix and the common suffix of
   *  the old and the new content: old `[start, oldEnd)`, new `[start, newEnd)`.
   */
  final class Region(val start: Int, val oldEnd: Int, val newEnd: Int):
    val delta: Int = newEnd - oldEnd

    /** An offset of the old content in the new: unchanged before the region,
     *  moved by `delta` after it, clamped inside it (only discarded trees
     *  have offsets there).
     */
    def offset(o: Int): Int =
      if o < start then o
      else if o >= oldEnd then o + delta
      else start + math.min(o - start, newEnd - start)

    /** The span of the old content in the new. Its start and point map as
     *  offsets; so does its end, except where the edit is an insertion at its
     *  end: whether the inserted text is inside the span or after it is the
     *  `anchor`'s to say, a span containing it (the edited definition and
     *  what encloses it) taking it in. The anchor is the edited definition's
     *  old span, or the region itself for a layout edit.
     */
    def span(sp: Span, anchor: Span): Span =
      if !sp.exists then sp
      else
        val s = offset(sp.start)
        val end =
          if sp.isZeroExtent then s
          else if sp.end == start && start == oldEnd && !(anchor != regionSpan && sp.start <= anchor.start && anchor.end <= sp.end) then sp.end
          else offset(sp.end)
        if sp.isSynthetic then Span(s, end)
        else Span(s, end, offset(sp.point))

    val regionSpan: Span = Span(start, oldEnd)

    /** Does the edit change the text of `sp` (old offsets): overlap it, or
     *  insert strictly inside it?
     */
    def touches(sp: Span): Boolean =
      sp.exists && (if start == oldEnd then sp.start < start && start < sp.end else sp.start < oldEnd && start < sp.end)

    override def toString = s"Region([$start, $oldEnd) -> [$start, $newEnd))"

  def region(old: String, now: String): Region =
    val n = math.min(old.length, now.length)
    var p = 0
    while p < n && old.charAt(p) == now.charAt(p) do p += 1
    var q = 0
    while q < n - p && old.charAt(old.length - 1 - q) == now.charAt(now.length - 1 - q) do q += 1
    Region(p, old.length - q, now.length - q)

  /** How an edit is classified. */
  enum Kind:
    /** Only the right-hand side of the member definition `old` differs; `now` is its new parse. */
    case Body(old: untpd.ValOrDefDef, now: untpd.ValOrDefDef)
    /** No tree differs: whitespace, or a comment that is not a doc comment. */
    case Layout
    /** Anything else: the reason, for the log. */
    case Fresh(reason: String)

  /** The innermost member definition (a `DefDef` or `ValDef` among the
   *  statements of a package or a template) whose right-hand side contains
   *  the region, in the parse of the old content.
   */
  def editedMember(tree: untpd.Tree, r: Region)(using Context): untpd.ValOrDefDef | Null =
    def inMembers(stats: List[untpd.Tree]): untpd.ValOrDefDef | Null =
      stats.iterator.map(inMember).find(_ != null).getOrElse(null)
    def inMember(stat: untpd.Tree): untpd.ValOrDefDef | Null = stat match
      case d: untpd.ValOrDefDef =>
        val rhs = d.rhs.span
        if rhs.exists && rhs.start <= r.start && r.oldEnd <= rhs.end then d else null
      case untpd.PackageDef(_, stats) => inMembers(stats)
      case untpd.TypeDef(_, impl: untpd.Template) => inMembers(impl.body)
      case untpd.ModuleDef(_, impl) => inMembers(impl.body)
      case untpd.Thicket(stats) => inMembers(stats)
      case _ => null
    inMember(tree)

  /** Why the edited member `d` is not eligible, or null: its info must be a
   *  function of its signature alone, and its body no part of its interface.
   */
  def ineligible(d: untpd.ValOrDefDef)(using Context): String | Null =
    val mods = d.rawMods
    if d.tpt.isEmpty then "no declared result type"
    else if mods.isOneOf(Inline | Transparent | Macro) then "inline"
    else if d.name == nme.CONSTRUCTOR then "a constructor"
    else if mods.is(Synthetic) then "synthetic"
    else null

  /** Classify the edit from `old` (a parse of the old content no Namer has
   *  touched) to `now` (the parse of the new content): structural equality
   *  modulo the region's offset map, except inside the right-hand side of
   *  the edited member. Over the whole file: the scanner's state at the end
   *  of the region could change the parse of text that did not change.
   */
  def classify(old: untpd.Tree, now: untpd.Tree, r: Region)(using Context): Kind =
    val edited = editedMember(old, r)
    val anchor = if edited != null then edited.span else r.regionSpan
    var counterpart: untpd.ValOrDefDef | Null = null
    var mismatch: String | Null = null

    def fail(o: Any, why: String): Boolean =
      if mismatch == null then
        val where = o match
          case p: Positioned => s" at ${p.span}"
          case _ => ""
        mismatch = s"$why$where"
      false

    def sameMods(o: untpd.MemberDef, n: untpd.MemberDef): Boolean =
      same(o.rawMods, n.rawMods)
      && (o.rawComment.map(_.raw) == n.rawComment.map(_.raw) || fail(o, "doc comment"))

    def sameList(o: List[?], n: List[?]): Boolean =
      (o.length == n.length || fail(o, "statement count")) && o.lazyZip(n).forall(same)

    def same(o: Any, n: Any): Boolean = (o, n) match
      case (o: Positioned, n: Positioned) =>
        if o.getClass ne n.getClass then fail(o, s"${o.getClass.getSimpleName} vs ${n.getClass.getSimpleName}")
        else if r.span(o.span, anchor) != n.span then fail(o, s"span ${r.span(o.span, anchor)} vs ${n.span}")
        else
          val modsOk = (o, n) match
            case (o: untpd.MemberDef, n: untpd.MemberDef) => sameMods(o, n)
            case _ => true
          modsOk && {
            val oi = o.asInstanceOf[Product].productIterator
            val ni = n.asInstanceOf[Product].productIterator
            if o eq edited then
              counterpart = n.asInstanceOf[untpd.ValOrDefDef]
              // the right-hand side is the last field of a `ValDef` or `DefDef`
              val os = oi.toList.init
              val ns = ni.toList.init
              os.lazyZip(ns).forall(same)
            else oi.zip(ni).forall(same) && (oi.hasNext == ni.hasNext || fail(o, "arity"))
          }
      case (o: List[?], n: List[?]) => sameList(o, n)
      case (o: untpd.Modifiers, n: untpd.Modifiers) =>
        (o.flags == n.flags || fail(o, s"flags ${o.flags} vs ${n.flags}"))
        && same(o.privateWithin, n.privateWithin) && same(o.annotations, n.annotations) && same(o.mods, n.mods)
      case (o: Name, n: Name) => (o eq n) || unnumbered(o) == unnumbered(n) || fail(o, s"name $o vs $n")
      case _ => o == n || fail(o, s"$o vs $n")

    if !same(old, now) then Kind.Fresh(s"differs: $mismatch")
    else if edited == null then Kind.Layout
    else
      val now = counterpart.nn
      if same(edited.rhs, now.rhs) then Kind.Layout
      else ineligible(edited) match
        case null => Kind.Body(edited, now)
        case why => Kind.Fresh(s"${edited.name}: $why")

  /** A name without the number of a fresh name (`_$2`, `x$3`): an edit
   *  that adds a placeholder renumbers the ones after it.
   */
  private def unnumbered(name: Name): String = name.toString.replaceAll("\\$\\d+$", "\\$")

  /** A copy of the untyped `tree`, every node new and of `src`, so that
   *  indexing it (the Namer attaches symbols and replaces modifiers) leaves
   *  the parse it came from untouched.
   */
  def copy(tree: untpd.Tree, src: SourceFile)(using Context): untpd.Tree =
    val copier = new untpd.UntypedTreeMap:
      override def transform(t: untpd.Tree)(using Context): untpd.Tree =
        val t1 = super.transform(t)
        if (t1 eq t) && !t.isEmpty then t.cloneIn(src) else t1
    copier.transform(tree)

  /** Shift, in place, the spans of everything the session keeps of `src`
   *  from the old content to the new: every `Positioned` of `src` reachable
   *  from `roots` (fields, modifiers, attachments) and the coordinates and
   *  annotation trees of `syms` and of the definitions in the trees. Trees
   *  are DAGs and the shift is not idempotent, so each node and symbol is
   *  visited once; trees of other sources are visited (an inlined body holds
   *  the call) and not shifted.
   */
  final class Shift(r: Region, anchor: Span, src: SourceFile)(using Context):
    private val seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean])
    var trees = 0
    var symbols = 0

    def tree(x: Any): Unit = x match
      case p: Positioned =>
        if seen.add(p) then
          if (p.source eq src) && p.span.exists then
            p.span = r.span(p.span, anchor)
            trees += 1
          p match
            case m: untpd.MemberDef if m.rawMods != null => tree(m.rawMods)
            case _ =>
          p match
            case t: tpd.Tree if t.hasType && t.symbol.exists && t.isDef => symbol(t.symbol)
            // a source annotation in a type (`c: @switch`) is a tree in the type
            case t: tpd.TypeTree if t.hasType =>
              t.tpe.foreachPart {
                case at: Types.AnnotatedType => tree(at.annot.tree)
                case _ =>
              }
            case _ =>
          p match
            case t: ast.Trees.Tree[?] =>
              t.allAttachments.foreach((_, v) => tree(v))
            case _ =>
          p.asInstanceOf[Product].productIterator.foreach(tree)
      case xs: List[?] => xs.foreach(tree)
      case m: untpd.Modifiers => tree(m.annotations); tree(m.mods)
      case _ =>

    def symbol(sym: Symbol): Unit =
      if sym.exists && seen.add(sym) && sym.associatedFile == src.file then
        if sym.coord.isSpan then
          sym.coord = spanCoord(r.span(sym.span, anchor))
          symbols += 1
        sym.annotationsUNSAFE.foreach(a => if a.isEvaluated then tree(a.tree))
        sym.rawParamss.foreach(_.foreach(symbol))
        if sym.isClass then sym.asClass.unforcedDecls.foreach(symbol)
end BodyEdit
