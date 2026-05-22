package com.snelnieuws.model

/** Canonical language list, hardcoded server-side. Codes are ISO 639-1
  * alpha-2 (matches `articles.language` and the v3 `?language=xx` /
  * `Accept-Language` resolver in NewsServletV3).
  *
  * Hardcoded for the same reason as `Categories.all`: the UI list
  * shouldn't shrink just because the DB happens to have no Italian
  * articles right now. The CODE order is the rendering order —
  * iOS/Android render the picker in this order regardless of which
  * locale the names are rendered in.
  *
  * As of 2026-05-22 the snelmind summarize pipeline only emits 'en'.
  * The endpoint enumerates the languages the product intends to
  * support so clients can ship the picker before more content arrives.
  */
object Languages {
  final case class Language(code: String, name: String)

  /** Rendering order for the picker. Stays constant across locales. */
  val codes: Seq[String] = Seq("de", "fr", "it", "en", "es", "pl", "nl")

  /** localizedNames(locale)(code) = display name of `code` rendered
    * in `locale`. 7×7 = 49 cells. Diagonal entries (locale == code)
    * are the autonyms (German→"Deutsch", French→"Français", …).
    *
    * Spelling source: each row checked against the standard
    * vernacular term used by the language's own Wikipedia article
    * for the named language as of 2026-05-22. If you change any
    * cell, prefer the form that appears verbatim in that language's
    * Wikipedia infobox so app strings match what native speakers see
    * elsewhere. */
  val localizedNames: Map[String, Map[String, String]] = Map(
    "en" -> Map(
      "de" -> "German",  "fr" -> "French",   "it" -> "Italian",
      "en" -> "English", "es" -> "Spanish",  "pl" -> "Polish",
      "nl" -> "Dutch"
    ),
    "nl" -> Map(
      "de" -> "Duits",   "fr" -> "Frans",    "it" -> "Italiaans",
      "en" -> "Engels",  "es" -> "Spaans",   "pl" -> "Pools",
      "nl" -> "Nederlands"
    ),
    "de" -> Map(
      "de" -> "Deutsch",  "fr" -> "Französisch", "it" -> "Italienisch",
      "en" -> "Englisch", "es" -> "Spanisch",    "pl" -> "Polnisch",
      "nl" -> "Niederländisch"
    ),
    "fr" -> Map(
      "de" -> "Allemand", "fr" -> "Français", "it" -> "Italien",
      "en" -> "Anglais",  "es" -> "Espagnol", "pl" -> "Polonais",
      "nl" -> "Néerlandais"
    ),
    "it" -> Map(
      "de" -> "Tedesco",  "fr" -> "Francese", "it" -> "Italiano",
      "en" -> "Inglese",  "es" -> "Spagnolo", "pl" -> "Polacco",
      "nl" -> "Olandese"
    ),
    "es" -> Map(
      "de" -> "Alemán",   "fr" -> "Francés",  "it" -> "Italiano",
      "en" -> "Inglés",   "es" -> "Español",  "pl" -> "Polaco",
      "nl" -> "Neerlandés"
    ),
    "pl" -> Map(
      "de" -> "Niemiecki", "fr" -> "Francuski",  "it" -> "Włoski",
      "en" -> "Angielski", "es" -> "Hiszpański", "pl" -> "Polski",
      "nl" -> "Niderlandzki"
    )
  )

  /** Resolve the picker payload for a caller's locale. Unknown locales
    * fall back to the English row. The CODE order is stable regardless
    * of locale. */
  def forLocale(locale: String): Seq[Language] = {
    val row = localizedNames.getOrElse(locale, localizedNames("en"))
    codes.map(code => Language(code, row(code)))
  }
}
