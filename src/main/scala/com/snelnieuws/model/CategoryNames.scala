package com.snelnieuws.model

/** Locale-aware display names for the canonical categories.
  *
  * The list of CODES is the source of truth in `Categories.all` (sourced
  * from emudoi-snelmind's prompts.py). This object only adds DISPLAY
  * NAMES per locale. Adding a new category means BOTH updating
  * `Categories.all` AND adding one inner cell per row here.
  *
  * Caller-facing endpoint: GET /v3/categories returns these as the
  * `categories_localized` field alongside the (unchanged) `categories`
  * slug array. The slug array is the immutable legacy contract for
  * installed apps; only `categories_localized` is locale-aware.
  */
object CategoryNames {
  final case class Category(code: String, name: String)

  /** localizedNames(locale)(code) = display name of `code` in `locale`.
    * 7 outer keys × 21 inner = 147 cells. */
  val localizedNames: Map[String, Map[String, String]] = Map(
    "en" -> Map(
      "politics"       -> "Politics",
      "economy"        -> "Economy",
      "business"       -> "Business",
      "finance"        -> "Finance",
      "technology"     -> "Technology",
      "science"        -> "Science",
      "health"         -> "Health",
      "sports"         -> "Sports",
      "culture"        -> "Culture",
      "environment"    -> "Environment",
      "world"          -> "World",
      "local"          -> "Local",
      "crime"          -> "Crime",
      "entertainment"  -> "Entertainment",
      "defense"        -> "Defense",
      "energy"         -> "Energy",
      "accident"       -> "Accidents",
      "transportation" -> "Transportation",
      "animal"         -> "Animals",
      "expat"          -> "Expat",
      "other"          -> "Other"
    ),
    "nl" -> Map(
      "politics"       -> "Politiek",
      "economy"        -> "Economie",
      "business"       -> "Zakelijk",
      "finance"        -> "Financiën",
      "technology"     -> "Technologie",
      "science"        -> "Wetenschap",
      "health"         -> "Gezondheid",
      "sports"         -> "Sport",
      "culture"        -> "Cultuur",
      "environment"    -> "Milieu",
      "world"          -> "Wereld",
      "local"          -> "Lokaal",
      "crime"          -> "Misdaad",
      "entertainment"  -> "Entertainment",
      "defense"        -> "Defensie",
      "energy"         -> "Energie",
      "accident"       -> "Ongelukken",
      "transportation" -> "Vervoer",
      "animal"         -> "Dieren",
      "expat"          -> "Expat",
      "other"          -> "Overig"
    ),
    "de" -> Map(
      "politics"       -> "Politik",
      "economy"        -> "Wirtschaft",
      "business"       -> "Unternehmen",
      "finance"        -> "Finanzen",
      "technology"     -> "Technologie",
      "science"        -> "Wissenschaft",
      "health"         -> "Gesundheit",
      "sports"         -> "Sport",
      "culture"        -> "Kultur",
      "environment"    -> "Umwelt",
      "world"          -> "Welt",
      "local"          -> "Lokal",
      "crime"          -> "Kriminalität",
      "entertainment"  -> "Unterhaltung",
      "defense"        -> "Verteidigung",
      "energy"         -> "Energie",
      "accident"       -> "Unfälle",
      "transportation" -> "Verkehr",
      "animal"         -> "Tiere",
      "expat"          -> "Expat",
      "other"          -> "Sonstiges"
    ),
    "fr" -> Map(
      "politics"       -> "Politique",
      "economy"        -> "Économie",
      "business"       -> "Entreprises",
      "finance"        -> "Finance",
      "technology"     -> "Technologie",
      "science"        -> "Sciences",
      "health"         -> "Santé",
      "sports"         -> "Sport",
      "culture"        -> "Culture",
      "environment"    -> "Environnement",
      "world"          -> "Monde",
      "local"          -> "Local",
      "crime"          -> "Criminalité",
      "entertainment"  -> "Divertissement",
      "defense"        -> "Défense",
      "energy"         -> "Énergie",
      "accident"       -> "Accidents",
      "transportation" -> "Transport",
      "animal"         -> "Animaux",
      "expat"          -> "Expatriés",
      "other"          -> "Autres"
    ),
    "it" -> Map(
      "politics"       -> "Politica",
      "economy"        -> "Economia",
      "business"       -> "Aziende",
      "finance"        -> "Finanza",
      "technology"     -> "Tecnologia",
      "science"        -> "Scienza",
      "health"         -> "Salute",
      "sports"         -> "Sport",
      "culture"        -> "Cultura",
      "environment"    -> "Ambiente",
      "world"          -> "Mondo",
      "local"          -> "Locale",
      "crime"          -> "Criminalità",
      "entertainment"  -> "Intrattenimento",
      "defense"        -> "Difesa",
      "energy"         -> "Energia",
      "accident"       -> "Incidenti",
      "transportation" -> "Trasporti",
      "animal"         -> "Animali",
      "expat"          -> "Expat",
      "other"          -> "Altro"
    ),
    "es" -> Map(
      "politics"       -> "Política",
      "economy"        -> "Economía",
      "business"       -> "Negocios",
      "finance"        -> "Finanzas",
      "technology"     -> "Tecnología",
      "science"        -> "Ciencia",
      "health"         -> "Salud",
      "sports"         -> "Deportes",
      "culture"        -> "Cultura",
      "environment"    -> "Medio ambiente",
      "world"          -> "Mundo",
      "local"          -> "Local",
      "crime"          -> "Crimen",
      "entertainment"  -> "Entretenimiento",
      "defense"        -> "Defensa",
      "energy"         -> "Energía",
      "accident"       -> "Accidentes",
      "transportation" -> "Transporte",
      "animal"         -> "Animales",
      "expat"          -> "Expatriados",
      "other"          -> "Otros"
    ),
    "pl" -> Map(
      "politics"       -> "Polityka",
      "economy"        -> "Gospodarka",
      "business"       -> "Biznes",
      "finance"        -> "Finanse",
      "technology"     -> "Technologia",
      "science"        -> "Nauka",
      "health"         -> "Zdrowie",
      "sports"         -> "Sport",
      "culture"        -> "Kultura",
      "environment"    -> "Środowisko",
      "world"          -> "Świat",
      "local"          -> "Lokalne",
      "crime"          -> "Przestępczość",
      "entertainment"  -> "Rozrywka",
      "defense"        -> "Obronność",
      "energy"         -> "Energia",
      "accident"       -> "Wypadki",
      "transportation" -> "Transport",
      "animal"         -> "Zwierzęta",
      "expat"          -> "Ekspaci",
      "other"          -> "Inne"
    )
  )

  /** Resolve the picker payload for a caller's locale. Unknown locales
    * fall back to the English row. Order is taken from `Categories.all`
    * so the slug array and the localized array line up index-for-index —
    * the contract apps rely on. */
  def forLocale(locale: String): Seq[Category] = {
    val row = localizedNames.getOrElse(locale, localizedNames("en"))
    Categories.all.map(code => Category(code, row(code)))
  }
}
