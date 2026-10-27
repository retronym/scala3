package dotty.tools.pc.tests.completion

import java.lang
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{CompletableFuture, CompletionStage}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

import scala.meta.internal.metals.{CompilerInlayHintsParams, CompilerOffsetParams, CompilerRangeParams, CompilerVirtualFileParams}
import scala.meta.pc.CancelToken

import dotty.tools.pc.base.BasePCSuite

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.junit.{Assume, Test}

/** Replays an editor's request trace against the presentation compiler, with
 *  its real job queue (`Scala3CompilerAccess`), to measure the time requests
 *  wait in that queue behind each other. `-Dreplay.events=<json>` is the
 *  sequence (what `trace_to_events.py` makes of a Metals `lsp.trace.json`:
 *  didOpen/didChange/didClose with the file's text, hover, definition,
 *  completion, document highlight, inlay hints, semantic tokens with offsets,
 *  and the client's cancellations), `-Dreplay.idleCap=<ms>` caps the pauses
 *  between the trace's seconds (default 1000), `-Dreplay.classpath=<file>` types the files against that classpath instead of the test JVM's, and `-Dreplay.spread=burst`
 *  sends the requests of one second back to back, `spread` spaces them evenly
 *  over the second (the trace has whole seconds only). Nothing is measured
 *  here: the queue line (`[dotc-queue]`) and the driver's request log
 *  (`[dotc-session]`) on stderr are the output, with one `[replay]` line per
 *  request. The test is skipped without the property.
 */
class QueueReplaySuite extends BasePCSuite:

  // the classpath the file is typed against: `-Dreplay.classpath=<file with a path-separated list>`
  // (an editor's, jars), or else the test JVM's own (class directories)
  override protected def additionalClasspath: Seq[Path] =
    val cp = sys.props.get("replay.classpath").map(f => Files.readString(Paths.get(f)).trim)
      .getOrElse(sys.props.getOrElse("java.class.path", ""))
    cp.split(java.io.File.pathSeparator).toSeq.map(Paths.get(_))

  override protected def scalacOptions(classpath: Seq[Path]): Seq[String] =
    Seq("-Yexplicit-nulls", "-Wsafe-init", "-language:implicitConversions", "-Yinteractive-log")

  class Token extends CancelToken:
    val cancel = new CompletableFuture[lang.Boolean]()
    override def onCancel(): CompletionStage[lang.Boolean] = cancel
    override def checkCanceled(): Unit =
      if cancel.isDone && cancel.getNow(false) then throw new java.util.concurrent.CancellationException()

  @Test def replay(): Unit =
    val eventsPath = sys.props.get("replay.events")
    Assume.assumeTrue("-Dreplay.events not set", eventsPath.isDefined)
    val idleCap = sys.props.getOrElse("replay.idleCap", "1000").toLong
    val spread = sys.props.getOrElse("replay.spread", "burst") == "spread"
    val events = JsonParser.parseString(Files.readString(Paths.get(eventsPath.get))).getAsJsonArray.asScala.map(_.getAsJsonObject).toVector
    val texts = mutable.Map.empty[String, String]
    val tokens = mutable.Map.empty[String, Token]
    val futures = mutable.ListBuffer.empty[CompletableFuture[?]]
    val pc = presentationCompiler
    // virtual time: the trace's seconds, pauses capped, requests of one second burst or spread
    val groups = events.groupBy(_.get("t").getAsDouble).toSeq.sortBy(_._1)
    var clock = 0.0 // virtual ms
    var wall = System.nanoTime()
    var prevT = groups.head._1
    def sleepUntil(dueMs: Double): Unit =
      val due = wall + (dueMs * 1e6).toLong
      val wait = due - System.nanoTime()
      if wait > 0 then Thread.sleep(wait / 1000000, (wait % 1000000).toInt)
    var n = 0
    for (t, es) <- groups do
      clock += math.min((t - prevT) * 1000, idleCap.toDouble)
      prevT = t
      val step = if spread then 1000.0 / es.size else 0.0
      for (e, i) <- es.zipWithIndex do
        sleepUntil(clock + i * step)
        val kind = e.get("kind").getAsString
        val uri = if e.has("uri") then e.get("uri").getAsString else null
        kind match
          case "didOpen" | "didChange" => texts(uri) = e.get("text").getAsString
          case "didClose" => texts.remove(uri); pc.didClose(URI.create(uri))
          case "cancel" => tokens.get(e.get("id").getAsString).foreach(_.cancel.complete(true))
          case _ =>
            val id = e.get("id").getAsString
            val text = texts(uri)
            val offset = if e.has("offset") then e.get("offset").getAsInt else 0
            val token = new Token
            tokens(id) = token
            n += 1
            val file = uri.split('/').last
            val t0 = System.nanoTime()
            System.err.println(s"[replay] #$id $kind $file@$offset submit")
            val f: CompletableFuture[?] = kind match
              case "hover" => pc.hover(CompilerOffsetParams(URI.create(uri), text, offset, token))
              case "definition" => pc.definition(CompilerOffsetParams(URI.create(uri), text, offset, token))
              case "completion" => pc.complete(CompilerOffsetParams(URI.create(uri), text, offset, token))
              case "signatureHelp" => pc.signatureHelp(CompilerOffsetParams(URI.create(uri), text, offset, token))
              case "documentHighlight" => pc.documentHighlight(CompilerOffsetParams(URI.create(uri), text, offset, token))
              case "semanticTokens" => pc.semanticTokens(CompilerVirtualFileParams(URI.create(uri), text, token))
              case "inlayHint" =>
                val range = CompilerRangeParams(URI.create(uri), text, offset, e.get("endOffset").getAsInt, token)
                // VS Code's defaults for Metals: inferred types only
                pc.inlayHints(CompilerInlayHintsParams(range, true, false, false, false, false, false, false, false, false))
            f.whenComplete((_, ex) =>
              val how = if ex == null then "ok" else if f.isCancelled then "cancelled" else s"failed ${ex.getClass.getSimpleName}"
              System.err.println(f"[replay] #$id $kind $file@$offset done ${(System.nanoTime() - t0) / 1e6}%.1f ms $how"))
            futures += f
    for f <- futures do
      try f.get() catch case _: Exception => ()
    System.err.println(s"[replay] $n requests replayed in ${(System.nanoTime() - wall) / 1e9} s")
