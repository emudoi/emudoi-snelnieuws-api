package com.snelnieuws.service

import com.snelnieuws.model.{
  BroadcastEnvResult,
  BroadcastResponse,
  DispatchResponse,
  NotificationCandidateInsert,
  NotificationCandidatePicked,
  SubscribeRequest
}
import com.snelnieuws.repository.{
  ArticleRepository,
  FeatureFlagRepository,
  NotificationCandidateRepository,
  NotificationSubscriptionRepository
}
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import java.util.UUID

sealed trait DispatchOutcome
object DispatchOutcome {
  case object Disabled                          extends DispatchOutcome
  case class Sent(response: DispatchResponse)   extends DispatchOutcome
  /** No language had a pickable candidate this tick (empty recent
    * window + empty leftover pool). The dispatch servlet maps this to
    * HTTP 503 with `{"error": "no_fresh_top_story"}`; nothing was sent. */
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

/** Per-language notification-pool tunables.
  *
  * Each dispatch builds a per-language pool of up to `PoolSize` top
  * candidates from that language's articles published in the last
  * `RecentWindowHours`, ranked and stored in `notification_candidates`
  * for `TtlHours`. `DedupHours` is the look-back used to skip articles
  * already pushed.
  */
object NotificationPoolConfig {
  val PoolSize: Int           = 4
  val TtlHours: Long          = 12L
  val DedupHours: Long        = 24L
  val RecentWindowHours: Long = 24L
  /** Same 7 ISO codes used by `notification_subscriptions.notification_language`
    * (V25 CHECK). */
  val SupportedLanguages: List[String] =
    List("en", "nl", "de", "fr", "it", "es", "pl")
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
  featureFlagRepository: FeatureFlagRepository,
  candidateRepository: NotificationCandidateRepository,
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

  /** Per-language top-story dispatch. For each supported language we
    * build a fresh candidate pool from that language's recent articles,
    * claim the best pickable candidate, and push its title to that
    * language's subscribers. Returns NoFreshTopStory when no language
    * yields a candidate (the servlet then 503s and nothing is sent). */
  def dispatch(
    frequency: Option[Int],
    environment: String
  ): Either[Throwable, DispatchOutcome] = {
    val client = environment match {
      case NotificationEnvironment.Sandbox => apnsSandbox
      case _                               => apnsProd
    }
    client match {
      case None    => Right(DispatchOutcome.Disabled)
      case Some(c) => composeAndSend(c, frequency, environment)
    }
  }

  private def composeAndSend(
    client: ApnsMessagingService,
    frequency: Option[Int],
    environment: String
  ): Either[Throwable, DispatchOutcome] = {
    val runId       = UUID.randomUUID()
    val now         = OffsetDateTime.now()
    val expiresAt   = now.plusHours(NotificationPoolConfig.TtlHours)
    val dedupSince  = now.minusHours(NotificationPoolConfig.DedupHours)
    val recentSince = now.minusHours(NotificationPoolConfig.RecentWindowHours)

    for {
      dedupSet <- candidateRepository.findConsumedArticleIdsSince(dedupSince)
      picksByLanguage <- collectPerLanguagePicks(runId, recentSince, dedupSet, expiresAt, now)
      outcome <- if (picksByLanguage.isEmpty) {
        logger.info(
          s"dispatch: no candidate for any language; runId=$runId env=$environment freq=$frequency"
        )
        Right(DispatchOutcome.NoFreshTopStory)
      } else {
        dispatchFromPoolPicks(client, frequency, environment, picksByLanguage, runId)
      }
    } yield outcome
  }

  /** For each supported language: build the fresh pool (if any), then
    * atomically claim the best pickable candidate. Languages that
    * resolve to None are simply omitted. */
  private def collectPerLanguagePicks(
    runId:       UUID,
    recentSince: OffsetDateTime,
    dedupSet:    Set[Long],
    expiresAt:   OffsetDateTime,
    now:         OffsetDateTime
  ): Either[Throwable, Map[String, PoolPick]] =
    NotificationPoolConfig.SupportedLanguages.foldLeft[Either[Throwable, Map[String, PoolPick]]](
      Right(Map.empty)
    ) { (accE, lang) =>
      accE.flatMap { acc =>
        for {
          articlesL <- articleRepository.findRecentForTopStory(lang, recentSince)
          fresh     = TopStorySelector
                        .selectTopN(articlesL, NotificationPoolConfig.PoolSize)
                        .filterNot(s => dedupSet.contains(s.representativeArticleId))
          _         <- insertFreshPool(runId, lang, fresh, expiresAt)
          claimed   <- claimBestForLanguage(lang, now)
        } yield claimed.fold(acc)(p => acc + (lang -> p))
      }
    }

  /** Persist the fresh top-N pool for one language under `runId`.
    * No-op when `fresh` is empty (no articles in the recent window, or
    * all were already sent). */
  private def insertFreshPool(
    runId:     UUID,
    language:  String,
    fresh:     Seq[TopStorySelector.Selection],
    expiresAt: OffsetDateTime
  ): Either[Throwable, Int] =
    if (fresh.isEmpty) Right(0)
    else {
      val inserts = fresh.zipWithIndex.map { case (s, idx) =>
        NotificationCandidateInsert(
          runId                   = runId,
          language                = language,
          rank                    = idx + 1,
          representativeArticleId = s.representativeArticleId,
          representativeUrl       = s.representativeUrl,
          selectionTier           = s.tier.code.toInt,
          score                   = (NotificationPoolConfig.PoolSize - idx) * 10,
          selectionMetadata       = s.selectionMetadata,
          expiresAt               = expiresAt
        )
      }.toList
      candidateRepository.insertBatch(inserts)
    }

  /** Claim the best pickable candidate for `language` and resolve its
    * title. `markConsumed` returns 0 when another tick raced us — we
    * retry with a fresh `findPickable` (which now sees the next rank). */
  private def claimBestForLanguage(
    language: String, now: OffsetDateTime
  ): Either[Throwable, Option[PoolPick]] = {

    def attempt(remaining: Int): Either[Throwable, Option[PoolPick]] =
      if (remaining <= 0) Right(None)
      else
        candidateRepository.findPickable(language).flatMap {
          case None       => Right(None)
          case Some(cand) =>
            candidateRepository.markConsumed(cand.id, now).flatMap {
              case 0 =>
                logger.debug(
                  s"dispatch: claim lost for lang=$language candidate.id=${cand.id} — retrying"
                )
                attempt(remaining - 1)
              case _ =>
                articleRepository.findById(cand.representativeArticleId).map {
                  case Some(article) =>
                    Some(PoolPick(candidate = cand, title = article.title))
                  case None =>
                    logger.warn(
                      s"dispatch: claimed candidate.id=${cand.id} but article.id=${cand.representativeArticleId} " +
                        s"is missing (cleanup race?); skipping lang=$language"
                    )
                    None
                }
            }
        }

    attempt(3)
  }

  /** Send each language's claimed title to that language's subscribers. */
  private def dispatchFromPoolPicks(
    client:          ApnsMessagingService,
    frequency:       Option[Int],
    environment:     String,
    picksByLanguage: Map[String, PoolPick],
    runId:           UUID
  ): Either[Throwable, DispatchOutcome] = {
    val notifMessages: Map[String, String] =
      picksByLanguage.map { case (lang, pick) => lang -> pick.title }

    val (sent, failed) = sendByLanguage(client, frequency, environment, notifMessages)
    logger.info(
      s"dispatch: runId=$runId env=$environment freq=$frequency " +
        s"languages=${picksByLanguage.keys.toList.sorted.mkString(",")} " +
        s"ranks=${picksByLanguage.toList.sortBy(_._1).map { case (l, p) => s"$l:${p.candidate.rank}" }.mkString(",")} " +
        s"sent=$sent failed=$failed"
    )
    Right(DispatchOutcome.Sent(DispatchResponse(sent, failed)))
  }

  /** Internal pair carrying a claimed candidate plus the resolved
    * title for its language. Title lookup happens at claim-time so
    * the dispatcher doesn't need to re-query the article rows. */
  private case class PoolPick(
    candidate: NotificationCandidatePicked,
    title:     String
  )

  /** Per-language fan-out. For each language with a message, look up the
    * matching subscriber tokens and send the localized title. Body is
    * empty so the lockscreen stays single-line. Languages with no
    * matching subscribers are silently skipped. */
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
    * whose feature flag is enabled. Independent of dispatch.
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
