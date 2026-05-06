package com.snelnieuws.service

import com.snelnieuws.model.{DispatchResponse, SubscribeRequest}
import com.snelnieuws.repository.{
  ArticleRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository
}
import org.slf4j.LoggerFactory

sealed trait DispatchOutcome
object DispatchOutcome {
  case object Disabled                          extends DispatchOutcome
  case class Sent(response: DispatchResponse)   extends DispatchOutcome
}

/** Owns the subscribe + dispatch flows. APNs is optional — when None
 *  (notifications disabled or APNs init failed at boot), dispatch returns
 *  `DispatchOutcome.Disabled` and the servlet maps that to 503.
 */
class NotificationService(
  articleRepository: ArticleRepository,
  subscriptionRepository: NotificationSubscriptionRepository,
  dispatchRepository: NotificationDispatchRepository,
  apns: Option[ApnsMessagingService]
) {

  private val logger = LoggerFactory.getLogger(classOf[NotificationService])

  def subscribe(req: SubscribeRequest): Either[Throwable, Int] =
    subscriptionRepository.upsert(req.deviceId, req.apnsToken, req.frequency)

  def dispatch(frequency: Option[Int]): Either[Throwable, DispatchOutcome] = {
    apns match {
      case None =>
        Right(DispatchOutcome.Disabled)
      case Some(client) =>
        for {
          lastAsOf    <- dispatchRepository.findLastAsOfArticleId(frequency)
          newArticles <- articleRepository.countSinceId(lastAsOf)
          currentMax  <- articleRepository.latestId()
          title        = if (newArticles == 1) "1 new article" else s"$newArticles new articles"
          body         = "Check them out in Snel Nieuws"
          sentFailed  <- sendIfAny(client, frequency, newArticles, title, body)
          (sent, failed) = sentFailed
          _           <- dispatchRepository.recordDispatch(
            frequency = frequency,
            asOfArticleId = currentMax,
            newArticles = newArticles,
            sent = sent,
            failed = failed,
            title = title,
            body = body
          )
        } yield DispatchOutcome.Sent(DispatchResponse(sent, failed, newArticles))
    }
  }

  private def sendIfAny(
    client: ApnsMessagingService,
    frequency: Option[Int],
    newArticles: Int,
    title: String,
    body: String
  ): Either[Throwable, (Int, Int)] = {
    if (newArticles == 0) Right((0, 0))
    else {
      val tokensE = frequency match {
        case Some(f) => subscriptionRepository.findTokensByFrequency(f)
        case None    => subscriptionRepository.findAllTokens()
      }
      tokensE.map(tokens => client.sendBatch(tokens, title, body))
    }
  }
}
