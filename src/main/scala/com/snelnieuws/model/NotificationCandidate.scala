package com.snelnieuws.model

import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Input shape for `NotificationCandidateRepository.insertBatch`. One
  * instance per (language, rank) slot in a fire's per-language pool.
  * `expiresAt` is set by the inserter (typically createdAt + 12h —
  * matches the agreed TTL for the fallback pool).
  */
case class NotificationCandidateInsert(
  runId: UUID,
  language: String,
  rank: Int,
  representativeArticleId: Long,
  representativeUrl: String,
  selectionTier: Int,
  score: Int,
  selectionMetadata: Json,
  expiresAt: OffsetDateTime
)

/** Read shape returned by `NotificationCandidateRepository.findPickable`.
  * Carries everything the dispatch path needs to localize titles and
  * persist the audit `top_summary` row, but omits the timestamps the
  * caller does not need.
  */
case class NotificationCandidatePicked(
  id: Long,
  language: String,
  rank: Int,
  representativeArticleId: Long,
  representativeUrl: String,
  selectionTier: Int,
  selectionMetadata: Json
)
