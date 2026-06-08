package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.service.ArticleService
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime

/** Per-article trending boost store (`article_trending_scores`). Written by
  * TrendingScoreService after each batch (one geo's rows fully replaced per
  * write), read on the v3 feed path by ArticleService.blendedV3Pool. Rows are
  * keyed by (source, article_id, geo); `source` is 'articles' | 'eulang'.
  * loadForGeo collapses (source, article_id) into the served_ids key scheme
  * used by the feed — eulang ids offset by ArticleService.EulangIdOffset. */
class ArticleTrendingScoresRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[ArticleTrendingScoresRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Replace a geo's score rows: delete the geo's prior rows and insert the
    * new ones in a single transaction. `rows` are (source, articleId, score)
    * with source = 'articles' | 'eulang'. No-op insert when `rows` is empty,
    * but the delete still runs so stale scores are cleared. Returns inserted
    * row count. */
  def upsertForGeo(
    geo: String,
    rows: List[(String, Long, Double)],
    expiresAt: OffsetDateTime
  ): Either[Throwable, Int] =
    try {
      val deleteOld = sql"DELETE FROM article_trending_scores WHERE geo = $geo".update.run
      val insertNew: doobie.ConnectionIO[Int] =
        if (rows.isEmpty) doobie.free.connection.pure(0)
        else {
          val sqlText =
            "INSERT INTO article_trending_scores (source, article_id, geo, score, expires_at) VALUES (?, ?, ?, ?, ?)"
          Update[(String, Long, String, Float, OffsetDateTime)](sqlText)
            .updateMany(rows.map { case (source, articleId, score) =>
              (source, articleId, geo, score.toFloat, expiresAt)
            })
        }
      val program = for {
        _        <- deleteOld
        inserted <- insertNew
      } yield inserted
      Right(program.transact(transactor).unsafeRunSync())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to upsert article_trending_scores for geo=$geo: ${e.getMessage}", e)
        Left(e)
    }

  /** Live (non-expired) scores for a geo, keyed by the served_ids scheme so the
    * feed can look up by `key(s)` directly: eulang rows keyed as
    * article_id + EulangIdOffset, articles by raw id. Empty map when no live
    * rows — which the feed treats as pure recency. */
  def loadForGeo(geo: String): Either[Throwable, Map[Long, Double]] =
    try {
      val rows = sql"""
        SELECT source, article_id, score
        FROM article_trending_scores
        WHERE geo = $geo
          AND expires_at > now()
      """.query[(String, Long, Float)].to[List].transact(transactor).unsafeRunSync()
      val map = rows.map { case (source, articleId, score) =>
        val key = if (source == "eulang") articleId + ArticleService.EulangIdOffset else articleId
        key -> score.toDouble
      }.toMap
      Right(map)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load article_trending_scores for geo=$geo: ${e.getMessage}", e)
        Left(e)
    }

  /** Delete rows past their `expires_at`. Returns deleted row count. Driven by
    * SeoTrendsCleanupScheduler each tick. */
  def deleteExpired(): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM article_trending_scores WHERE expires_at < now()".update.run
          .transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete expired article_trending_scores: ${e.getMessage}", e)
        Left(e)
    }
}
