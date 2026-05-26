package com.snelnieuws.service

import com.snelnieuws.model.{
  AndroidBroadcastResponse,
  AndroidSubscribeRequest,
  DispatchResponse,
  NotificationCandidateInsert,
  NotificationCandidatePicked,
  TopStoryPayload
}
import com.snelnieuws.repository.{
  AndroidNotificationDispatchRepository,
  AndroidNotificationSubscriptionRepository,
  ArticleRepository,
  FeatureFlagRepository,
  NotificationCandidateRepository,
  TopSummaryRepository
}
import io.circe.Json
import io.circe.syntax._
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

  /** Android inline composer. Same flag-gated shape as
    * NotificationService.composeAndSendInline — when the V29 fallback
    * pool flag is off, runs the legacy single-story path byte-for-byte
    * identical to the pre-V29 code. When on, runs the per-language
    * pool path mirrored from the iOS service. */
  private def composeAndSendInline(
    client: FcmMessagingService,
    frequency: Option[Int],
    lastAsOf: Option[Long],
    currentMax: Option[Long],
    newArticles: Int
  ): Either[Throwable, DispatchOutcome] =
    for {
      flagOn  <- featureFlagRepository.isEnabled(FallbackPoolConfig.FlagName)
      outcome <- if (flagOn)
                   composeAndSendInlineWithPool(client, frequency, lastAsOf, currentMax, newArticles)
                 else
                   composeAndSendInlineLegacy(client, frequency, lastAsOf, currentMax, newArticles)
    } yield outcome

  /** Pre-V29 dispatch path. Unchanged from the 2026-05-24 inline
    * refactor so the flag-off code path is identical to the previous
    * behaviour. */
  private def composeAndSendInlineLegacy(
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

  /** V29 fallback-pool dispatch path. Mirror of
    * NotificationService.composeAndSendInlineWithPool — see that
    * comment for the full design. Android-specific differences:
    *   - sends via the FCM `sendByLanguage` (no environment split)
    *   - records dispatch in the Android dispatch table
    *   - shares the SAME `notification_candidates` table as iOS; iOS
    *     and Android pools coexist because each row is keyed by
    *     run_id (per-fire, per-platform) so they never collide. */
  private def composeAndSendInlineWithPool(
    client: FcmMessagingService,
    frequency: Option[Int],
    lastAsOf: Option[Long],
    currentMax: Option[Long],
    newArticles: Int
  ): Either[Throwable, DispatchOutcome] = {
    val runId      = UUID.randomUUID()
    val now        = OffsetDateTime.now()
    val expiresAt  = now.plusHours(FallbackPoolConfig.TtlHours)
    val dedupSince = now.minusHours(FallbackPoolConfig.DedupHours)

    for {
      dedupSet <- candidateRepository.findConsumedArticleIdsSince(dedupSince)
      picksByLanguage <- collectPerLanguagePicks(
        runId      = runId,
        lastAsOf   = lastAsOf,
        currentMax = currentMax,
        dedupSet   = dedupSet,
        expiresAt  = expiresAt,
        now        = now
      )
      outcome <- if (picksByLanguage.isEmpty) {
        logger.info(
          s"dispatch(pool): no candidate for any language; runId=$runId " +
            s"android freq=$frequency newArticles=$newArticles"
        )
        Right(DispatchOutcome.NoFreshTopStory)
      } else {
        dispatchFromPoolPicks(
          client          = client,
          frequency       = frequency,
          currentMax      = currentMax,
          newArticles     = newArticles,
          picksByLanguage = picksByLanguage,
          runId           = runId,
          now             = now
        )
      }
    } yield outcome
  }

  private def collectPerLanguagePicks(
    runId:      UUID,
    lastAsOf:   Option[Long],
    currentMax: Option[Long],
    dedupSet:   Set[Long],
    expiresAt:  OffsetDateTime,
    now:        OffsetDateTime
  ): Either[Throwable, Map[String, PoolPick]] =
    FallbackPoolConfig.SupportedLanguages.foldLeft[Either[Throwable, Map[String, PoolPick]]](
      Right(Map.empty)
    ) { (accE, lang) =>
      accE.flatMap { acc =>
        for {
          articlesL <- articleRepository.findInWindowForTopStory(lastAsOf, currentMax, lang)
          fresh     = TopStorySelector
                        .selectTopN(articlesL, FallbackPoolConfig.PoolSize)
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
          score                   = (FallbackPoolConfig.PoolSize - idx) * 10,
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
                  s"dispatch(pool): android claim lost for lang=$language candidate.id=${cand.id} — retrying"
                )
                attempt(remaining - 1)
              case _ =>
                articleRepository.findById(cand.representativeArticleId).map {
                  case Some(article) =>
                    Some(PoolPick(candidate = cand, title = article.title))
                  case None =>
                    logger.warn(
                      s"dispatch(pool): android claimed candidate.id=${cand.id} but article.id=${cand.representativeArticleId} " +
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
    currentMax:      Option[Long],
    newArticles:     Int,
    picksByLanguage: Map[String, PoolPick],
    runId:           UUID,
    now:             OffsetDateTime
  ): Either[Throwable, DispatchOutcome] = {
    val notifMessages: Map[String, String] = picksByLanguage.flatMap {
      case (lang, pick) =>
        CountPhrases.get(lang).map { suffixTmpl =>
          lang -> s"${pick.title} — ${suffixTmpl.format(newArticles)}"
        }
    }

    if (notifMessages.isEmpty) {
      logger.warn(
        s"dispatch(pool): android claimed ${picksByLanguage.size} picks but zero composed messages " +
          s"(no CountPhrases match). runId=$runId freq=$frequency"
      )
      Right(DispatchOutcome.NoFreshTopStory)
    } else {
      val anchor = picksByLanguage.values.head
      val perLanguageMeta = Json.fromFields(picksByLanguage.toList.sortBy(_._1).map { case (lang, p) =>
        lang -> Json.obj(
          "candidate_id"        -> p.candidate.id.asJson,
          "article_id"          -> p.candidate.representativeArticleId.asJson,
          "rank"                -> p.candidate.rank.asJson,
          "tier"                -> p.candidate.selectionTier.asJson,
          "selection_metadata"  -> p.candidate.selectionMetadata
        )
      })
      val payload = TopStoryPayload(
        representativeArticleId = anchor.candidate.representativeArticleId,
        topNews                 = Json.obj(),
        notificationMessages    = notifMessages,
        selectionTier           = anchor.candidate.selectionTier,
        selectionMetadata       = Json.obj(
          "fallback_pool_enabled" -> true.asJson,
          "run_id"                -> runId.toString.asJson,
          "platform"              -> "android".asJson,
          "per_language"          -> perLanguageMeta
        )
      )

      for {
        topId          <- topSummaryRepository.insert(payload)
        _              <- topSummaryRepository.markDispatched(topId, now)
        (sent, failed)  = sendByLanguage(client, frequency, notifMessages)
        _              <- dispatchRepository.recordDispatch(
                            frequency     = frequency,
                            asOfArticleId = currentMax,
                            newArticles   = newArticles,
                            sent          = sent,
                            failed        = failed,
                            title         = s"top_summary=$topId",
                            body          = notifMessages.keys.toList.sorted.mkString(","),
                            topSummaryId  = Some(topId)
                          )
        _ = logger.info(
              s"dispatch(pool): android runId=$runId freq=$frequency " +
                s"languages=${picksByLanguage.keys.toList.sorted.mkString(",")} " +
                s"ranks=${picksByLanguage.toList.sortBy(_._1).map { case (l, p) => s"$l:${p.candidate.rank}" }.mkString(",")} " +
                s"sent=$sent failed=$failed newArticles=$newArticles"
            )
      } yield DispatchOutcome.Sent(DispatchResponse(sent, failed, newArticles))
    }
  }

  private case class PoolPick(
    candidate: NotificationCandidatePicked,
    title:     String
  )

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
