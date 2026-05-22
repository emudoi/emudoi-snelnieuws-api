package com.snelnieuws.model

import java.time.OffsetDateTime

case class ImageCacheRow(
  sourceUrl: String,
  relativePath: String,
  contentType: Option[String],
  sizeBytes: Option[Long],
  status: String,
  downloadedAt: Option[OffsetDateTime],
  lastAttemptAt: OffsetDateTime,
  attempts: Int,
  // Defaulted so existing test sites and repository code paths that
  // build a Row without these two new V23 fields stay source-compatible.
  // doobie's row mapper assigns positionally, so the SELECTs in
  // ImageCacheRepository must include the new columns in matching order.
  lastFailureReason: Option[String] = None,
  lastFailureStatusCode: Option[Int] = None
)

object ImageCacheStatus {
  val Downloaded = "downloaded"
  val Failed     = "failed"
}
