package com.snelnieuws.model

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

/** Hand-off envelope for an image URL whose fast-path fetch failed in a
  * way the classifier judged retryableSlow=true. Consumed by
  * ImageRetrySlowConsumer (same JVM, separate thread pool, separate
  * HttpClient with longer timeouts).
  *
  *  - eventType:      always "image.retry.requested" for now; reserved
  *                    so a future event family can share the topic.
  *  - sourceUrl:      original article image URL.
  *  - originalReason: the DownloadFailure.reason token that triggered
  *                    the hand-off (e.g. "timeout", "http_5xx") so the
  *                    slow consumer's log lines preserve causality.
  *  - firstAttemptAt: ISO-8601 UTC, when the fast path gave up. The
  *                    slow consumer can compute hand-off latency from
  *                    log timestamps without polling the database. */
case class ImageRetryEvent(
  eventType: String,
  sourceUrl: String,
  originalReason: String,
  firstAttemptAt: String
)

object ImageRetryEvent {
  val EventTypeRetryRequested: String = "image.retry.requested"

  implicit val encoder: Encoder[ImageRetryEvent] = deriveEncoder
  implicit val decoder: Decoder[ImageRetryEvent] = deriveDecoder
}
