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

  def upsert(deviceId: String, apnsToken: String, frequency: Int): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO notification_subscriptions (device_id, apns_token, frequency)
          VALUES ($deviceId, $apnsToken, $frequency)
          ON CONFLICT (device_id) DO UPDATE SET
            apns_token = EXCLUDED.apns_token,
            frequency  = EXCLUDED.frequency,
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
