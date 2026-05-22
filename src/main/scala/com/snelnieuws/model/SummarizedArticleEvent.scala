package com.snelnieuws.model

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

/**
 * Mirror of `SummarizedArticleExport` in emudoi-snelnieuws-ingestion-api.
 * `createdAt` is Option because older producer deployments omit it; we don't
 * use it on the consumer side anyway — `publishedAt` is what lands in the table.
 *
 * `language` is Option[String] for the same forward-compat reason: messages
 * emitted BEFORE the producer-side language change (V_a of
 * language_support/backend_tasks.txt §4) still parse cleanly on this consumer
 * because circe's deriveDecoder treats absent JSON fields as None. New
 * messages carry Some("en"|"nl"|…) and ArticleRepository.upsertByTitle
 * defaults None to "en" at the DB layer.
 */
case class SummarizedArticleExport(
  author: Option[String],
  title: String,
  description: Option[String],
  url: String,
  urlToImage: Option[String],
  publishedAt: String,
  createdAt: Option[String],
  category: Option[String],
  sharedCategories: Option[List[String]],
  country: Option[String],
  sharedCountries: Option[List[String]],
  language: Option[String]
)

object SummarizedArticleExport {
  implicit val encoder: Encoder[SummarizedArticleExport] = deriveEncoder
  implicit val decoder: Decoder[SummarizedArticleExport] = deriveDecoder
}

case class SummarizedArticleExportEvent(
  eventType: String,
  article: SummarizedArticleExport
)

object SummarizedArticleExportEvent {
  implicit val encoder: Encoder[SummarizedArticleExportEvent] = deriveEncoder
  implicit val decoder: Decoder[SummarizedArticleExportEvent] = deriveDecoder
}
