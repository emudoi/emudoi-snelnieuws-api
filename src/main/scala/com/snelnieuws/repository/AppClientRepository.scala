package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.parser.parse
import org.slf4j.LoggerFactory

import java.util.UUID

class AppClientRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[AppClientRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Idempotent upsert. iOS calls register on every cold start when its
    * Keychain UUID hasn't been confirmed-as-registered yet, so duplicates
    * are expected — we just refresh os_version + last_seen_at. We
    * deliberately do NOT touch revoked_at here: a re-register from a
    * client that an operator already flagged would otherwise silently
    * un-revoke itself, defeating the only kill-switch we have. Once
    * revoked, only manual SQL un-revokes. */
  def upsertOnRegister(
    clientId: UUID,
    bundleId: String,
    osVersion: Option[String],
    platform: String
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO app_clients (client_id, bundle_id, os_version, platform)
          VALUES ($clientId, $bundleId, $osVersion, $platform)
          ON CONFLICT (client_id) DO UPDATE SET
            bundle_id    = EXCLUDED.bundle_id,
            os_version   = EXCLUDED.os_version,
            platform     = EXCLUDED.platform,
            last_seen_at = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert app_client clientId=$clientId: ${e.getMessage}", e)
        Left(e)
    }

  /** Returns true iff the client_id exists and has not been revoked.
    * Used by the v2 before() filter on every request — keep cheap. */
  def isActive(clientId: UUID): Either[Throwable, Boolean] =
    try
      Right(
        sql"""
          SELECT 1 FROM app_clients
          WHERE client_id = $clientId AND revoked_at IS NULL
          LIMIT 1
        """.query[Int].option.transact(transactor).unsafeRunSync().isDefined
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to look up app_client clientId=$clientId: ${e.getMessage}", e)
        Left(e)
    }

  /** Bumps last_seen_at. Called from the v2 filter on the request thread
    * when we want to track liveness. Best-effort: a failure here mustn't
    * fail the request. */
  def markSeen(clientId: UUID): Either[Throwable, Int] =
    try
      Right(
        sql"""
          UPDATE app_clients SET last_seen_at = NOW()
          WHERE client_id = $clientId AND revoked_at IS NULL
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to bump last_seen_at clientId=$clientId: ${e.getMessage}", e)
        Left(e)
    }

  // ────────────────────────── Personalised feed ──────────────────────────
  //
  // last_served_ids is a JSONB array of article ids the client has been
  // served. The personalised-feed read path (ArticleService) loads it,
  // filters the pool, and appends the freshly-served ids back. The column
  // defaults to '[]' (V18 migration), so a freshly-registered client
  // returns Set.empty here, and an unknown client_id also returns
  // Set.empty (the read tolerates a missing row).

  /** Read the stored served-id history. Returns an empty set when the
    * client is unknown, the column is null, or parsing fails — the filter
    * is best-effort, never a hard dependency. */
  def readServedIds(clientId: UUID): Either[Throwable, Set[Long]] =
    try
      Right(
        sql"SELECT last_served_ids::text FROM app_clients WHERE client_id = $clientId"
          .query[String]
          .option
          .transact(transactor)
          .unsafeRunSync()
          .flatMap(parse(_).toOption)
          .flatMap(_.asArray)
          .map(_.flatMap(_.asNumber).flatMap(_.toLong).toSet)
          .getOrElse(Set.empty[Long])
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to read last_served_ids for $clientId: ${e.getMessage}", e)
        Left(e)
    }

  /** Append `newIds` to the served-id history, dedupe, trim to the most
    * recent `capAt` entries. Wrapped in a single transaction with
    * SELECT ... FOR UPDATE so two parallel requests from the same client
    * cannot lose updates. The trim keeps the *tail* (most recent), so
    * older ids fall off first. */
  def appendServedIds(
    clientId: UUID,
    newIds: List[Long],
    capAt: Int = 1000
  ): Either[Throwable, Int] =
    if (newIds.isEmpty) Right(0)
    else
      try {
        val program = for {
          existingJsonOpt <- sql"SELECT last_served_ids::text FROM app_clients WHERE client_id = $clientId FOR UPDATE"
            .query[String].option
          existing = existingJsonOpt
            .flatMap(parse(_).toOption)
            .flatMap(_.asArray)
            .map(_.flatMap(_.asNumber).flatMap(_.toLong).toList)
            .getOrElse(List.empty[Long])
          merged  = (existing ++ newIds).distinct
          trimmed = merged.takeRight(capAt)
          json    = trimmed.mkString("[", ",", "]")
          rows <- sql"UPDATE app_clients SET last_served_ids = $json::jsonb WHERE client_id = $clientId"
            .update.run
        } yield rows
        Right(program.transact(transactor).unsafeRunSync())
      } catch {
        case e: Exception =>
          logger.error(s"Failed to append served ids for $clientId: ${e.getMessage}", e)
          Left(e)
      }

  /** Replace the served-id history wholesale. Used by the reset-on-exhaust
    * path so the next call starts a fresh rotation cycle. */
  def setServedIds(clientId: UUID, ids: List[Long]): Either[Throwable, Int] =
    try {
      val json = ids.mkString("[", ",", "]")
      Right(
        sql"UPDATE app_clients SET last_served_ids = $json::jsonb WHERE client_id = $clientId"
          .update.run.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to set served ids for $clientId: ${e.getMessage}", e)
        Left(e)
    }

  // ─────────────────────────── Video reel feed ───────────────────────────
  //
  // last_served_video_ids (V42) is the video-only twin of last_served_ids —
  // a separate JSONB column so video ids never collide with article ids.
  // VideoFeedService drives the same read → filter → append → reset-on-
  // exhaust rotation the article feed uses.

  /** Read the stored served-video-id history. Tolerant of unknown client /
    * null column / parse failure (returns Set.empty), like readServedIds. */
  def readServedVideoIds(clientId: UUID): Either[Throwable, Set[Long]] =
    try
      Right(
        sql"SELECT last_served_video_ids::text FROM app_clients WHERE client_id = $clientId"
          .query[String]
          .option
          .transact(transactor)
          .unsafeRunSync()
          .flatMap(parse(_).toOption)
          .flatMap(_.asArray)
          .map(_.flatMap(_.asNumber).flatMap(_.toLong).toSet)
          .getOrElse(Set.empty[Long])
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to read last_served_video_ids for $clientId: ${e.getMessage}", e)
        Left(e)
    }

  /** Append `newIds` to the served-video history, dedupe, trim to the most
    * recent `capAt`. SELECT ... FOR UPDATE so parallel requests don't race. */
  def appendServedVideoIds(
    clientId: UUID,
    newIds: List[Long],
    capAt: Int = 1000
  ): Either[Throwable, Int] =
    if (newIds.isEmpty) Right(0)
    else
      try {
        val program = for {
          existingJsonOpt <- sql"SELECT last_served_video_ids::text FROM app_clients WHERE client_id = $clientId FOR UPDATE"
            .query[String].option
          existing = existingJsonOpt
            .flatMap(parse(_).toOption)
            .flatMap(_.asArray)
            .map(_.flatMap(_.asNumber).flatMap(_.toLong).toList)
            .getOrElse(List.empty[Long])
          merged  = (existing ++ newIds).distinct
          trimmed = merged.takeRight(capAt)
          json    = trimmed.mkString("[", ",", "]")
          rows <- sql"UPDATE app_clients SET last_served_video_ids = $json::jsonb WHERE client_id = $clientId"
            .update.run
        } yield rows
        Right(program.transact(transactor).unsafeRunSync())
      } catch {
        case e: Exception =>
          logger.error(s"Failed to append served video ids for $clientId: ${e.getMessage}", e)
          Left(e)
      }

  /** Replace the served-video history wholesale (reset-on-exhaust). */
  def setServedVideoIds(clientId: UUID, ids: List[Long]): Either[Throwable, Int] =
    try {
      val json = ids.mkString("[", ",", "]")
      Right(
        sql"UPDATE app_clients SET last_served_video_ids = $json::jsonb WHERE client_id = $clientId"
          .update.run.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to set served video ids for $clientId: ${e.getMessage}", e)
        Left(e)
    }
}
