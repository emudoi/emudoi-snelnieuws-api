package com.snelnieuws.service

import com.snelnieuws.model.{
  AndroidBroadcastResponse,
  AndroidSubscribeRequest,
  DispatchResponse,
  TopStoryPayload
}
import com.snelnieuws.repository.{
  AndroidNotificationDispatchRepository,
  AndroidNotificationSubscriptionRepository,
  ArticleRepository,
  FeatureFlagRepository,
  TopSummaryRepository
}
import io.circe.Json
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
    * fan-out, but with the top story SELECTED INLINE from the
    * articles window since the last Android dispatch. See the iOS
    * service's `composeAndSendInline` for the full rationale (the
    * 2026-05-24 inline-top-story refactor).
    *
    * Android and iOS run independently — each platform's
    * `lastAsOf` watermark is in its own `*_notification_dispatches`
    * table. That means the platforms can pick DIFFERENT top stories
    * if their dispatch tick cadence is shifted; that's correct
    * behaviour, each fan-out represents "what's NEW for this
    * platform's subscribers since they last got a push". */
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
                          composeAndSendInline(
                            client = client,
                            frequency = frequency,
                            lastAsOf = lastAsOf,
                            currentMax = currentMax,
                            newArticles = newArticles
                          )
                        }
        } yield outcome
    }

  /** Android inline composer. Same shape as
    * NotificationService.composeAndSendInline — see that comment for
    * the rationale. Logic is identical apart from:
    *   - calling the Android sendByLanguage (FCM tokens, no env)
    *   - writing to the Android dispatch repository
    *   - sharing the SAME top_summaries audit table as iOS (one
    *     selection per dispatch tier per platform, audit per row)
    */
  private def composeAndSendInline(
    client: FcmMessagingService,
    frequency: Option[Int],
    lastAsOf: Option[Long],
    currentMax: Option[Long],
    newArticles: Int
  ): Either[Throwable, DispatchOutcome] =
    for {
      window <- articleRepository.findInWindowForTopStory(lastAsOf, currentMax, "en")
      outcome <- TopStorySelector.selectFromWindow(window) match {
        case None =>
          logger.info(
            s"dispatch: no viable top story in window of ${window.size} articles; " +
              s"android freq=$frequency newArticles=$newArticles"
          )
          Right(DispatchOutcome.NoFreshTopStory)
        case Some(sel) =>
          articleRepository.findTitlesByUrl(sel.representativeUrl).flatMap {
            case empty if empty.isEmpty =>
              logger.warn(
                s"dispatch: android rep url=${sel.representativeUrl} has 0 title rows; " +
                  s"degrading to NoFreshTopStory. freq=$frequency"
              )
              Right(DispatchOutcome.NoFreshTopStory)
            case titlesByLang =>
              val notifMessages = composeNotificationMessages(titlesByLang, newArticles)
              val payload = TopStoryPayload(
                representativeArticleId = sel.representativeArticleId,
                topNews                 = Json.obj(),
                notificationMessages    = notifMessages,
                selectionTier           = sel.tier.code,
                selectionMetadata       = sel.selectionMetadata
              )
              for {
                topId    <- topSummaryRepository.insert(payload)
                _        <- topSummaryRepository.markDispatched(topId, OffsetDateTime.now())
                (sent, failed) = sendByLanguage(client, frequency, notifMessages)
                _        <- dispatchRepository.recordDispatch(
                              frequency      = frequency,
                              asOfArticleId  = currentMax,
                              newArticles    = newArticles,
                              sent           = sent,
                              failed         = failed,
                              title          = s"top_summary=$topId",
                              body           = notifMessages.keys.toList.sorted.mkString(","),
                              topSummaryId   = Some(topId)
                            )
              } yield DispatchOutcome.Sent(DispatchResponse(sent, failed, newArticles))
          }
      }
    } yield outcome

  /** Static per-language "{n} ... articles" suffix. Duplicated from
    * NotificationService for symmetry (Android service is intentionally
    * standalone from iOS to keep ownership clean). Keep in sync. */
  private val CountPhrases: Map[String, String] = Map(
    "en" -> "%d new articles",
    "nl" -> "%d nieuwe artikelen",
    "de" -> "%d neue Artikel",
    "fr" -> "%d nouveaux articles",
    "it" -> "%d nuovi articoli",
    "es" -> "%d artículos nuevos",
    "pl" -> "%d nowych artykułów"
  )

  private def composeNotificationMessages(
    titlesByLang: Map[String, String],
    newArticles: Int
  ): Map[String, String] =
    titlesByLang.flatMap { case (lang, title) =>
      CountPhrases.get(lang).map { suffixTmpl =>
        lang -> s"$title — ${suffixTmpl.format(newArticles)}"
      }
    }

  private def sendByLanguage(
    client: FcmMessagingService,
    frequency: Option[Int],
    messages: Map[String, String]
  ): (Int, Int) = {
    val grouped = subscriptionRepository
      .findTokensByLanguageGrouped(frequency)
      .getOrElse(Map.empty)
    var totalSent   = 0
    var totalFailed = 0
    messages.foreach { case (lang, title) =>
      grouped.get(lang).filter(_.nonEmpty) match {
        case None =>
          logger.debug(s"dispatch: no Android subscribers for lang=$lang freq=$frequency")
        case Some(tokens) =>
          val (sent, failed) = client.sendBatch(tokens, title, "")
          totalSent   += sent
          totalFailed += failed
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
