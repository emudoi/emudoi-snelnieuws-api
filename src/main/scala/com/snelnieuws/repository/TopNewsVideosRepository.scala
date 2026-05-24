package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.snelnieuws.model.TopNewsVideoRow
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import org.slf4j.LoggerFactory

/** Persistence for `top_news_videos` — the work queue the
  * litikai-video-generator pipeline reads from. The dispatch flow
  * inserts a pair of rows (one `v21` deep-dive + one `yellow` rundown)
  * per Airflow tick; the video renderer claims `pending` rows
  * downstream.
  *
  * `id`, `status`, `created_at` are populated by table defaults — we
  * only supply the input columns (`text`, `anchor`, `variant`,
  * `description`).
  */
class TopNewsVideosRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[TopNewsVideosRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Insert all rows in a single transaction so either both video
    * scripts land or neither — we never want a half-populated pair
    * (the renderer would produce a lone v21 video with no companion
    * top-5 rundown). Returns the generated ids in input order.
    */
  def insertBatch(rows: List[TopNewsVideoRow]): Either[Throwable, List[Long]] =
    try {
      if (rows.isEmpty) Right(Nil)
      else {
        val program = rows.traverse { r =>
          sql"""
            INSERT INTO top_news_videos (text, anchor, variant, description)
            VALUES (${r.text}, ${r.anchor}, ${r.variant}, ${r.description})
            RETURNING id
          """.query[Long].unique
        }
        Right(program.transact(transactor).unsafeRunSync())
      }
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to insertBatch ${rows.size} top_news_videos rows: ${e.getMessage}",
          e
        )
        Left(e)
    }
}
