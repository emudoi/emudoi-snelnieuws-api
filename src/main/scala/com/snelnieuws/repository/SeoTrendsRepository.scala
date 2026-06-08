package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime

/** Raw trend-batch store (`seo_trends`). A geo's batch fully replaces the
  * geo's prior rows in one transaction (replaceForGeo) — there is never more
  * than one live batch per geo. rank = index+1 (1 = hottest). Kept ~48h by
  * SeoTrendsCleanupScheduler. */
class SeoTrendsRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[SeoTrendsRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Replace a geo's stored batch: delete the geo's prior rows and insert the
    * new `terms` (rank = index+1) in a single transaction. `source` defaults
    * to "google_trends" at the call site. No-op insert if `terms` is empty,
    * but the delete still runs so a stale batch is cleared. Returns the number
    * of rows inserted. */
  def replaceForGeo(
    geo: String,
    collectedAt: OffsetDateTime,
    source: String,
    terms: List[String]
  ): Either[Throwable, Int] =
    try {
      val deleteOld = sql"DELETE FROM seo_trends WHERE geo = $geo".update.run
      val insertNew: doobie.ConnectionIO[Int] =
        if (terms.isEmpty) doobie.free.connection.pure(0)
        else {
          val rows = terms.zipWithIndex.map { case (term, idx) => (term, idx + 1) }
          val sqlText =
            "INSERT INTO seo_trends (term, geo, rank, source, collected_at) VALUES (?, ?, ?, ?, ?)"
          Update[(String, String, Int, String, OffsetDateTime)](sqlText)
            .updateMany(rows.map { case (term, rank) => (term, geo, rank, source, collectedAt) })
        }
      val program = for {
        _        <- deleteOld
        inserted <- insertNew
      } yield inserted
      Right(program.transact(transactor).unsafeRunSync())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to replace seo_trends for geo=$geo: ${e.getMessage}", e)
        Left(e)
    }

  /** The latest stored batch for a geo as `(term, rank)` ordered by rank asc.
    * There is only ever one batch per geo (replaceForGeo), so no collected_at
    * filter is needed. Empty list when the geo has no rows. */
  def latestTermsByGeo(geo: String): Either[Throwable, List[(String, Int)]] =
    try
      Right(
        sql"""
          SELECT term, rank
          FROM seo_trends
          WHERE geo = $geo
          ORDER BY rank ASC
        """.query[(String, Int)].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load latest terms for geo=$geo: ${e.getMessage}", e)
        Left(e)
    }

  /** Delete batches older than `cutoff`. Returns deleted row count. Driven by
    * SeoTrendsCleanupScheduler each tick. */
  def deleteCollectedBefore(cutoff: OffsetDateTime): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM seo_trends WHERE collected_at < $cutoff".update.run
          .transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete seo_trends before $cutoff: ${e.getMessage}", e)
        Left(e)
    }
}
