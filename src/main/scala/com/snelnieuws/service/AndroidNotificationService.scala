package com.snelnieuws.service

import com.snelnieuws.model.{
  AndroidBroadcastResponse,
  AndroidSubscribeRequest,
  DispatchResponse,
  NotificationCandidateInsert,
  NotificationCandidatePicked
}
import com.snelnieuws.repository.{
  AndroidNotificationSubscriptionRepository,
  ArticleRepository,
  FeatureFlagRepository,
  NotificationCandidateRepository
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

/** Owns the subscribe + dispatch + broadcast flows for Android FCM. Mirrors
  * `NotificationService` (iOS); the two share the per-language
  * `notification_candidates` pool but nothing else — each row is keyed by
  * `run_id` (per-fire, per-platform) so iOS and Android pools never collide.
  *
  * `fcm` is `Option` so the service degrades gracefully when notifications
  * are disabled (config flag) or FCM init failed at boot — dispatch then
  * returns `DispatchOutcome.Disabled` and the servlet maps that to 503.
  */
class AndroidNotificationService(
  articleRepository: ArticleRepository,
  subscriptionRepository: AndroidNotificationSubscriptionRepository,
  featureFlagRepository: FeatureFlagRepository,
  candidateRepository: NotificationCandidateRepository,
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

  /** Per-language top-story dispatch for Android FCM. Same shape as the
    * iOS service: build a per-language pool from each language's recent
    * articles, claim the best candidate, push its title to that
    * language's subscribers. NoFreshTopStory when no language yields a
    * candidate. */
  def dispatch(frequency: Option[Int]): Either[Throwable, DispatchOutcome] =
    fcm match {
      case None         => Right(DispatchOutcome.Disabled)
      case Some(client) => composeAndSend(client, frequency)
    }

  private def composeAndSend(
    client: FcmMessagingService,
    frequency: Option[Int]
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
        logger.info(s"dispatch: android no candidate for any language; runId=$runId freq=$frequency")
        Right(DispatchOutcome.NoFreshTopStory)
      } else {
        dispatchFromPoolPicks(client, frequency, picksByLanguage, runId)
      }
    } yield outcome
  }

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
                  s"dispatch: android claim lost for lang=$language candidate.id=${cand.id} — retrying"
                )
                attempt(remaining - 1)
              case _ =>
                articleRepository.findById(cand.representativeArticleId).map {
                  case Some(article) =>
                    Some(PoolPick(candidate = cand, title = article.title))
                  case None =>
                    logger.warn(
                      s"dispatch: android claimed candidate.id=${cand.id} but article.id=${cand.representativeArticleId} " +
                        s"is missing (cleanup race?); skipping lang=$language"
                    )
                    None
                }
            }
        }
    attempt(3)
  }

  private def dispatchFromPoolPicks(
    client:          FcmMessagingService,
    frequency:       Option[Int],
    picksByLanguage: Map[String, PoolPick],
    runId:           UUID
  ): Either[Throwable, DispatchOutcome] = {
    val notifMessages: Map[String, String] =
      picksByLanguage.map { case (lang, pick) => lang -> pick.title }

    val (sent, failed) = sendByLanguage(client, frequency, notifMessages)
    logger.info(
      s"dispatch: android runId=$runId freq=$frequency " +
        s"languages=${picksByLanguage.keys.toList.sorted.mkString(",")} " +
        s"ranks=${picksByLanguage.toList.sortBy(_._1).map { case (l, p) => s"$l:${p.candidate.rank}" }.mkString(",")} " +
        s"sent=$sent failed=$failed"
    )
    Right(DispatchOutcome.Sent(DispatchResponse(sent, failed)))
  }

  private case class PoolPick(
    candidate: NotificationCandidatePicked,
    title:     String
  )

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
    * `notify_android` feature flag is on. Independent of dispatch. */
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
