//> using options -experimental
import scala.language.experimental.subCases
enum Version:
  case Legacy
  case Stable(major: Int, minor: Int)
case class Document(title: String, version: Version)
def version(d: Option[Document]) = d match
  case Some(x) if x.version match
    case Version.Stable(m, n) if m > 2 => s"$m.$n"
    case Version.Legacy => "legacy"
  case Some(x: Document) if x.title.nonEmpty if x.title.length match
    case 1 => "one"
    case n if n > 5 if n match
      case 6 => "six"
  case _ => "unsupported"
