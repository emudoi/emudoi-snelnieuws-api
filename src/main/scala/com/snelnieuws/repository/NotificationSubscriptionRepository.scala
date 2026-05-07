package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.slf4j.LoggerFactory

class NotificationSubscriptionRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[NotificationSubscriptionRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  def upsert(
    deviceId: String,
    apnsToken: String,
    frequency: Int,
    userId: Option[String] = None
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO notification_subscriptions (device_id, apns_token, frequency, user_id)
          VALUES ($deviceId, $apnsToken, $frequency, $userId)
          ON CONFLICT (device_id) DO UPDATE SET
            apns_token = EXCLUDED.apns_token,
            frequency  = EXCLUDED.frequency,
            user_id    = EXCLUDED.user_id,
            updated_at = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert subscription deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
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
