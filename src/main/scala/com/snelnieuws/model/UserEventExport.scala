package com.snelnieuws.model

import java.util.UUID

/** The shape published to the `user.events` Kafka topic for the recommender
  * (emudoi-snelnieuws-recommender) to consume. Mirrors the enriched row written
  * to `user_events` — the article snapshot (title/url + features) is already
  * resolved server-side, so the consumer never has to call back for catalog
  * data. `clientId` is the stable per-install id; `articleId` is the public id
  * exactly as served (so it joins to feed_serves and the catalog). */
case class UserEventExport(
  clientId: UUID,
  eventType: String,
  articleId: Option[String],
  title: Option[String],
  url: Option[String],
  category: Option[String],
  source: Option[String],
  language: Option[String],
  country: Option[String],
  position: Option[Int],
  listName: Option[String],
  dwellMs: Option[Long],
  ts: Option[String]
)
