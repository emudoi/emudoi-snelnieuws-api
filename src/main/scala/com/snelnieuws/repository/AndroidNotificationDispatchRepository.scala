package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.slf4j.LoggerFactory

/** Mirror of `NotificationDispatchRepository` for Android. No environment
  * dimension — FCM has no sandbox/production split. Watermark
  * (`as_of_article_id`) is tracked independently from the iOS dispatch
  * table so a push to one platform doesn't bump the other's pointer.
  */
class AndroidNotificationDispatchRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[AndroidNotificationDispatchRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  def findLastAsOfArticleId(frequency: Option[Int]): Either[Throwable, Option[Long]] =
    try {
      val q = frequency match {
        case Some(f) =>
          sql"""SELECT as_of_article_id
                FROM android_notification_dispatches
                WHERE frequency = $f
                ORDER BY dispatched_at DESC
                LIMIT 1"""
        case None =>
          sql"""SELECT as_of_article_id
                FROM android_notification_dispatches
                WHERE frequency IS NULL
                ORDER BY dispatched_at DESC
                LIMIT 1"""
      }
      Right(q.query[Option[Long]].option.transact(transactor).unsafeRunSync().flatten)
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to fetch Android last dispatch as_of_article_id freq=$frequency: ${e.getMessage}",
          e
        )
        Left(e)
    }

  def recordDispatch(
    frequency: Option[Int],
    asOfArticleId: Option[Long],
    newArticles: Int,
    sent: Int,
    failed: Int,
    title: String,
    body: String,
    topSummaryId: Option[Long] = None
  ): Either[Throwable, Long] =
    try
      Right(
        sql"""
          INSERT INTO android_notification_dispatches
            (frequency, as_of_article_id, new_articles, sent_count, failed_count,
             title, body, top_summary_id)
          VALUES
            ($frequency, $asOfArticleId, $newArticles, $sent, $failed,
             $title, $body, $topSummaryId)
          RETURNING id
        """.query[Long].unique.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to record Android dispatch freq=$frequency: ${e.getMessage}",
          e
        )
        Left(e)
    }
}
