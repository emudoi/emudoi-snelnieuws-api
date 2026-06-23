package com.snelnieuws.service

import com.snelnieuws.repository.{AppClientRepository, VideoRepository}
import org.slf4j.LoggerFactory

import java.util.UUID

/** A reel item, unified across the local-table source (the new, Kafka-fed
  * `videos`) and the legacy marketing-api source. `streamUrl` is Some(cdn) for
  * local videos (played directly) and None for marketing (the servlet builds
  * its `/v3/videos/:id/stream` proxy URL). `url` is the source article's
  * publisher link (local only) — powers tap-to-article. */
case class FeedVideo(
  id:          Long,
  streamUrl:   Option[String],
  durationSec: Option[Double],
  title:       String,
  variant:     String,
  url:         Option[String],
  urlToImage:  Option[String]
)

/** Per-client video reel feed with served-id rotation (mirrors the article
  * feed). The catalogue comes from the local `videos` table when
  * `feedSource = "local"` (the target: Kafka-fed, no upstream call), or the
  * legacy marketing-api when `"marketing"` (transitional default, so the
  * cutover deploy doesn't empty the reel before the table fills). */
class VideoFeedService(
  marketingClient: MarketingApiClient,
  videoRepository: VideoRepository,
  appClientRepository: AppClientRepository,
  feedSource: String = "marketing"
) {

  private val logger = LoggerFactory.getLogger(classOf[VideoFeedService])

  private def catalogue(): Either[Throwable, List[FeedVideo]] =
    if (feedSource == "local")
      videoRepository.listCatalogue().map(_.map { v =>
        FeedVideo(v.id, Some(v.streamUrl), v.durationSec, v.title,
                  v.variant.getOrElse(""), v.url, v.urlToImage)
      })
    else
      marketingClient.listCompletedVideos().map(_.map { v =>
        FeedVideo(v.id, None, v.durationSec, v.title, v.variant, None, None)
      })

  /** Returns (videos for this page, hasMore). */
  def fetch(clientId: UUID, limit: Int): Either[Throwable, (List[FeedVideo], Boolean)] =
    for {
      cat    <- catalogue()
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
