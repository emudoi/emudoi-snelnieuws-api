package com.snelnieuws.model

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto._

import java.time.OffsetDateTime

/** Twin of the case class in emudoi-snelnieuws-ingestion-api's common
  * subproject (notifications_clickbait_tasks.txt §7). The producer side
  * controls the shape of topNews + selectionMetadata; we treat them as
  * opaque Json on the consumer.
  *
  * notificationMessages keys are ISO 639-1 codes (Languages.codes); no
  * specific language is required to be present — dispatch fans out per
  * key. */
case class TopStoryPayload(
  representativeArticleId: Long,
  topNews: Json,
  notificationMessages: Map[String, String],
  selectionTier: Int,
  selectionMetadata: Json
)

object TopStoryPayload {
  implicit val encoder: Encoder[TopStoryPayload] = deriveEncoder
  implicit val decoder: Decoder[TopStoryPayload] = deriveDecoder
}

case class TopStoryEvent(
  eventType: String, // "top_story.selected"
  payload: TopStoryPayload
)

object TopStoryEvent {
  val EventTypeSelected: String = "top_story.selected"

  implicit val encoder: Encoder[TopStoryEvent] = deriveEncoder
  implicit val decoder: Decoder[TopStoryEvent] = deriveDecoder
}

/** Row shape read back by NotificationService.dispatch when it looks up
  * the latest undispatched top_summary. */
case class TopSummaryRow(
  id: Long,
  createdAt: OffsetDateTime,
  topNews: Json,
  notificationMessages: Map[String, String],
  selectionTier: Int,
  selectionMetadata: Option[Json]
)
