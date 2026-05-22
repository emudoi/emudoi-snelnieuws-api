package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.util.UUID

/** Mirror of `NotificationSubscriptionRepository` for the Android FCM stack.
  *
  * Kept fully separate from the iOS APNs subscription repo so iOS query
  * shapes and table layout are untouched. The two stacks share nothing
  * except the `articles` table they both read.
  *
  * Unlike iOS, there is no environment column — FCM has a single endpoint
  * for both debug and release builds.
  */
class AndroidNotificationSubscriptionRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[AndroidNotificationSubscriptionRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Upsert the device's subscription row. Mirrors the iOS upsert behavior
    * around `client_id`: COALESCE preserves an existing client_id if a
    * later subscribe call doesn't carry one (e.g. after a logout flow).
    */
  def upsert(
    deviceId: String,
    fcmToken: String,
    frequency: Int,
    userId: Option[String] = None,
    clientId: Option[UUID] = None,
    notificationLanguage: String = "en"
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO android_notification_subscriptions
            (device_id, fcm_token, frequency, user_id, client_id, notification_language)
          VALUES ($deviceId, $fcmToken, $frequency, $userId, $clientId, $notificationLanguage)
          ON CONFLICT (device_id) DO UPDATE SET
            fcm_token             = EXCLUDED.fcm_token,
            frequency             = EXCLUDED.frequency,
            user_id               = EXCLUDED.user_id,
            client_id             = COALESCE(EXCLUDED.client_id, android_notification_subscriptions.client_id),
            notification_language = EXCLUDED.notification_language,
            updated_at            = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert Android subscription deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
    }

  /** Per-language token bucketing for the Android clickbait dispatch
    * flow (notifications_clickbait_tasks.txt §8). When `frequency` is
    * Some(N), only rows whose frequency matches are returned; None
    * broadcasts across all frequencies. Backed by
    * idx_android_notif_sub_lang (V25 index). */
  def findTokensByLanguageGrouped(
    frequency: Option[Int] = None
  ): Either[Throwable, Map[String, List[String]]] =
    try {
      val baseFr = fr"""SELECT notification_language, fcm_token
                       FROM android_notification_subscriptions"""
      val whereFr = frequency.map(f => fr"WHERE frequency = $f").getOrElse(Fragment.empty)
      val rows = (baseFr ++ whereFr)
        .query[(String, String)].to[List]
        .transact(transactor).unsafeRunSync()
      Right(rows.groupBy(_._1).view.mapValues(_.map(_._2)).toMap)
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to group Android tokens by language freq=$frequency: ${e.getMessage}", e
        )
        Left(e)
    }

  def findTokensByFrequency(frequency: Int): Either[Throwable, List[String]] =
    try
      Right(
        sql"""
          SELECT fcm_token FROM android_notification_subscriptions
          WHERE frequency = $frequency
        """.query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to fetch Android tokens for frequency=$frequency: ${e.getMessage}", e)
        Left(e)
    }

  def findAllTokens(): Either[Throwable, List[String]] =
    try
      Right(
        sql"SELECT fcm_token FROM android_notification_subscriptions"
          .query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to fetch all Android subscription tokens: ${e.getMessage}", e)
        Left(e)
    }

  def deleteByFcmToken(token: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM android_notification_subscriptions WHERE fcm_token = $token"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete Android subscription by fcmToken: ${e.getMessage}", e)
        Left(e)
    }

  def deleteByDeviceId(deviceId: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM android_notification_subscriptions WHERE device_id = $deviceId"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete Android subscription deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
    }

  /** Most-recently-updated frequency from any Android device row owned by
    * uid. Symmetric to the iOS repo's lastFrequencyByUserId — used by
    * GET /v2/users/me/last-preference when the caller is Android, so a
    * 2nd-device install inherits the user's last picked frequency.
    * None when the user has no Android subscriptions yet.
    */
  def lastFrequencyByUserId(uid: String): Either[Throwable, Option[Int]] =
    try
      Right(
        sql"""
          SELECT frequency
          FROM android_notification_subscriptions
          WHERE user_id = $uid
          ORDER BY updated_at DESC
          LIMIT 1
        """.query[Int].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to find Android last frequency for uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  /** Delete every Android subscription row linked to uid. The iOS table
    * has an FK to users(id) ON DELETE CASCADE; this table does not (kept
    * self-contained on purpose), so account-deletion has to invoke this
    * explicitly to avoid orphan rows.
    */
  def deleteByUserId(uid: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM android_notification_subscriptions WHERE user_id = $uid"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete Android subscriptions by uid=$uid: ${e.getMessage}", e)
        Left(e)
    }
}
