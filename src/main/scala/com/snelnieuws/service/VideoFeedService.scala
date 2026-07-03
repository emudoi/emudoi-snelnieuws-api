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

  /** Returns (videos for this page, hasMore). Filtered to `language`.
    *
    * The reel is an ENDLESS newest-first loop. We serve the client's unseen
    * videos a page at a time; when they run out we reset the rotation and start
    * again from the newest. `hasMore` reflects "the reel can keep going" — true
    * whenever the catalogue is larger than a single page — rather than "unseen
    * videos remain". That way the current apps (which stop paginating the moment
    * they see hasMore=false) never dead-end on a short final page: they simply
    * fetch the next page, which wraps back to the top. The only time hasMore is
    * false is when the whole catalogue fits in one page (nothing to page to) or
    * there are no videos at all. */
  def fetch(clientId: UUID, limit: Int, language: String): Either[Throwable, (List[FeedVideo], Boolean)] =
    for {
      cat    <- catalogue(language)
      served <- appClientRepository.readServedVideoIds(clientId)
      result <- {
        // A full catalogue means the reel can always advance to a next page
        // (either more unseen, or a wrap back to the newest). Constant across
        // both branches so the client keeps looping instead of stopping.
        val hasMore = cat.size > limit
        val unseen  = cat.filterNot(v => served.contains(v.id))
        if (unseen.nonEmpty) {
          val page = unseen.take(limit)
          appClientRepository.appendServedVideoIds(clientId, page.map(_.id)).map(_ => (page, hasMore))
        } else {
          // Exhausted — reset rotation and loop from the newest again.
          val page = cat.take(limit)
          logger.debug(s"video feed exhausted for client=$clientId; looping rotation from newest")
          appClientRepository.setServedVideoIds(clientId, page.map(_.id)).map(_ => (page, hasMore))
        }
      }
    } yield result
}
