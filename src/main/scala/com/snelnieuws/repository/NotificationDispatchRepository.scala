package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.slf4j.LoggerFactory

class NotificationDispatchRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[NotificationDispatchRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Returns the `as_of_article_id` from the most recent dispatch row matching
   *  the given frequency filter (NULL for the "all subscribers" tier). None
   *  if no prior dispatch exists for that filter, or if the prior row stored
   *  a NULL as_of_article_id (e.g. articles table was empty at that time).
   */
  def findLastAsOfArticleId(frequency: Option[Int]): Either[Throwable, Option[Long]] =
    try {
      val q = frequency match {
        case Some(f) =>
          sql"""SELECT as_of_article_id
                FROM notification_dispatches
                WHERE frequency = $f
                ORDER BY dispatched_at DESC
                LIMIT 1"""
        case None =>
          sql"""SELECT as_of_article_id
                FROM notification_dispatches
                WHERE frequency IS NULL
                ORDER BY dispatched_at DESC
                LIMIT 1"""
      }
      Right(q.query[Option[Long]].option.transact(transactor).unsafeRunSync().flatten)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to fetch last dispatch as_of_article_id freq=$frequency: ${e.getMessage}", e)
        Left(e)
    }

  /** Inserts an audit row. Called on every dispatch attempt — even no-ops. */
  def recordDispatch(
    frequency: Option[Int],
    asOfArticleId: Option[Long],
    newArticles: Int,
    sent: Int,
    failed: Int,
    title: String,
    body: String
  ): Either[Throwable, Long] =
    try
      Right(
        sql"""
          INSERT INTO notification_dispatches
            (frequency, as_of_article_id, new_articles, sent_count, failed_count, title, body)
          VALUES
            ($frequency, $asOfArticleId, $newArticles, $sent, $failed, $title, $body)
          RETURNING id
        """.query[Long].unique.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to record dispatch freq=$frequency: ${e.getMessage}", e)
        Left(e)
    }
}
