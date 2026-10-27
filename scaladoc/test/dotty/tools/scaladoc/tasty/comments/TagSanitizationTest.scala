package dotty.tools.scaladoc
package tasty
package comments

import org.junit.Test
import org.junit.Assert.*

import java.nio.file.Files

class TagSanitizationTest extends BaseHtmlTest:
  private def docHtml(cls: String): String =
    super.docHtml("sanitization", cls, "markdown")

  @Test def scriptTag(): Unit =
    val html = docHtml("Script")
    assertEquals("<p>alert('hello')</p>", html)

  @Test def scriptTagWithSpaces(): Unit =
    val html = docHtml("ScriptWithSpaces")
    assertEquals("<p>&lt; script &gt;alert('hello')</p>", html)

  @Test def scriptTagWithSafeChar(): Unit =
    val html = docHtml("FakeSafeScript")
    assertEquals("<p>alert('hello')</p>", html)

  @Test def notATag(): Unit =
    val html = docHtml("NotATag")
    assertEquals("<p>Example &lt; Second &lt;: Third &lt;= Fourth</p>", html)

  @Test def notATagButHasGreaterThan(): Unit =
    val html = docHtml("NotATagButHasGreaterThan")
    assertEquals("<p>Example &lt; Second &gt;: Third</p>", html)

  @Test def notATagButNoSpaces(): Unit =
    val html = docHtml("NotATagButNoSpaces")
    assertEquals("<p>a&lt;b</p>", html)

  @Test def tagOutsideCode(): Unit =
    val html = docHtml("TagOutsideCode")
    assertFalse(html, html.contains("<script>"))
    assertFalse(html, html.contains("</script>"))

  @Test def linkToTagLike(): Unit =
    val html = docHtml("LinkToTagLike")
    // ensure we don't treat the text between <:< and >:> as a tag content
    assertTrue(html, html.contains("or"))

  @Test def angleBracketsInCodeSpan(): Unit =
    val html = docHtml("AngleBracketsInCodeSpan")
    assertEquals(
      "<p><code>x &lt; 0</code> and <code>0 &lt;= n &lt; k</code>, then <code>\"&lt;cycle&gt;\"</code> and <code>&lt;script&gt;alert('hello')&lt;/script&gt;</code>.</p>",
      html)

  @Test def codeSpanDoesNotCrossParagraph(): Unit =
    val html = docHtml("CodeSpanDoesNotCrossParagraph")
    assertFalse(html, html.contains("<code>"))
    assertTrue(html, html.contains("a &lt; b"))

  @Test def doubleBacktickCodeSpan(): Unit =
    val html = docHtml("DoubleBacktickCodeSpan")
    assertEquals("<p><code>a ` &lt; b</code> and <code>&lt;b&gt;bold&lt;/b&gt;</code></p>", html)

  @Test def angleBracketsInCodeSpanWiki(): Unit =
    val html = super.docHtml("sanitization", "AngleBracketsInCodeSpan", "wiki")
    assertFalse(html, html.contains("<script>"))
    assertFalse(html, html.contains("</script>"))
    assertTrue(html, html.contains("&lt;script&gt;"))
