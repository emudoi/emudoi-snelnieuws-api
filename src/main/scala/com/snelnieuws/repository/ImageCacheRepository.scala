package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.{ImageCacheRow, ImageCacheStatus}
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime

/** Persistence for the URL → on-disk-path mapping. The relative_path is a
  * pure function of source_url (sha256 + extension), so the table mainly
  * tracks state — downloaded / failed, attempts, last_attempt_at — and
  * gives the cleanup scheduler a fast index on downloaded_at instead of
  * having to walk the NFS export.
  */
class ImageCacheRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[ImageCacheRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  def findByUrl(sourceUrl: String): Either[Throwable, Option[ImageCacheRow]] =
    try
      Right(
        sql"""
          SELECT source_url, relative_path, content_type, size_bytes,
                 status, downloaded_at, last_attempt_at, attempts,
                 last_failure_reason, last_failure_status_code
          FROM image_cache
          WHERE source_url = $sourceUrl
        """.query[ImageCacheRow].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to look up image_cache url=$sourceUrl: ${e.getMessage}", e)
        Left(e)
    }

  def findByRelativePath(relativePath: String): Either[Throwable, Option[ImageCacheRow]] =
    try
      Right(
        sql"""
          SELECT source_url, relative_path, content_type, size_bytes,
                 status, downloaded_at, last_attempt_at, attempts,
                 last_failure_reason, last_failure_status_code
          FROM image_cache
          WHERE relative_path = $relativePath
        """.query[ImageCacheRow].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to look up image_cache relativePath=$relativePath: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** Upsert success state. A retry that was previously 'failed' is promoted
    * to 'downloaded' here — attempts is bumped so we still have a tally.
    * Resets last_failure_* on success so dashboards don't keep showing
    * stale reasons for now-healthy URLs. */
  def upsertDownloaded(
    sourceUrl: String,
    relativePath: String,
    contentType: Option[String],
    sizeBytes: Long
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO image_cache
            (source_url, relative_path, content_type, size_bytes,
             status, downloaded_at, last_attempt_at, attempts,
             last_failure_reason, last_failure_status_code)
          VALUES
            ($sourceUrl, $relativePath, $contentType, $sizeBytes,
             ${ImageCacheStatus.Downloaded}, NOW(), NOW(), 1,
             NULL, NULL)
          ON CONFLICT (source_url) DO UPDATE SET
            relative_path             = EXCLUDED.relative_path,
            content_type              = EXCLUDED.content_type,
            size_bytes                = EXCLUDED.size_bytes,
            status                    = ${ImageCacheStatus.Downloaded},
            downloaded_at             = NOW(),
            last_attempt_at           = NOW(),
            attempts                  = image_cache.attempts + 1,
            last_failure_reason       = NULL,
            last_failure_status_code  = NULL
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to upsert image_cache url=$sourceUrl path=$relativePath: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** Upsert failure state. relative_path is still recorded — content
    * addressing means we know it independently of whether the bytes
    * landed, and we want it on the row in case a future retry wins.
    * reason / statusCode are surfaced from the in-process classifier. */
  def upsertFailed(
    sourceUrl: String,
    relativePath: String,
    reason: Option[String] = None,
    statusCode: Option[Int] = None
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO image_cache
            (source_url, relative_path, status, last_attempt_at, attempts,
             last_failure_reason, last_failure_status_code)
          VALUES
            ($sourceUrl, $relativePath, ${ImageCacheStatus.Failed}, NOW(), 1,
             $reason, $statusCode)
          ON CONFLICT (source_url) DO UPDATE SET
            status                   = ${ImageCacheStatus.Failed},
            last_attempt_at          = NOW(),
            attempts                 = image_cache.attempts + 1,
            last_failure_reason      = $reason,
            last_failure_status_code = $statusCode
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to mark image_cache failed url=$sourceUrl: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** Bump attempts + last_attempt_at without changing status or reason —
    * used by the slow-path consumer when its single retry attempt fails
    * so we still tally the work without overwriting the original
    * fast-path failure classification. */
  def bumpAttempt(sourceUrl: String): Either[Throwable, Int] =
    try
      Right(
        sql"""
          UPDATE image_cache
          SET attempts        = attempts + 1,
              last_attempt_at = NOW()
          WHERE source_url = $sourceUrl
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to bump image_cache attempt url=$sourceUrl: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** Rows whose downloaded_at is older than `cutoff`. Returned as
    * (source_url, relative_path) pairs so the caller can `rm` the file
    * on NFS before deleting the row. Rows with NULL downloaded_at
    * (still 'failed', never succeeded) are skipped — cleanup of those
    * is driven by attempts cap, not retention. */
  def findDownloadedBefore(
    cutoff: OffsetDateTime,
    limit: Int
  ): Either[Throwable, List[(String, String)]] =
    try
      Right(
        sql"""
          SELECT source_url, relative_path
          FROM image_cache
          WHERE status = ${ImageCacheStatus.Downloaded}
            AND downloaded_at < $cutoff
          ORDER BY downloaded_at ASC
          LIMIT $limit
        """.query[(String, String)].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to query expired image_cache rows before $cutoff: ${e.getMessage}",
          e
        )
        Left(e)
    }

  def deleteByUrl(sourceUrl: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM image_cache WHERE source_url = $sourceUrl".update.run
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete image_cache url=$sourceUrl: ${e.getMessage}", e)
        Left(e)
    }
}
