package com.snelnieuws.service

import com.snelnieuws.repository.AppClientRepository
import org.slf4j.LoggerFactory

import java.util.UUID

/** Per-client video reel feed. Mirrors ArticleService.personalisedV3Fetch's
  * served-id rotation so the reel behaves exactly like the text feed:
  *
  *   1. Load the marketing video catalogue (completed only, newest-first).
  *   2. Filter out ids this client has already been served.
  *   3. Return the next `limit` unseen videos; append them to the served set.
  *   4. On exhaustion (everything seen) RESET the served set and start the
  *      cycle over — this is the "loop back once you've seen them all".
  *
  * `hasMore=false` tells the client it has reached the end of the current
  * cycle; the app shows the caught-up sentinel and re-fetches, at which point
  * step 4 has reset the set and the loop continues.
  */
class VideoFeedService(
  marketingClient: MarketingApiClient,
  appClientRepository: AppClientRepository
) {

  private val logger = LoggerFactory.getLogger(classOf[VideoFeedService])

  /** Returns (videos for this page, hasMore). On any upstream/marketing
    * failure returns Left — the servlet maps that to 502. */
  def fetch(clientId: UUID, limit: Int): Either[Throwable, (List[MarketingVideo], Boolean)] =
    for {
      catalogue <- marketingClient.listCompletedVideos()
      served    <- appClientRepository.readServedVideoIds(clientId)
      result <- {
        val servedSet = served
        val unseen    = catalogue.filterNot(v => servedSet.contains(v.id))
        if (unseen.nonEmpty) {
          val page    = unseen.take(limit)
          val hasMore = unseen.size > page.size
          appClientRepository
            .appendServedVideoIds(clientId, page.map(_.id))
            .map(_ => (page, hasMore))
        } else {
          // Exhausted this cycle — reset and serve from the top again.
          val page    = catalogue.take(limit)
          val hasMore = catalogue.size > page.size
          logger.debug(s"video feed exhausted for client=$clientId; resetting rotation")
          appClientRepository
            .setServedVideoIds(clientId, page.map(_.id))
            .map(_ => (page, hasMore))
        }
      }
    } yield result
}
