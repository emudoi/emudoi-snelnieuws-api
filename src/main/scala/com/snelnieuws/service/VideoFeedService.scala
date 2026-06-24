package com.snelnieuws.service

import com.snelnieuws.repository.{AppClientRepository, VideoRepository}
import org.slf4j.LoggerFactory

import java.util.UUID

/** A reel item. `streamUrl` is the public CDN mp4 (played directly); `url` is
  * the source article's publisher link (tap-to-article). */
case class FeedVideo(
  id:          Long,
  streamUrl:   String,
  durationSec: Option[Double],
  title:       String,
  variant:     String,
  url:         Option[String],
  urlToImage:  Option[String]
)

/** Per-client video reel feed with served-id rotation (mirrors the article
  * feed). Served entirely from the local `videos` table, which is fed from
  * ingestion via Kafka — no upstream call. */
class VideoFeedService(
  videoRepository: VideoRepository,
  appClientRepository: AppClientRepository
) {

  private val logger = LoggerFactory.getLogger(classOf[VideoFeedService])

  private def catalogue(language: String): Either[Throwable, List[FeedVideo]] =
    videoRepository.listCatalogue(language).map(_.map { v =>
      FeedVideo(v.id, v.streamUrl, v.durationSec, v.title,
                v.variant.getOrElse(""), v.url, v.urlToImage)
    })

  /** Returns (videos for this page, hasMore). Filtered to `language`. */
  def fetch(clientId: UUID, limit: Int, language: String): Either[Throwable, (List[FeedVideo], Boolean)] =
    for {
      cat    <- catalogue(language)
      served <- appClientRepository.readServedVideoIds(clientId)
      result <- {
        val unseen = cat.filterNot(v => served.contains(v.id))
        if (unseen.nonEmpty) {
          val page = unseen.take(limit)
          appClientRepository.appendServedVideoIds(clientId, page.map(_.id)).map(_ => (page, unseen.size > page.size))
        } else {
          // Exhausted — reset rotation and serve from the top again.
          val page = cat.take(limit)
          logger.debug(s"video feed exhausted for client=$clientId; resetting rotation")
          appClientRepository.setServedVideoIds(clientId, page.map(_.id)).map(_ => (page, cat.size > page.size))
        }
      }
    } yield result
}
