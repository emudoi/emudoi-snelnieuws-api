package com.snelnieuws.model

/** One engagement event in a POST /v3/events batch. The apps buffer these and
  * flush periodically. `client_id` is taken from the X-Client-Key gate, never
  * the body. `articleId` is the public id as served (numeric or "e<id>"), and
  * is None for non-article events. `ts` is the client-reported ISO-8601 event
  * time (best-effort; the server also stamps its own receive time). */
case class UserEventInput(
  `type`: String,
  articleId: Option[String] = None,
  dwellMs: Option[Long] = None,
  position: Option[Int] = None,
  list: Option[String] = None,
  country: Option[String] = None,
  language: Option[String] = None,
  // Promoted affinity dimensions (also queryable columns).
  category: Option[String] = None,
  source: Option[String] = None,
  // Flexible bag of everything else the app computes (open_source,
  // close_reason, direction, share_surface, is_local, age_hours,
  // from_article_id, search_query, category_slug, …). Stored as JSONB so the
  // backend can adopt any of these later without an app release. Values are
  // strings on the wire to keep parsing simple.
  props: Option[Map[String, String]] = None,
  ts: Option[String] = None
)

case class UserEventsBatch(events: List[UserEventInput])

object UserEvent {
  /** Event types we accept; anything else in a batch is dropped server-side.
    * Mirrors the GA4 instrumentation the apps already compute — article
    * engagement plus the explicit-interest events. */
  val AllowedTypes: Set[String] =
    Set(
      "impression", "open", "close", "read_engaged", "swipe", "share",
      "category_select", "search_submit", "feed_caught_up"
    )

  /** Max events accepted in a single POST /v3/events batch. */
  val MaxBatchSize: Int = 200
}
