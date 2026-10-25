package tests.macros

import scala.quoted.*

/** A latch for the typer: `Latch.await` is a transparent inline macro, so it
 *  is expanded while the enclosing statement is typed, and its expansion
 *  blocks for as long as the file named by the system property `xray.latch`
 *  exists. The x-ray server (`dotty.tools.pc.xray.XrayServer`) creates the
 *  file before a "latched" request and deletes it when the user releases or
 *  cancels, so that a cancellation lands at a known point of the typing,
 *  deterministically. Without the property, the macro returns at once.
 */
object Latch:
  transparent inline def await: Int = ${ awaitImpl }

  private def awaitImpl(using Quotes): Expr[Int] =
    sys.props.get("xray.latch").foreach { path =>
      val file = new java.io.File(path)
      while file.exists() do Thread.sleep(10)
    }
    '{ 0 }
