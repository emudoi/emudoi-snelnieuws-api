package com.snelnieuws.model

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

/**
 * Mirror of the trend batch produced by litikai-marketing-api onto the
 * `seo.trends.collected` Kafka topic. `source` is Option for forward-compat
 * (older producer deployments may omit it; defaults to "google_trends" at the
 * consumer), exactly like `SummarizedArticleExport.language`. `terms` are
 * rank-ordered — index+1 = rank (1 = hottest).
 */
case class SeoTrendsExport(
  geo: String,
  collectedAt: String,
  source: Option[String],
  terms: List[String]
)

object SeoTrendsExport {
  implicit val encoder: Encoder[SeoTrendsExport] = deriveEncoder
  implicit val decoder: Decoder[SeoTrendsExport] = deriveDecoder
}

case class SeoTrendsExportEvent(
  eventType: String,
  trends: SeoTrendsExport
)

object SeoTrendsExportEvent {
  implicit val encoder: Encoder[SeoTrendsExportEvent] = deriveEncoder
  implicit val decoder: Decoder[SeoTrendsExportEvent] = deriveDecoder
}
