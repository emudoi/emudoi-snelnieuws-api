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
  ts: Option[String] = None
)

case class UserEventsBatch(events: List[UserEventInput])

object UserEvent {
  /** Event types we accept; anything else in a batch is dropped server-side.
    * Mirrors the GA4 instrumentation the apps already compute. */
  val AllowedTypes: Set[String] =
    Set("impression", "open", "close", "read_engaged", "swipe", "share")

  /** Max events accepted in a single POST /v3/events batch. */
  val MaxBatchSize: Int = 200
}
