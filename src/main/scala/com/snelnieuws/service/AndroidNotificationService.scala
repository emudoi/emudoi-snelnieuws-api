package com.snelnieuws.service

import com.snelnieuws.model.{
  AndroidBroadcastResponse,
  AndroidSubscribeRequest,
  DispatchResponse
}
import com.snelnieuws.repository.{
  AndroidNotificationDispatchRepository,
  AndroidNotificationSubscriptionRepository,
  ArticleRepository,
  FeatureFlagRepository,
  TopSummaryRepository
}
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import java.util.UUID

/** Feature-flag name guarding the Android broadcast endpoint. Symmetric to
  * `BroadcastFeatureFlag.Production` (iOS). Repo treats unknown names as
  * `false` so a typo in code can only fail closed.
  */
object AndroidBroadcastFeatureFlag {
  val Android = "notify_android"
}

/** Owns the subscribe + dispatch + broadcast flows for Android FCM. Fully
  * separate from `NotificationService` (iOS) — no shared state, no shared
  * tables. Constructor mirrors the iOS service shape so tests can use the
  * same patterns.
  *
  * `fcm` is `Option` so the service degrades gracefully when notifications
  * are disabled (config flag) or FCM init failed at boot — dispatch then
  * returns `DispatchOutcome.Disabled` and the servlet maps that to 503.
  */
class AndroidNotificationService(
  articleRepository: ArticleRepository,
  subscriptionRepository: AndroidNotificationSubscriptionRepository,
  dispatchRepository: AndroidNotificationDispatchRepository,
  featureFlagRepository: FeatureFlagRepository,
  topSummaryRepository: TopSummaryRepository,
  fcm: Option[FcmMessagingService]
) {

  private val logger = LoggerFactory.getLogger(classOf[AndroidNotificationService])

  def subscribe(
    req: AndroidSubscribeRequest,
    userId: Option[String] = None,
    clientId: Option[UUID] = None
  ): Either[Throwable, Int] = {
    val lang = req.notificationLanguage
      .map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("en")
    subscriptionRepository.upsert(
      req.deviceId,
      req.fcmToken,
      req.frequency,
      userId,
      clientId,
      lang
    )
  }

  def deleteDevice(deviceId: String): Either[Throwable, Int] =
    subscriptionRepository.deleteByDeviceId(deviceId)

  /** Mirror of NotificationService.dispatch — per-language clickbait
    * fan-out from the latest undispatched top_summary, with
    * NoFreshTopStory when none. See
    * notifications_clickbait_tasks.txt §8. */
  def dispatch(frequency: Option[Int]): Either[Throwable, DispatchOutcome] =
    fcm match {
      case None =>
        Right(DispatchOutcome.Disabled)
      case Some(client) =>
        for {
          lastAsOf    <- dispatchRepository.findLastAsOfArticleId(frequency)
          newArticles <- articleRepository.countSinceId(lastAsOf)
          currentMax  <- articleRepository.latestId()
          outcome     <- if (newArticles == 0) {
                          dispatchRepository
                            .recordDispatch(
                              frequency = frequency,
                              asOfArticleId = currentMax,
                              newArticles = 0,
                              sent = 0,
                              failed = 0,
                              title = "",
                              body = "",
                              topSummaryId = None
                            )
                            .map(_ => DispatchOutcome.Sent(DispatchResponse(0, 0, 0)))
                        } else {
                          topSummaryRepository.findLatestUndispatched().flatMap {
                            case None =>
                              logger.info(
                                s"dispatch: no fresh top_summary; android freq=$frequency newArticles=$newArticles"
                              )
                              Right(DispatchOutcome.NoFreshTopStory)
                            case Some(top) =>
                              val (sent, failed) = sendByLanguage(
                                client,
                                frequency,
                                top.notificationMessages
                              )
                              for {
                                _ <- topSummaryRepository.markDispatched(top.id, OffsetDateTime.now())
                                _ <- dispatchRepository.recordDispatch(
                                  frequency      = frequency,
                                  asOfArticleId  = currentMax,
                                  newArticles    = newArticles,
                                  sent           = sent,
                                  failed         = failed,
                                  title          = s"top_summary=${top.id}",
                                  body           = top.notificationMessages.keys.toList.sorted.mkString(","),
                                  topSummaryId   = Some(top.id)
                                )
                              } yield DispatchOutcome.Sent(DispatchResponse(sent, failed, newArticles))
                          }
                        }
        } yield outcome
    }

  /** Per-language fan-out with English fallback. Mirrors the iOS
    * NotificationService.sendByLanguage rewrite — iterate subscriber
    * buckets (not message languages), look up that bucket's language
    * in `messages`, fall back to `messages.get("en")` when not
    * present, skip only when neither is available. See
    * NotificationService.sendByLanguage for the full rationale +
    * the 2026-05-22 incident it guards against. */
  private def sendByLanguage(
    client: FcmMessagingService,
    frequency: Option[Int],
    messages: Map[String, String]
  ): (Int, Int) = {
    val grouped = subscriptionRepository
      .findTokensByLanguageGrouped(frequency)
      .getOrElse(Map.empty)
    val englishFallback: Option[String] = messages.get("en")
    var totalSent   = 0
    var totalFailed = 0
    grouped.foreach { case (subscriberLang, tokens) =>
      if (tokens.isEmpty) {
        // Empty bucket — skip without log noise.
      } else {
        val title: Option[String] = messages.get(subscriberLang).orElse(englishFallback)
        title match {
          case None =>
            logger.warn(
              s"dispatch: no Android message for subscriberLang=$subscriberLang and no `en` fallback in " +
                s"top_summary (keys=${messages.keys.toList.sorted.mkString(",")}); " +
                s"${tokens.size} subscribers skipped freq=$frequency"
            )
          case Some(t) =>
            val isFallback = !messages.contains(subscriberLang)
            if (isFallback) {
              logger.info(
                s"dispatch: english_fallback Android subscriberLang=$subscriberLang " +
                  s"tokens=${tokens.size} freq=$frequency " +
                  s"top_summary_keys=${messages.keys.toList.sorted.mkString(",")}"
              )
            }
            val (sent, failed) = client.sendBatch(tokens, t, "")
            totalSent   += sent
            totalFailed += failed
        }
      }
    }
    (totalSent, totalFailed)
  }

  /** Broadcast a free-form text to all Android subscribers when the
    * `notify_android` feature flag is on. Independent of dispatch
    * tracking — does not read or write `android_notification_dispatches`.
    */
  def broadcast(text: String): Either[Throwable, AndroidBroadcastResponse] = {
    val title = "Snel Nieuws"
    for {
      enabled <- featureFlagRepository.isEnabled(AndroidBroadcastFeatureFlag.Android)
      result  <- broadcastTo(enabled, title, text)
    } yield result
  }

  private def broadcastTo(
    enabled: Boolean,
    title: String,
    body: String
  ): Either[Throwable, AndroidBroadcastResponse] = {
    if (!enabled) Right(AndroidBroadcastResponse(enabled = false, sent = 0, failed = 0))
    else
      fcm match {
        case None =>
          // Flag is on but FCM init never succeeded — log and surface
          // enabled=true so the caller sees the flag is on but nothing
          // went out. Pod logs explain why.
          logger.warn("broadcast: notify_android flag is on but FCM client is not initialized")
          Right(AndroidBroadcastResponse(enabled = true, sent = 0, failed = 0))
        case Some(client) =>
          subscriptionRepository.findAllTokens().map { tokens =>
            val (sent, failed) = client.sendBatch(tokens, title, body)
            AndroidBroadcastResponse(enabled = true, sent = sent, failed = failed)
          }
      }
  }
}
