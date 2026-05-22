package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.{TopStoryPayload, TopSummaryRow}
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.parser.{parse => circeParse}
import io.circe.syntax._
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime

/** Persistence for `top_summaries`, the per-language clickbait
  * dispatch table (notifications_clickbait_tasks.txt §5 + §8). Each
  * row is dispatched at most once (idempotent on dispatched_at IS NULL).
  *
  * The JSONB columns round-trip through String (using `::jsonb` casts
  * in SQL) — same pattern as AppClientRepository.last_served_ids, since
  * doobie-postgres-circe isn't on the classpath in this build.
  */
class TopSummaryRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[TopSummaryRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  def insert(payload: TopStoryPayload): Either[Throwable, Long] =
    try {
      val topNewsStr      = payload.topNews.noSpaces
      val notifsStr       = Json
        .fromFields(payload.notificationMessages.iterator.map { case (k, v) =>
          k -> Json.fromString(v)
        }.toSeq)
        .noSpaces
      val metadataStr     = payload.selectionMetadata.noSpaces
      Right(
        sql"""
          INSERT INTO top_summaries
            (top_news, notification_messages, selection_tier, selection_metadata)
          VALUES
            ($topNewsStr::jsonb, $notifsStr::jsonb, ${payload.selectionTier},
             $metadataStr::jsonb)
          RETURNING id
        """.query[Long].unique.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to insert top_summary (articleId=${payload.representativeArticleId}): ${e.getMessage}",
          e
        )
        Left(e)
    }

  def findLatestUndispatched(): Either[Throwable, Option[TopSummaryRow]] =
    try
      Right(
        sql"""
          SELECT id, created_at, top_news::text, notification_messages::text,
                 selection_tier, selection_metadata::text
          FROM top_summaries
          WHERE dispatched_at IS NULL
          ORDER BY created_at DESC
          LIMIT 1
        """
          .query[(Long, OffsetDateTime, String, String, Int, Option[String])]
          .map { case (id, createdAt, topNewsStr, notifsStr, tier, metaOpt) =>
            val topNewsJson  = circeParse(topNewsStr).getOrElse(Json.obj())
            val notifsJson   = circeParse(notifsStr).getOrElse(Json.obj())
            val notifMap     = notifsJson.asObject
              .map(_.toMap.flatMap { case (k, v) => v.asString.map(k -> _) })
              .getOrElse(Map.empty)
            val metadataJson = metaOpt.flatMap(s => circeParse(s).toOption)
            TopSummaryRow(
              id                   = id,
              createdAt            = createdAt,
              topNews              = topNewsJson,
              notificationMessages = notifMap,
              selectionTier        = tier,
              selectionMetadata    = metadataJson
            )
          }
          .option
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to find latest undispatched top_summary: ${e.getMessage}", e)
        Left(e)
    }

  def markDispatched(id: Long, at: OffsetDateTime): Either[Throwable, Int] =
    try
      Right(
        sql"UPDATE top_summaries SET dispatched_at = $at WHERE id = $id"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to mark top_summary id=$id dispatched: ${e.getMessage}", e)
        Left(e)
    }
}
