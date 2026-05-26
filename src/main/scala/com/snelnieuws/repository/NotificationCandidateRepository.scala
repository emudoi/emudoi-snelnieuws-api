package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.snelnieuws.model.{NotificationCandidateInsert, NotificationCandidatePicked}
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.parser.{parse => circeParse}
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime

/** Persistence for `notification_candidates` (V29) — the per-language
  * ranked fallback pool for push notifications.
  *
  * Lifecycle:
  *   - `insertBatch` is called by NotificationService /
  *     AndroidNotificationService each dispatch tick (when the fallback
  *     pool feature flag is on). It performs lazy cleanup of expired
  *     rows in the SAME transaction as the insert, which keeps the
  *     table from growing without a scheduled job (Option C).
  *   - `findPickable` returns the best (lowest rank, freshest batch)
  *     unconsumed, non-expired candidate for a given language.
  *   - `markConsumed` is called immediately before dispatch sends so
  *     two concurrent ticks can't dispatch the same row (UPDATE …
  *     WHERE consumed_at IS NULL ensures atomicity).
  *   - `findConsumedArticleIdsSince` powers dedup: don't re-add an
  *     article id we already sent in the last 24 h to a new pool.
  *
  * JSONB round-trips through `String` with `::jsonb` casts — same
  * pattern as TopSummaryRepository / AppClientRepository, since
  * doobie-postgres-circe isn't on the classpath in this build.
  */
class NotificationCandidateRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[NotificationCandidateRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Insert a per-language batch of ranked candidates. Performs a lazy
    * cleanup pass deleting rows whose `expires_at < NOW()` BEFORE
    * inserting the new batch — same transaction so the new rows can't
    * be deleted by a concurrent insert.
    *
    * `ON CONFLICT (run_id, language, rank) DO NOTHING` guards against
    * an accidental retry inserting duplicates; the unique constraint
    * is defined in V29.
    *
    * Returns the count of rows inserted (excluding any swallowed by
    * the ON CONFLICT branch). Cleanup row count is logged but not
    * returned — callers don't care.
    */
  def insertBatch(
    candidates: List[NotificationCandidateInsert]
  ): Either[Throwable, Int] =
    if (candidates.isEmpty) Right(0)
    else
      try {
        val cleanup =
          sql"DELETE FROM notification_candidates WHERE expires_at < NOW()".update.run

        def insertOne(c: NotificationCandidateInsert): doobie.ConnectionIO[Int] = {
          val metaStr = c.selectionMetadata.noSpaces
          sql"""
            INSERT INTO notification_candidates
              (run_id, language, rank, representative_article_id,
               representative_url, selection_tier, score,
               selection_metadata, expires_at)
            VALUES
              (${c.runId}, ${c.language}, ${c.rank}, ${c.representativeArticleId},
               ${c.representativeUrl}, ${c.selectionTier}, ${c.score},
               $metaStr::jsonb, ${c.expiresAt})
            ON CONFLICT (run_id, language, rank) DO NOTHING
          """.update.run
        }

        val program: doobie.ConnectionIO[(Int, Int)] = for {
          deleted  <- cleanup
          inserted <- candidates.traverse(insertOne).map(_.sum)
        } yield (deleted, inserted)

        val (deleted, inserted) = program.transact(transactor).unsafeRunSync()
        if (deleted > 0)
          logger.info(
            s"notification_candidates: lazy-cleanup deleted=$deleted expired rows before insert"
          )
        Right(inserted)
      } catch {
        case e: Exception =>
          logger.error(
            s"Failed to insert notification_candidates batch size=${candidates.size}: ${e.getMessage}",
            e
          )
          Left(e)
      }

  /** Highest-ranked (rank ASC) unconsumed, non-expired candidate for
    * `language`. Ties on rank break on `created_at DESC` so a fresh
    * batch's rank-2 is preferred over an older batch's rank-2.
    * Returns None when the pool is empty for that language.
    */
  def findPickable(language: String): Either[Throwable, Option[NotificationCandidatePicked]] =
    try
      Right(
        sql"""
          SELECT id, language, rank, representative_article_id, representative_url,
                 selection_tier, selection_metadata::text
          FROM notification_candidates
          WHERE language = $language
            AND consumed_at IS NULL
            AND expires_at > NOW()
          ORDER BY rank ASC, created_at DESC
          LIMIT 1
        """
          .query[(Long, String, Short, Long, String, Short, Option[String])]
          .map { case (id, lang, rank, repId, repUrl, tier, metaOpt) =>
            val metaJson = metaOpt.flatMap(s => circeParse(s).toOption).getOrElse(Json.obj())
            NotificationCandidatePicked(
              id                      = id,
              language                = lang,
              rank                    = rank.toInt,
              representativeArticleId = repId,
              representativeUrl       = repUrl,
              selectionTier           = tier.toInt,
              selectionMetadata       = metaJson
            )
          }
          .option
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to find pickable notification_candidate lang=$language: ${e.getMessage}", e)
        Left(e)
    }

  /** Atomically mark `id` consumed. Returns 1 on success, 0 if the row
    * was already consumed (another tick raced us) or no longer exists.
    * Callers MUST check the returned count before sending — a 0 means
    * "someone else already took this candidate".
    */
  def markConsumed(id: Long, at: OffsetDateTime): Either[Throwable, Int] =
    try
      Right(
        sql"""
          UPDATE notification_candidates
          SET consumed_at = $at
          WHERE id = $id AND consumed_at IS NULL
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to mark notification_candidate id=$id consumed: ${e.getMessage}", e)
        Left(e)
    }

  /** Set of representative_article_ids that have been consumed since
    * `since`. Used at candidate-generation time to drop article ids
    * we already sent in the dedup window (24 h) before persisting a
    * new pool.
    */
  def findConsumedArticleIdsSince(since: OffsetDateTime): Either[Throwable, Set[Long]] =
    try
      Right(
        sql"""
          SELECT DISTINCT representative_article_id
          FROM notification_candidates
          WHERE consumed_at IS NOT NULL
            AND consumed_at >= $since
        """.query[Long].to[List].transact(transactor).unsafeRunSync().toSet
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load consumed article ids since=$since: ${e.getMessage}", e)
        Left(e)
    }
}
