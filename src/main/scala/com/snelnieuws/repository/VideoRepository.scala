package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.VideoRenderExport
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.time.{OffsetDateTime, ZoneOffset}

/** One row of the local `videos` table (the reel's source of truth, fed from
  * ingestion via Kafka). `id` is the ingestion video_renders.id. */
case class VideoRow(
  id:           Long,
  title:        String,
  description:  Option[String],
  streamUrl:    String,
  url:          Option[String],
  urlToImage:   Option[String],
  articleId:    Option[Long],
  category:     Option[String],
  country:      Option[String],
  language:     String,
  durationSec:  Option[Double],
  variant:      Option[String],
  publishedAt:  OffsetDateTime
)

/** Persistence for the reel's videos. Mirror of ArticleRepository (doobie +
  * HikariTransactor). Upserted by the Kafka VideoRenderConsumer; read by
  * VideoFeedService (catalogue) and VideosServletV3 (by-id, for shared links). */
class VideoRepository(
  provideTransactor: => HikariTransactor[IO]
) {

  private val logger = LoggerFactory.getLogger(classOf[VideoRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Insert or update by render id (the event is idempotent / replayable). */
  def upsert(v: VideoRenderExport): Either[Throwable, Int] =
    try {
      val publishedAt = v.publishedAt
        .flatMap(s => scala.util.Try(OffsetDateTime.parse(s)).toOption)
        .getOrElse(OffsetDateTime.now(ZoneOffset.UTC))
      val language = v.language.getOrElse("en")
      Right(
        sql"""
          INSERT INTO videos (id, title, description, stream_url, url, url_to_image,
                              article_id, category, country, language, duration_sec,
                              variant, published_at, updated_at)
          VALUES (${v.renderId}, ${v.title}, ${v.description}, ${v.streamUrl},
                  ${v.url}, ${v.urlToImage}, ${v.articleId}, ${v.category},
                  ${v.country}, $language, ${v.durationSec}, ${v.variant},
                  $publishedAt, NOW())
          ON CONFLICT (id) DO UPDATE SET
            title        = EXCLUDED.title,
            description  = EXCLUDED.description,
            stream_url   = EXCLUDED.stream_url,
            url          = EXCLUDED.url,
            url_to_image = EXCLUDED.url_to_image,
            article_id   = EXCLUDED.article_id,
            category     = EXCLUDED.category,
            country      = EXCLUDED.country,
            language     = EXCLUDED.language,
            duration_sec = EXCLUDED.duration_sec,
            variant      = EXCLUDED.variant,
            published_at = EXCLUDED.published_at,
            updated_at   = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to upsert video render id=${v.renderId}: ${e.getMessage}", e)
        Left(e)
    }

  def deleteById(id: Long): Either[Throwable, Int] =
    try Right(sql"DELETE FROM videos WHERE id = $id".update.run.transact(transactor).unsafeRunSync())
    catch { case e: Exception => logger.error(s"Failed to delete video id=$id: ${e.getMessage}", e); Left(e) }

  /** Newest-first catalogue for the reel rotation (bounded), filtered to the
    * user's language — the reel shows only videos in that language, like the
    * article feed. */
  def listCatalogue(language: String, max: Int = 200): Either[Throwable, List[VideoRow]] =
    try Right(
      sql"""
        SELECT id, title, description, stream_url, url, url_to_image, article_id,
               category, country, language, duration_sec, variant, published_at
        FROM videos
        WHERE language = $language
        ORDER BY published_at DESC, id DESC
        LIMIT $max
      """.query[VideoRow].to[List].transact(transactor).unsafeRunSync()
    )
    catch { case e: Exception => logger.error(s"listCatalogue failed: ${e.getMessage}", e); Left(e) }

  /** Resolve a shared video id (the video analogue of articleV3 by-id). */
  def findById(id: Long): Either[Throwable, Option[VideoRow]] =
    try Right(
      sql"""
        SELECT id, title, description, stream_url, url, url_to_image, article_id,
               category, country, language, duration_sec, variant, published_at
        FROM videos WHERE id = $id
      """.query[VideoRow].option.transact(transactor).unsafeRunSync()
    )
    catch { case e: Exception => logger.error(s"findById($id) failed: ${e.getMessage}", e); Left(e) }
}
