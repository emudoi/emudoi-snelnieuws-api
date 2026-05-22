package com.snelnieuws.service

import com.snelnieuws.model.{
  BroadcastEnvResult,
  BroadcastResponse,
  DispatchResponse,
  SubscribeRequest
}
import com.snelnieuws.repository.{
  ArticleRepository,
  FeatureFlagRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository,
  TopSummaryRepository
}
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import java.util.UUID

sealed trait DispatchOutcome
object DispatchOutcome {
  case object Disabled                          extends DispatchOutcome
  case class Sent(response: DispatchResponse)   extends DispatchOutcome
  /** Snelmind hasn't shipped a fresh top story since the last
    * dispatched_at — the watcher MUST NOT advance its pivot/counter
    * (notifications_clickbait_tasks.txt §8 + §10 no-fallback policy).
    * The dispatch servlet maps this to HTTP 503 with
    * `{"error": "no_fresh_top_story"}`. */
  case object NoFreshTopStory                   extends DispatchOutcome
}

object NotificationEnvironment {
  val Production = "production"
  val Sandbox    = "sandbox"
}

/** Names of the feature flags read by NotificationService.broadcast. The
  * constants exist so a typo in code is a compile error rather than a
  * silently-disabled broadcast (the repo treats unknown names as `false`).
  */
object BroadcastFeatureFlag {
  val Sandbox    = "test_notification"
  val Production = "notify_applestore_apps"
}

/** Owns the subscribe + dispatch flows. APNs is optional per environment —
 *  when the matching client is None (notifications disabled, init failed,
 *  or that environment was never configured), dispatch returns
 *  `DispatchOutcome.Disabled` and the servlet maps that to 503.
 *
 *  Two clients exist because APNs sandbox tokens (Xcode-debug builds) only
 *  work against api.sandbox.push.apple.com and production tokens (TestFlight
 *  + App Store) only work against api.push.apple.com. Same .p8 signs both
 *  — they differ only in which Apple host they target.
 */
class NotificationService(
  articleRepository: ArticleRepository,
  subscriptionRepository: NotificationSubscriptionRepository,
  dispatchRepository: NotificationDispatchRepository,
  featureFlagRepository: FeatureFlagRepository,
  topSummaryRepository: TopSummaryRepository,
  apnsProd: Option[ApnsMessagingService],
  apnsSandbox: Option[ApnsMessagingService]
) {

  private val logger = LoggerFactory.getLogger(classOf[NotificationService])

  def subscribe(
    req: SubscribeRequest,
    userId: Option[String] = None,
    clientId: Option[UUID] = None
  ): Either[Throwable, Int] = {
    // notificationLanguage defaults to "en". Validate against the
    // supported set via the V25 CHECK constraint — Postgres rejects
    // an unsupported code at INSERT, which surfaces as a Left here.
    val lang = req.notificationLanguage.map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("en")
    subscriptionRepository.upsert(
      req.deviceId,
      req.apnsToken,
      req.frequency,
      req.environment,
      userId,
      clientId,
      lang
    )
  }

  /** Delete a single device's subscription regardless of whether it was
    * linked to a user. Used by account-deletion to clean up rows whose
    * user_id is NULL (the FK CASCADE doesn't cover those). */
  def deleteDevice(deviceId: String): Either[Throwable, Int] =
    subscriptionRepository.deleteByDeviceId(deviceId)

  /** Dispatch the latest undispatched top_summary's per-language
    * clickbait text to subscribers grouped by notification_language
    * (notifications_clickbait_tasks.txt §8). No fallback to the legacy
    * "$N new articles" path — if no fresh top story exists,
    * NoFreshTopStory is returned and the watcher silently skips the
    * fire (§10 no-fallback policy, observe-and-revisit-after-7d). */
  def dispatch(
    frequency: Option[Int],
    environment: String
  ): Either[Throwable, DispatchOutcome] = {
    val client = environment match {
      case NotificationEnvironment.Sandbox => apnsSandbox
      case _                               => apnsProd
    }
    client match {
      case None =>
        Right(DispatchOutcome.Disabled)
      case Some(c) =>
        for {
          lastAsOf    <- dispatchRepository.findLastAsOfArticleId(frequency, environment)
          newArticles <- articleRepository.countSinceId(lastAsOf)
          currentMax  <- articleRepository.latestId()
          outcome     <- if (newArticles == 0) {
                          // No new articles → no fire, but record the
                          // no-op for audit. Unchanged from legacy behavior.
                          dispatchRepository
                            .recordDispatch(
                              frequency = frequency,
                              environment = environment,
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
                              // §8 NoFreshTopStory branch. Do NOT
                              // advance the pivot — the watcher will
                              // retry on the next tick if/when a fresh
                              // top story lands.
                              logger.info(
                                s"dispatch: no fresh top_summary; env=$environment freq=$frequency newArticles=$newArticles"
                              )
                              Right(DispatchOutcome.NoFreshTopStory)
                            case Some(top) =>
                              val (sent, failed) = sendByLanguage(
                                c,
                                frequency,
                                environment,
                                top.notificationMessages
                              )
                              for {
                                _ <- topSummaryRepository.markDispatched(top.id, OffsetDateTime.now())
                                _ <- dispatchRepository.recordDispatch(
                                  frequency      = frequency,
                                  environment    = environment,
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
  }

  /** Per-language fan-out. For each language present in the top
    * summary's notificationMessages map, look up the matching
    * subscriber tokens and send the localized title. Body is empty so
    * the lockscreen stays single-line.
    *
    * Languages with no matching subscribers are silently skipped. */
  private def sendByLanguage(
    client: ApnsMessagingService,
    frequency: Option[Int],
    environment: String,
    messages: Map[String, String]
  ): (Int, Int) = {
    val grouped = subscriptionRepository
      .findTokensByLanguageGrouped(environment, frequency)
      .getOrElse(Map.empty)
    var totalSent   = 0
    var totalFailed = 0
    messages.foreach { case (lang, title) =>
      grouped.get(lang).filter(_.nonEmpty) match {
        case None =>
          logger.debug(s"dispatch: no subscribers for lang=$lang env=$environment freq=$frequency")
        case Some(tokens) =>
          val (sent, failed) = client.sendBatch(tokens, title, "")
          totalSent   += sent
          totalFailed += failed
      }
    }
    (totalSent, totalFailed)
  }

  /** Broadcast a free-form text to every subscriber in each environment
    * whose feature flag is enabled. Independent of the per-frequency
    * dispatch tracking — does not read or write `notification_dispatches`.
    *
    * Title is hardcoded to "Snel Nieuws"; the request body becomes the
    * APNs alert body. Both flags can be enabled simultaneously, in which
    * case the broadcast fans out to both environments in a single call.
    */
  def broadcast(text: String): Either[Throwable, BroadcastResponse] = {
    val title = "Snel Nieuws"
    for {
      sandboxEnabled <- featureFlagRepository.isEnabled(BroadcastFeatureFlag.Sandbox)
      prodEnabled    <- featureFlagRepository.isEnabled(BroadcastFeatureFlag.Production)
      sandbox        <- broadcastTo(apnsSandbox, sandboxEnabled, NotificationEnvironment.Sandbox, title, text)
      production     <- broadcastTo(apnsProd, prodEnabled, NotificationEnvironment.Production, title, text)
    } yield BroadcastResponse(production = production, sandbox = sandbox)
  }

  private def broadcastTo(
    client: Option[ApnsMessagingService],
    enabled: Boolean,
    environment: String,
    title: String,
    body: String
  ): Either[Throwable, BroadcastEnvResult] = {
    if (!enabled) Right(BroadcastEnvResult(enabled = false, sent = 0, failed = 0))
    else
      client match {
        case None =>
          // Flag is on but APNs init never succeeded for this environment
          // (e.g. .p8 missing at boot). Surface enabled=true so the caller
          // can tell the flag is on; sent/failed=0 signals nothing went
          // out. The pod logs explain why.
          logger.warn(s"broadcast: $environment flag is on but APNs client is not initialized")
          Right(BroadcastEnvResult(enabled = true, sent = 0, failed = 0))
        case Some(c) =>
          subscriptionRepository.findAllTokensByEnvironment(environment).map { tokens =>
            val (sent, failed) = c.sendBatch(tokens, title, body)
            BroadcastEnvResult(enabled = true, sent = sent, failed = failed)
          }
      }
  }
}
