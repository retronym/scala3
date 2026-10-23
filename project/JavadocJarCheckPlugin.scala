package dotty.tools.sbtplugin

import sbt.*
import sbt.Keys.*

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipFile

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

/** Checks the content of a javadoc JAR when it is packaged, so that `publish` fails rather than
 *  ship documentation with broken links or content that doesn't belong in it.
 *
 *  Nothing here is specific to this build: a project opts in with `javadocJarCheck := true` and
 *  describes what to look for with `javadocJarRules`.
 */
object JavadocJarCheckPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport {
    val javadocJarCheck = settingKey[Boolean]("Check the content of the javadoc JAR when it is packaged")
    val javadocJarRules = settingKey[JavadocJarRules]("What the javadoc JAR check looks for")
  }
  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Def.settings(
    javadocJarCheck := false,
    javadocJarRules := JavadocJarRules(),
    Compile / packageDoc := {
      val jar = (Compile / packageDoc).value
      if (javadocJarCheck.value) JavadocJarCheck(jar, javadocJarRules.value, streams.value.log)
      jar
    },
  )
}

/** @param forbiddenEntries   entries that must not be in the JAR
 *  @param minPages           the fewest HTML pages the JAR may have (an index and an API page)
 *  @param noInlinedSideMenu  reject scaladoc pages that embed the side menu
 *                            (generated without `-dynamic-side-menu`, quadratic in size)
 *  @param selfLinkBases      URL prefixes under which this JAR's pages are also published:
 *                            a link there to a page that is in the JAR should be relative
 *  @param missingAllowed     link targets that may be missing
 */
final case class JavadocJarRules(
  // javadoc itself ships the licenses of its scripts as `legal/*.md`
  forbiddenEntries: Seq[Regex] = Seq("^docs/".r, """\.pdf$""".r, """^(?!legal/).*\.md$""".r),
  minPages: Int = 2,
  noInlinedSideMenu: Boolean = false,
  selfLinkBases: Seq[String] = Nil,
  // javadoc's stylesheet imports this, but JDK builds without the DejaVu fonts don't ship it
  missingAllowed: Seq[Regex] = Seq("""^resources/fonts/dejavu\.css$""".r),
)

object JavadocJarCheck {
  private val LinkAttr = """\b(?:href|src)="([^"]*)"""".r
  private val CssUrl = """url\(\s*['"]?([^'")]+)['"]?\s*\)""".r
  private val Id = """\b(?:id|name)="([^"]*)"""".r
  private val Scheme = """^[a-zA-Z][a-zA-Z0-9+.-]*:.*""".r
  private val InlinedSideMenu = """data-dynamicSideMenu="false""""

  /** The problems of each kind, with a few examples of each */
  private final class Problems {
    val counts = mutable.LinkedHashMap.empty[String, Int]
    val examples = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
    def add(kind: String, example: => String): Unit = {
      counts(kind) = counts.getOrElse(kind, 0) + 1
      val ex = examples.getOrElseUpdate(kind, mutable.ArrayBuffer.empty)
      if (ex.size < 5) ex += example
    }
    def report: String = counts.map { case (kind, n) =>
      s"  $n $kind, e.g.\n" + examples(kind).map("    " + _).mkString("\n")
    }.mkString("\n")
  }

  def apply(jar: File, rules: JavadocJarRules, log: Logger): Unit = {
    val (entries, texts) = Using.resource(new ZipFile(jar)) { zip =>
      val entries = zip.entries().asScala.filterNot(_.isDirectory).map(_.getName).toSet
      val texts = zip.entries().asScala
        .filter(e => e.getName.endsWith(".html") || e.getName.endsWith(".css"))
        .map(e => e.getName -> new String(Using.resource(zip.getInputStream(e))(_.readAllBytes()), UTF_8))
        .toMap
      (entries, texts)
    }
    val pages = texts.keys.filter(_.endsWith(".html"))
    val problems = new Problems
    val idsOf = mutable.HashMap.empty[String, Set[String]]
    def ids(page: String) = idsOf.getOrElseUpdate(page, Id.findAllMatchIn(texts(page)).map(m => unescape(m.group(1))).toSet)

    for (e <- entries.toSeq.sorted; re <- rules.forbiddenEntries.find(_.findFirstIn(e).isDefined))
      problems.add(s"entries matching ${re.regex}", e)
    if (pages.size < rules.minPages)
      problems.add(s"HTML pages too few (need ${rules.minPages})", s"${pages.size} pages")

    var links = 0
    for ((file, text) <- texts.toSeq.sortBy(_._1)) {
      val isPage = file.endsWith(".html")
      if (isPage && rules.noInlinedSideMenu && text.contains(InlinedSideMenu))
        problems.add("pages with an inlined side menu", file)
      val refs = if (isPage) LinkAttr.findAllMatchIn(text) else CssUrl.findAllMatchIn(text)
      for (m <- refs; ref = unescape(m.group(1)) if ref.nonEmpty && !ref.startsWith("//") && !ref.startsWith("data:")) {
        links += 1
        if (Scheme.pattern.matcher(ref).matches) {
          for (base <- rules.selfLinkBases.find(ref.startsWith); target = path(ref.stripPrefix(base)) if entries(target))
            problems.add("links leaving the JAR for one of its own pages", s"$file -> $ref")
        } else {
          val target = if (path(ref).isEmpty) file else resolve(file, path(ref))
          val fragment = ref.indexOf('#') match { case -1 => ""; case i => percentDecode(ref.substring(i + 1)) }
          if (!entries(target) && !rules.missingAllowed.exists(_.findFirstIn(target).isDefined)) problems.add("links to a file not in the JAR (a directory counts)", s"$file -> $ref")
          else if (fragment.nonEmpty && target.endsWith(".html") && !ids(target)(fragment))
            problems.add("links to an anchor not on its page", s"$file -> $ref")
        }
      }
    }

    val summary = f"${jar.getName}: ${jar.length / 1048576.0}%.1f MB, ${entries.size} files, ${pages.size} pages, $links links checked"
    if (problems.counts.isEmpty) log.info(summary)
    else {
      // logged as well as thrown: `sbt --client` shows only the first line of the exception
      val message = s"$summary; the javadoc JAR has\n${problems.report}"
      log.error(message)
      sys.error(message)
    }
  }

  /** The path of a relative URL, without query and fragment, percent-decoded */
  private def path(ref: String): String = percentDecode(ref.takeWhile(c => c != '#' && c != '?'))

  private def resolve(from: String, rel: String): String = {
    val segments = mutable.ArrayBuffer((if (rel.startsWith("/")) Nil else from.split('/').toSeq.dropRight(1))*)
    for (s <- rel.split('/')) s match {
      case "" | "." =>
      case ".." => if (segments.nonEmpty) segments.remove(segments.size - 1)
      case s => segments += s
    }
    segments.mkString("/")
  }

  private def unescape(s: String): String =
    if (!s.contains('&')) s
    else s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&")

  private val PercentEscapes = """(?:%[0-9a-fA-F]{2})+""".r

  // Only `%XX` escapes: scaladoc anchors contain a literal `%` for the operator `%`
  // (`#Int_%-0`), which `URLDecoder` would decode (it parses `-0` as hex)
  private def percentDecode(s: String): String =
    if (!s.contains('%')) s
    else PercentEscapes.replaceAllIn(s, m => Regex.quoteReplacement(new String(
      m.matched.grouped(3).map(h => Integer.parseInt(h.substring(1), 16).toByte).toArray, UTF_8)))
}
