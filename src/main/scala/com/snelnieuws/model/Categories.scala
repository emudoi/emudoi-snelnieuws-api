package com.snelnieuws.model

/** Canonical category list, hardcoded server-side. Mirrors the
  * `CATEGORIES` tuple in `emudoi-snelmind/src/snelmind/agents/prompts.py`
  * which is the SOURCE OF TRUTH — it's what the LLM is constrained to
  * pick from when categorizing each summarized article. Keep these two
  * lists in sync; if a category is added there, add it here too (and
  * order matters: the iOS UI renders them in the order returned).
  *
  * The order intentionally keeps the original 13 first (so existing UI
  * scroll positions / habits stay stable) and adds the 8 new categories
  * at the end. The 8 additions came from a 7452-article open-ended LLM
  * discovery run on 2026-05-20 (see
  * snelmind-experiment/output/clusters.json for the source data).
  *
  * Why hardcoded here rather than computed from `SELECT DISTINCT
  * category FROM articles`: the UI list shouldn't shrink just because
  * the DB happens to have no `science` articles right now.
  */
object Categories {
  val all: Seq[String] = Seq(
    "politics",
    "economy",
    "business",
    "finance",
    "technology",
    "science",
    "health",
    "sports",
    "culture",
    "environment",
    "world",
    "local",
    "crime",
    "entertainment",
    "defense",
    "energy",
    "accident",
    "transportation",
    "animal",
    "expat",
    "other"
  )
}
