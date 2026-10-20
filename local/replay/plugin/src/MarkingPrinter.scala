// LLM-assisted (Claude), see LLM_POLICY.md.
package replay

import dotty.tools.dotc.ast.Trees.{Tree, Untyped}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.printing.Texts.*

/** RefinedPrinter that brackets each tree's text in invisible markers carrying the tree's arena id.
 *
 *  Markers are spelled like ANSI SGR escapes (`ESC [ 1<id> m` opens, `ESC [ 2<id> m` closes) because
 *  `Text.layout` measures width with ANSI escapes stripped, so line breaking is unaffected.
 *  Requires `-color:never` so no real escapes appear. Trees the arena does not know (e.g. printed
 *  from inside a type) are left unmarked.
 */
class MarkingPrinter(_ctx: Context, idOf: Tree[?] => Int) extends RefinedPrinter(_ctx):
  override protected def toTextCore[T <: Untyped](tree: Tree[T]): Text =
    val txt = super.toTextCore(tree)
    val id = idOf(tree)
    if id < 0 || txt.isEmpty then txt
    else MarkingPrinter.wrap(txt, s"\u001b[1${id}m", s"\u001b[2${id}m")

  // RefinedPrinter drops a leading "new " with `stripPrefix`, which a leading marker defeats.
  override protected def constrText(tree: dotty.tools.dotc.ast.untpd.Tree): Text =
    MarkingPrinter.stripPrefixPastMarkers(toTextLocal(tree), keywordStr("new "))

object MarkingPrinter:
  private val leadingMarkers = "^(\u001b\\[\\d+m)*".r

  /** `Text.stripPrefix`, but looking past leading markers. Mirrors upstream exactly, including that
   *  its `Str` case always yields a fresh `Str`, so the enclosing `Closed` is rebuilt as a plain
   *  (splittable) `Fluid`; that affects line breaking, so we must do the same.
   */
  def stripPrefixPastMarkers(t: Text, pre: String): Text = t match
    case str: Str =>
      val s = str.s
      val m = leadingMarkers.findPrefixOf(s).getOrElse("")
      if s.startsWith(pre, m.length) then Str(m + s.substring(m.length + pre.length)) else Str(s)
    case Fluid(relems) =>
      val elems = relems.reverse
      val head = stripPrefixPastMarkers(elems.head, pre)
      if head eq elems.head then t else Fluid((head :: elems.tail).reverse)
    case Vertical(relems) =>
      val elems = relems.reverse
      val head = stripPrefixPastMarkers(elems.head, pre)
      if head eq elems.head then t else Vertical((head :: elems.tail).reverse)

  private def rebuild(t: Text, relems: List[Text]): Text = t match
    case _: Vertical => Vertical(relems)
    case _ if t.isClosed => Closed(relems)
    case _ => Fluid(relems)

  // `relems` are in reverse order: head is the last element, last is the first.
  // Markers go into the first/last *non-empty* leaf so emptiness tests in `layout`/`~` are unchanged.
  private def prefix(t: Text, m: String): Text = t match
    case str: Str => Str(m + str.s)
    case _ =>
      val elems = t.relems.reverse
      val i = elems.indexWhere(!_.isEmpty)
      rebuild(t, elems.updated(i, prefix(elems(i), m)).reverse)

  private def suffix(t: Text, m: String): Text = t match
    case str: Str => Str(str.s + m)
    case _ =>
      val i = t.relems.indexWhere(!_.isEmpty)
      rebuild(t, t.relems.updated(i, suffix(t.relems(i), m)))

  def wrap(t: Text, open: String, close: String): Text = suffix(prefix(t, open), close)

  final class Flat(val text: String, val offsets: Array[Int]) // offsets: id, start, end triples

  /** Strip markers, returning the plain text and [id, start, end) triples (end-exclusive,
   *  in UTF-16 code units). Trailing spaces before a newline are removed, as `Text.print` does,
   *  because a close marker can hide trailing spaces from `stripTrailingSpaces`.
   */
  def flatten(marked: String): Flat =
    val sb = new java.lang.StringBuilder(marked.length)
    val offs = scala.collection.mutable.ArrayBuffer[Int]()
    val stack = scala.collection.mutable.Stack[(Int, Int)]() // (id, start)
    var i = 0
    val n = marked.length
    // positions recorded before trailing-space stripping are clamped to the stripped line end
    def stripLine(): Unit =
      var e = sb.length
      while e > 0 && sb.charAt(e - 1) == ' ' do e -= 1
      if e < sb.length then
        sb.setLength(e)
        // ends are appended in non-decreasing order, so only a suffix of `offs` can need clamping
        var k = offs.length - 3
        while k >= 0 && offs(k + 2) > e do
          if offs(k + 1) > e then offs(k + 1) = e
          offs(k + 2) = e
          k -= 3
        for j <- stack.indices do
          val (id, s) = stack(j)
          if s > e then stack(j) = (id, e)
    while i < n do
      val c = marked.charAt(i)
      if c == '\u001b' && i + 2 < n && marked.charAt(i + 1) == '[' then
        var j = i + 2
        while marked.charAt(j) != 'm' do j += 1
        val kind = marked.charAt(i + 2)
        val id = marked.substring(i + 3, j).toInt
        if kind == '1' then stack.push((id, sb.length))
        else
          val (id0, start) = stack.pop()
          assert(id0 == id, s"marker mismatch $id0 vs $id")
          offs += id; offs += start; offs += sb.length
        i = j + 1
      else
        if c == '\n' then stripLine()
        sb.append(c)
        i += 1
    stripLine()
    assert(stack.isEmpty)
    Flat(sb.toString, offs.toArray)
