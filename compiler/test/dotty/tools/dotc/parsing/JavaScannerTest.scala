package dotty.tools
package dotc
package parsing

import util.SourceFile
import JavaScanners.JavaScanner
import JavaTokens.*

import org.junit.Assert.assertEquals
import org.junit.Test

class JavaScannerTest extends DottyTest:

  /** The text of each token, skipping erroneous ones */
  private def texts(code: String): List[String] =
    val scanner = JavaScanner(SourceFile.virtual("J.java", code))
    val texts = List.newBuilder[String]
    while scanner.token != EOF do
      if scanner.token == STRINGLIT then texts += scanner.strVal.nn
      else if scanner.token == IDENTIFIER then texts += scanner.name.toString
      scanner.nextToken()
    texts.result()

  @Test def unclosedStringLiteralIsDiscarded: Unit =
    assertEquals(List("next"), texts("\"abc\nnext"))

  @Test def unclosedCharacterLiteralIsDiscarded: Unit =
    assertEquals(List("b"), texts("'ab"))
