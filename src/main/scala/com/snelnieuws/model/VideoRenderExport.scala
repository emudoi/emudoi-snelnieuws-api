package com.snelnieuws.model

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

/**
 * Mirror of `VideoRenderExport` in emudoi-snelnieuws-ingestion-api. Published
 * on topic `snelnieuws.videos.rendered` when an ingestion video_render becomes
 * playable (video_host_path set) or is removed.
 *
 * `renderId` is the ingestion video_renders.id — the stable key we store as
 * `videos.id` and use as the shareable id. `url` / `urlToImage` are the SOURCE
 * article's publisher link + image (from ingestion_articles via real_article_id)
 * so tapping a video opens the same page its source article would.
 *
 * All non-essential fields are Option for forward-compat — circe's
 * deriveDecoder maps absent JSON fields to None.
 */
case class VideoRenderExport(
  renderId:    Long,
  title:       String,
  description: Option[String],
  streamUrl:   String,
  url:         Option[String],
  urlToImage:  Option[String],
  articleId:   Option[Long],
  category:    Option[String],
  country:     Option[String],
  language:    Option[String],
  durationSec: Option[Double],
  variant:     Option[String],
  publishedAt: Option[String]
)

object VideoRenderExport {
  implicit val encoder: Encoder[VideoRenderExport] = deriveEncoder
  implicit val decoder: Decoder[VideoRenderExport] = deriveDecoder
}

/** `eventType` is "upserted" (video ready / updated) or "deleted" (removed
 *  from the reel). For "deleted" only `renderId` is meaningful. */
case class VideoRenderExportEvent(
  eventType: String,
  video:     VideoRenderExport
)

object VideoRenderExportEvent {
  implicit val encoder: Encoder[VideoRenderExportEvent] = deriveEncoder
  implicit val decoder: Decoder[VideoRenderExportEvent] = deriveDecoder
}
