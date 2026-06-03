package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.free.connection
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.util.UUID

class NotificationSubscriptionRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[NotificationSubscriptionRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Upsert the device's subscription row. When `userId` is provided, this
    * runs in one transaction with a self-healing `INSERT INTO users (id) ON
    * CONFLICT DO NOTHING` so a missing users row (e.g. POST /users failed
    * silently at signup time) never causes an FK violation here. The email
    * column was relaxed to NULL in V9 specifically to allow this — the next
    * /users upsert from iOS fills in the email. */
  def upsert(
    deviceId: String,
    apnsToken: String,
    frequency: Int,
    environment: String,
    userId: Option[String] = None,
    clientId: Option[UUID] = None,
    // notification_language defaults to "en" matching the V25 column
    // DEFAULT. Existing callers (and older app builds that haven't
    // shipped the new SubscribeRequest field) get 'en' implicitly.
    notificationLanguage: String = "en"
  ): Either[Throwable, Int] = {
    val ensureUser: ConnectionIO[Int] = userId match {
      case Some(uid) =>
        sql"INSERT INTO users (id) VALUES ($uid) ON CONFLICT (id) DO NOTHING"
          .update.run
      case None =>
        connection.pure(0)
    }

    // EXCLUDED.client_id intentionally NOT used on conflict: the column
    // is set when a v2-aware client first creates the row, and we don't
    // want a later v1 subscribe (clientId=None) to NULL it back out.
    // COALESCE keeps any existing client_id if the new one is NULL.
    val upsertSubscription: ConnectionIO[Int] =
      sql"""
        INSERT INTO notification_subscriptions
          (device_id, apns_token, frequency, apns_environment, user_id, client_id,
           notification_language)
        VALUES ($deviceId, $apnsToken, $frequency, $environment, $userId, $clientId,
                $notificationLanguage)
        ON CONFLICT (device_id) DO UPDATE SET
          apns_token            = EXCLUDED.apns_token,
          frequency             = EXCLUDED.frequency,
          apns_environment      = EXCLUDED.apns_environment,
          user_id               = EXCLUDED.user_id,
          client_id             = COALESCE(EXCLUDED.client_id, notification_subscriptions.client_id),
          notification_language = EXCLUDED.notification_language,
          updated_at            = NOW()
      """.update.run

    try
      Right(
        ensureUser.flatMap(_ => upsertSubscription)
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert subscription deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
    }
  }

  def findTokensByFrequency(frequency: Int): Either[Throwable, List[String]] =
    try
      Right(
        sql"""
          SELECT apns_token FROM notification_subscriptions
          WHERE frequency = $frequency
        """.query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to fetch tokens for frequency=$frequency: ${e.getMessage}", e)
        Left(e)
    }

  def findTokensByFrequencyAndEnvironment(
    frequency: Int,
    environment: String
  ): Either[Throwable, List[String]] =
    try
      Right(
        sql"""
          SELECT apns_token FROM notification_subscriptions
          WHERE frequency = $frequency AND apns_environment = $environment
        """.query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to fetch tokens for frequency=$frequency env=$environment: ${e.getMessage}",
          e
        )
        Left(e)
    }

  def findAllTokens(): Either[Throwable, List[String]] =
    try
      Right(
        sql"""
          SELECT apns_token FROM notification_subscriptions
        """.query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to fetch all subscription tokens: ${e.getMessage}", e)
        Left(e)
    }

  /** Per-language token bucketing for the clickbait dispatch flow
    * (notifications_clickbait_tasks.txt §8). `frequency` is the slot
    * threshold: Some(N) returns every row whose picked frequency is
    * >= N, so one slot reaches everyone at or above its threshold
    * (07:00→4, 17:00→3, 19:00→2, 21:00→1). None broadcasts across all
    * frequencies in the environment. Backed by idx_notif_sub_lang_env
    * (V25 partial index on language+environment). */
  def findTokensByLanguageGrouped(
    environment: String,
    frequency: Option[Int] = None
  ): Either[Throwable, Map[String, List[String]]] =
    try {
      val baseFr  = fr"""SELECT notification_language, apns_token
                        FROM notification_subscriptions
                        WHERE apns_environment = $environment"""
      val freqFr  = frequency.map(f => fr"AND frequency >= $f").getOrElse(Fragment.empty)
      val rows = (baseFr ++ freqFr)
        .query[(String, String)].to[List]
        .transact(transactor).unsafeRunSync()
      Right(rows.groupBy(_._1).view.mapValues(_.map(_._2)).toMap)
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to group tokens by language env=$environment freq=$frequency: ${e.getMessage}", e
        )
        Left(e)
    }

  def findAllTokensByEnvironment(environment: String): Either[Throwable, List[String]] =
    try
      Right(
        sql"""
          SELECT apns_token FROM notification_subscriptions
          WHERE apns_environment = $environment
        """.query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to fetch all tokens for env=$environment: ${e.getMessage}",
          e
        )
        Left(e)
    }

  def deleteByApnsToken(token: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM notification_subscriptions WHERE apns_token = $token".update.run
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete subscription by apnsToken: ${e.getMessage}", e)
        Left(e)
    }

  /** Returns Some(uid) if the device row is linked to a user, None if it
    * exists but is anonymous, or Right(None) at the outer layer if no row
    * exists (caller can disambiguate). Used by tests + can power admin views. */
  def findUserIdByDeviceId(deviceId: String): Either[Throwable, Option[Option[String]]] =
    try
      Right(
        sql"""
          SELECT user_id FROM notification_subscriptions WHERE device_id = $deviceId
        """.query[Option[String]].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to find user_id for deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
    }

  /** Most-recently-updated frequency from any device row owned by uid.
    * iOS calls this on login on a fresh device so the user doesn't have
    * to redo onboarding. None when the user has no rows yet. */
  def lastFrequencyByUserId(uid: String): Either[Throwable, Option[Int]] =
    try
      Right(
        sql"""
          SELECT frequency
          FROM notification_subscriptions
          WHERE user_id = $uid
          ORDER BY updated_at DESC
          LIMIT 1
        """.query[Int].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to find last frequency for uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  def deleteByDeviceId(deviceId: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM notification_subscriptions WHERE device_id = $deviceId".update.run
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete subscription by deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
    }
}
