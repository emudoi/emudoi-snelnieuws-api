package com.snelnieuws.service

import com.snelnieuws.model.UserSemanticQueryRow
import com.snelnieuws.repository.UserSemanticQueryRepository
import org.slf4j.LoggerFactory

import java.util.UUID

/** Bridges the UserSemanticQueryRepository with the IngestionApiClient
  * (semantic_search/backend_tasks.txt §6).
  *
  * `setQuery` calls the ingestion-api bridge to embed the query, then
  * persists the (clientId, embedding, queryText, language) tuple. The
  * embedding is also returned to the caller so the app can cache it
  * locally without a follow-up GET.
  *
  * The remaining methods are thin pass-throughs over the repository.
  */
class SemanticQueryService(
  repository: UserSemanticQueryRepository,
  ingestionApiClient: IngestionApiClient
) {

  private val logger = LoggerFactory.getLogger(classOf[SemanticQueryService])

  /** Embed `queryText` via the bridge, persist the row, return the
    * embedding (so the app caches the same vector the server stored).
    * Returns Left on bridge failure — the servlet maps that to 503. */
  def setQuery(
    clientId: UUID,
    userId: Option[String],
    queryText: String,
    language: Option[String]
  ): Either[Throwable, Array[Float]] = {
    val cleaned = queryText.trim
    require(cleaned.nonEmpty, "query text must be non-empty")
    require(cleaned.length <= 200, s"query text exceeds 200 chars: ${cleaned.length}")

    ingestionApiClient.embedQuery(cleaned).flatMap { embedding =>
      repository.upsert(clientId, userId, cleaned, embedding, language) match {
        case Right(_) => Right(embedding)
        case Left(e) =>
          logger.warn(
            s"semantic_query.embed_ok_but_persist_failed clientId=$clientId: ${e.getMessage}"
          )
          // The embedding was computed successfully — surface it to the
          // caller so the client doesn't lose work. The next /v3/embeddings/query
          // GET will return None until persist eventually succeeds.
          Right(embedding)
      }
    }
  }

  def getQuery(clientId: UUID): Either[Throwable, Option[UserSemanticQueryRow]] =
    repository.findByClientId(clientId)

  def getQueryByUser(userId: String): Either[Throwable, Option[UserSemanticQueryRow]] =
    repository.findByUserId(userId)

  def deleteQuery(clientId: UUID): Either[Throwable, Int] =
    repository.delete(clientId)
}
