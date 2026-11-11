package dotty.tools.dotc
package config

import Settings.Setting.ChoiceWithHelp

object ScalaSettingsProperties:

  private val minTargetVersion = 17
  private val maxTargetVersion = 28
  private val minReleaseVersion = 17

  def supportedTargetVersions: List[String] =
    (minTargetVersion to maxTargetVersion).toList.map(_.toString)

  def supportedReleaseVersions: List[String] =
    val jdkVersion = Runtime.version().feature()
    val maxVersion = Math.min(jdkVersion, maxTargetVersion)
    (minReleaseVersion to maxVersion).toList.map(_.toString)

  def supportedSourceVersions: List[String] =
    SourceVersion.values.diff(SourceVersion.illegalInSettings)
      .map(_.toString).toList

  def supportedLanguageFeatures: List[ChoiceWithHelp[String]] =
    (Feature.values ++ Feature.deprecatedFeatures.map((n, _) => (n, "(deprecated, no effect)")))
      .map((n, d) => ChoiceWithHelp(n.toString, d))

  val legacyLanguageFeatures: List[String] =
    Feature.legacyFeatures

  def defaultClasspath: String = sys.env.getOrElse("CLASSPATH", ".")

  def defaultPageWidth: Int =
    pageWidth(System.getenv("COLUMNS"), if Properties.isWin then System.getenv("ANSICON") else null)

  /** Width from `COLUMNS`, else from `ANSICON` (Windows), else 80. Malformed values are ignored. */
  private[config] def pageWidth(columnsVar: String | Null, ansiconVar: String | Null): Int = {
    val defaultWidth = 80
    def columnsWidth =
      if columnsVar != null then columnsVar.toIntOption else None
    def ansiconWidth = // eg. "142x32766 (142x26)"
      if ansiconVar != null && ansiconVar.matches("[0-9]+x.*") then
        ansiconVar.substring(0, ansiconVar.indexOf("x")).toIntOption
      else None
    columnsWidth.orElse(ansiconWidth).getOrElse(defaultWidth)
  }
