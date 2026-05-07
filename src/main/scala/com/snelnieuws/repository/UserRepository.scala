package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.User
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.slf4j.LoggerFactory

class UserRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[UserRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Idempotent upsert keyed on Firebase uid. Updates email + updated_at on
    * conflict so a user changing their email in Firebase is reflected here
    * on the next signup/login backfill call. */
  def upsert(uid: String, email: String): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO users (id, email)
          VALUES ($uid, $email)
          ON CONFLICT (id) DO UPDATE SET
            email      = EXCLUDED.email,
            updated_at = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert user uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  def findById(uid: String): Either[Throwable, Option[User]] =
    try
      Right(
        sql"""
          SELECT id, email, created_at::TEXT, updated_at::TEXT
          FROM users WHERE id = $uid
        """.query[User].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to find user uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  /** Cascades to notification_subscriptions via FK ON DELETE CASCADE. */
  def deleteById(uid: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM users WHERE id = $uid".update.run
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete user uid=$uid: ${e.getMessage}", e)
        Left(e)
    }
}
