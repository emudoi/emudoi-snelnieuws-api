package com.snelnieuws.model

import java.time.OffsetDateTime
import java.util.UUID

/** Server-side row for a saved semantic-search query — see
  * semantic_search/backend_tasks.txt §5+§6.
  *
  * `embedding` is the 1024-dim multilingual-e5-large vector. It's
  * persisted alongside the source text so the app can verify the
  * server has the same vector it last received, and so a future
  * personalized-notifications hook can iterate stored queries
  * without re-embedding. */
case class UserSemanticQueryRow(
  clientId:  UUID,
  userId:    Option[String],
  queryText: String,
  embedding: Array[Float],
  language:  Option[String],
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

/** Parsed body of POST /v3/embeddings/query. The servlet validates
  * `text` (non-empty, ≤ 200 chars) BEFORE calling the service. */
case class EmbedQueryRequest(
  text: String,
  language: Option[String]
)
