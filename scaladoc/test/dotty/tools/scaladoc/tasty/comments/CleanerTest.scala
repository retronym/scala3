package dotty.tools.scaladoc
package tasty
package comments

import org.junit.Test
import org.junit.Assert.*

/** `Cleaner.clean` removes HTML tags from a comment before it is parsed, but must leave code spans alone. */
class CleanerTest:
  private def clean(body: String): String = Cleaner.clean(s"/** $body */").mkString("\n")

  @Test def tagOutsideCodeSpanIsRemoved(): Unit =
    assertEquals("a x b", clean("a <script>x</script> b"))

  @Test def angleBracketsInCodeSpanAreKept(): Unit =
    assertEquals("`x < 0` and `0 <= n < k`", clean("`x < 0` and `0 <= n < k`"))

  @Test def tagInCodeSpanIsKept(): Unit =
    assertEquals("`<cycle>` and `<script>x</script>`", clean("`<cycle>` and `<script>x</script>`"))

  @Test def textUpToLaterGreaterThanIsKept(): Unit =
    // used to be removed as one tag, from the '<' in `<=` to the '>' in `>=`
    assertEquals("`0 <= n` is kept, `n >= 0`", clean("`0 <= n` is kept, `n >= 0`"))

  @Test def doubleBacktickCodeSpanIsKept(): Unit =
    assertEquals("``a ` <cycle>`` and b", clean("``a ` <cycle>`` and <script>b</script>"))

  @Test def unclosedBacktickDoesNotProtectLaterTags(): Unit =
    assertEquals("a ` b x", clean("a ` b <script>x</script>"))

  @Test def codeSpanDoesNotCrossParagraph(): Unit =
    // the first backtick is not closed within its paragraph, so the second one is not its closing run
    assertEquals(List("", "`a x", "", "b`"), Cleaner.clean("/**\n * `a <script>x</script>\n *\n * b` */"))
