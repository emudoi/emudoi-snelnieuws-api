package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.UserSemanticQueryRow
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.util.UUID

/** Persistence for `user_semantic_queries` — one saved semantic-search
  * query per app install (client_id), optionally bound to a user_id
  * for cross-device sync. See semantic_search/backend_tasks.txt §6.
  *
  * `embedding` REAL[] round-trips via `Array[Float]`. Doobie's
  * postgres module already provides `Get/Put[Array[Float]]` via the
  * `floatArray` instance imported from `doobie.postgres.implicits._`
  * — no extra Meta required.
  */
class UserSemanticQueryRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[UserSemanticQueryRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Insert-or-update the per-client row. `created_at` stays put on
    * conflict; `updated_at` is bumped via NOW(). */
  def upsert(
    clientId: UUID,
    userId: Option[String],
    queryText: String,
    embedding: Array[Float],
    language: Option[String]
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO user_semantic_queries
            (client_id, user_id, query_text, embedding, language)
          VALUES
            ($clientId, $userId, $queryText, $embedding, $language)
          ON CONFLICT (client_id) DO UPDATE SET
            user_id    = EXCLUDED.user_id,
            query_text = EXCLUDED.query_text,
            embedding  = EXCLUDED.embedding,
            language   = EXCLUDED.language,
            updated_at = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to upsert user_semantic_queries clientId=$clientId: ${e.getMessage}", e
        )
        Left(e)
    }

  def findByClientId(clientId: UUID): Either[Throwable, Option[UserSemanticQueryRow]] =
    try
      Right(
        sql"""
          SELECT client_id, user_id, query_text, embedding, language,
                 created_at, updated_at
          FROM user_semantic_queries
          WHERE client_id = $clientId
        """.query[UserSemanticQueryRow].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to load user_semantic_queries clientId=$clientId: ${e.getMessage}", e
        )
        Left(e)
    }

  /** Cross-device sync. If multiple device rows share a user_id, return
    * the most recently updated one. */
  def findByUserId(userId: String): Either[Throwable, Option[UserSemanticQueryRow]] =
    try
      Right(
        sql"""
          SELECT client_id, user_id, query_text, embedding, language,
                 created_at, updated_at
          FROM user_semantic_queries
          WHERE user_id = $userId
          ORDER BY updated_at DESC
          LIMIT 1
        """.query[UserSemanticQueryRow].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to load user_semantic_queries userId=$userId: ${e.getMessage}", e
        )
        Left(e)
    }

  def delete(clientId: UUID): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM user_semantic_queries WHERE client_id = $clientId"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to delete user_semantic_queries clientId=$clientId: ${e.getMessage}", e
        )
        Left(e)
    }
}
