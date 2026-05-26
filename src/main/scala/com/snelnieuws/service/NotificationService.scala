package com.snelnieuws.service

import com.snelnieuws.model.{
  BroadcastEnvResult,
  BroadcastResponse,
  DispatchResponse,
  NotificationCandidateInsert,
  NotificationCandidatePicked,
  SubscribeRequest,
  TopStoryPayload
}
import com.snelnieuws.repository.{
  ArticleRepository,
  FeatureFlagRepository,
  NotificationCandidateRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository,
  TopSummaryRepository
}
import io.circe.Json
import io.circe.syntax._
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

/** V29 fallback-pool tunables.
  *
  * `FallbackPoolEnabled` gates the entire per-language pool flow —
  * when off, dispatch is byte-for-byte identical to the pre-V29
  * legacy path. PoolSize (N=4) and TtlHours / DedupHours (12 / 24)
  * match the user-approved design.
  */
object FallbackPoolConfig {
  val FlagName: String       = "notifications_fallback_pool_enabled"
  val PoolSize: Int          = 4
  val TtlHours: Long         = 12L
  val DedupHours: Long       = 24L
  /** Same 7 ISO codes used by `notification_subscriptions.notification_language`
    * (V25 CHECK) and the CountPhrases map. */
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
  dispatchRepository: NotificationDispatchRepository,
  featureFlagRepository: FeatureFlagRepository,
  topSummaryRepository: TopSummaryRepository,
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
                          composeAndSendInline(
                            client = c,
                            frequency = frequency,
                            environment = environment,
                            lastAsOf = lastAsOf,
                            currentMax = currentMax,
                            newArticles = newArticles
                          )
                        }
        } yield outcome
    }
  }

  /** 2026-05-24 refactor: inline top-story SELECTION + DISPATCH.
    *
    * Replaces the pre-fix flow where `select_top_story_main` ran on
    * the GPU (snelmind dstack) and published the top_summary via
    * Kafka, which caused (a) spot-instance interruptions to produce
    * duplicate top_summary rows, (b) the dispatch path to use stale
    * top_summaries from hours-old snelmind runs, and (c) cost.
    *
    * New flow runs entirely in the dispatch request:
    *   1. Load the (lastAsOf, currentMax] window of articles from
    *      the same DB this service already uses — no remote calls,
    *      no Milvus.
    *   2. TopStorySelector.selectFromWindow → 3-tier heuristic pick.
    *      None means "no viable top story in this window" → degrade
    *      to NoFreshTopStory exactly like the old path did when
    *      snelmind hadn't shipped anything fresh.
    *   3. For each language present in `articles` rows of the picked
    *      URL, take that row's localized title and append the
    *      static "— N new articles" suffix from COUNT_PHRASES. No
    *      LLM call. Subscribers whose language isn't in the
    *      multi-language summary set just don't get this dispatch
    *      (strict no-fallback per user direction).
    *   4. INSERT a top_summary row for AUDIT (so we can still
    *      reconstruct "which story was sent at 16:00 yesterday").
    *      Marked dispatched_at immediately because we're sending NOW.
    *   5. sendByLanguage + recordDispatch — unchanged.
    *
    * Failure modes:
    *   - selector returns None → NoFreshTopStory + DON'T advance the
    *     watermark (so the next watcher tick can retry; same as old).
    *   - findTitlesByUrl returns empty → degrade to NoFreshTopStory.
    *     Shouldn't happen since the rep URL was just queried, but
    *     defensive.
    */
  private def composeAndSendInline(
    client: ApnsMessagingService,
    frequency: Option[Int],
    environment: String,
    lastAsOf: Option[Long],
    currentMax: Option[Long],
    newArticles: Int
  ): Either[Throwable, DispatchOutcome] =
    for {
      flagOn  <- featureFlagRepository.isEnabled(FallbackPoolConfig.FlagName)
      outcome <- if (flagOn)
                   composeAndSendInlineWithPool(client, frequency, environment, lastAsOf, currentMax, newArticles)
                 else
                   composeAndSendInlineLegacy(client, frequency, environment, lastAsOf, currentMax, newArticles)
    } yield outcome

  /** Pre-V29 dispatch path. Single top story selected from the EN
    * article window, localized to every language via the URL join,
    * dispatched as one APNs fan-out. Returns NoFreshTopStory when the
    * 3-tier selector finds nothing — the watcher skips the fire.
    *
    * Kept verbatim from the 2026-05-24 inline refactor so the flag-off
    * code path is byte-for-byte identical to the previous behaviour. */
  private def composeAndSendInlineLegacy(
    client: ApnsMessagingService,
    frequency: Option[Int],
    environment: String,
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
              s"env=$environment freq=$frequency newArticles=$newArticles"
          )
          Right(DispatchOutcome.NoFreshTopStory)
        case Some(sel) =>
          articleRepository.findTitlesByUrl(sel.representativeUrl).flatMap {
            case empty if empty.isEmpty =>
              logger.warn(
                s"dispatch: rep article url=${sel.representativeUrl} has 0 title rows; " +
                  s"degrading to NoFreshTopStory. env=$environment freq=$frequency"
              )
              Right(DispatchOutcome.NoFreshTopStory)
            case titlesByLang =>
              val notifMessages = composeNotificationMessages(titlesByLang, newArticles)
              val payload = TopStoryPayload(
                representativeArticleId = sel.representativeArticleId,
                topNews                 = Json.obj(), // unused in the inline path
                notificationMessages    = notifMessages,
                selectionTier           = sel.tier.code,
                selectionMetadata       = sel.selectionMetadata
              )
              for {
                topId    <- topSummaryRepository.insert(payload)
                _        <- topSummaryRepository.markDispatched(topId, OffsetDateTime.now())
                (sent, failed) = sendByLanguage(client, frequency, environment, notifMessages)
                _        <- dispatchRepository.recordDispatch(
                              frequency      = frequency,
                              environment    = environment,
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

  /** V29 fallback-pool dispatch path. Per-language candidate pool
    * (top N picks from each language's article window). For each
    * supported language:
    *
    *   1. Build a fresh pool of up to N=4 candidates from that
    *      language's articles in the (lastAsOf, currentMax] window,
    *      filtered against the dedup set (article ids consumed in the
    *      last 24 h across any language).
    *   2. Persist that pool under a shared `run_id` (rank 1..N).
    *   3. Atomically claim the best pickable candidate for this
    *      language — rank ASC, created_at DESC — which yields rank-1
    *      of the just-inserted pool if any, otherwise the highest-
    *      ranked unconsumed leftover from a prior run (TTL 12 h).
    *   4. Resolve its title via `articles.findById`.
    *
    * Languages that yield neither a fresh nor a fallback candidate
    * are dropped — `notification_messages` only carries the languages
    * that successfully resolved a candidate. If ALL languages drop,
    * the dispatch returns NoFreshTopStory (same downstream behaviour
    * as the legacy path's "skip the fire" case).
    *
    * Race safety: `markConsumed` runs BEFORE send. A concurrent
    * dispatch attempting to pick the same row gets count=0 and falls
    * to the next rank. Failed sends do not refund the claim — that
    * is a deliberate cost vs. the risk of double-sending.
    *
    * Audit: a single `top_summaries` row is INSERTed; its
    * `selection_metadata` carries the per-language picks (article id,
    * tier, rank, source) so the dispatch can be reconstructed later.
    */
  private def composeAndSendInlineWithPool(
    client: ApnsMessagingService,
    frequency: Option[Int],
    environment: String,
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
            s"env=$environment freq=$frequency newArticles=$newArticles"
        )
        Right(DispatchOutcome.NoFreshTopStory)
      } else {
        dispatchFromPoolPicks(
          client          = client,
          frequency       = frequency,
          environment     = environment,
          currentMax      = currentMax,
          newArticles     = newArticles,
          picksByLanguage = picksByLanguage,
          runId           = runId,
          now             = now
        )
      }
    } yield outcome
  }

  /** For each supported language: build the fresh pool (if any), then
    * atomically claim the best pickable candidate. Returns a map of
    * language → (picked, resolved title, source-of-pick). Languages
    * that resolve to None are simply omitted. */
  private def collectPerLanguagePicks(
    runId:      UUID,
    lastAsOf:   Option[Long],
    currentMax: Option[Long],
    dedupSet:   Set[Long],
    expiresAt:  OffsetDateTime,
    now:        OffsetDateTime
  ): Either[Throwable, Map[String, PoolPick]] = {
    // Fold over the language list short-circuiting on any DB error,
    // accumulating successful picks into a Map.
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
  }

  /** Persist the fresh top-N pool for one language under `runId`.
    * No-op when `fresh` is empty (zero strict candidates and zero
    * Tier 4 fillers — happens only when the language has no articles
    * in the window). */
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

  /** Claim the best pickable candidate for `language` and resolve its
    * title. `markConsumed` returns 0 when another tick raced us — we
    * retry once with a fresh `findPickable` (which now sees the next
    * rank). Two retries are enough: only two ticks can ever race the
    * same row (the underlying constraint is the watcher's
    * max_active_runs=1 plus a possible manual trigger). */
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
                  s"dispatch(pool): claim lost for lang=$language candidate.id=${cand.id} — retrying"
                )
                attempt(remaining - 1)
              case _ =>
                articleRepository.findById(cand.representativeArticleId).map {
                  case Some(article) =>
                    Some(PoolPick(candidate = cand, title = article.title))
                  case None =>
                    logger.warn(
                      s"dispatch(pool): claimed candidate.id=${cand.id} but article.id=${cand.representativeArticleId} " +
                        s"is missing (cleanup race?); skipping lang=$language"
                    )
                    None
                }
            }
        }

    attempt(3)
  }

  /** Run after we know we have at least one language's pick. Composes
    * per-language messages, INSERTs an audit `top_summary`, sends
    * APNs by language, records the dispatch. */
  private def dispatchFromPoolPicks(
    client:          ApnsMessagingService,
    frequency:       Option[Int],
    environment:     String,
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
      // We picked candidates but none have a matching CountPhrases entry
      // — defensive only (CountPhrases covers all 7 supported langs).
      logger.warn(
        s"dispatch(pool): claimed ${picksByLanguage.size} picks but zero composed messages " +
          s"(no CountPhrases match). runId=$runId env=$environment freq=$frequency"
      )
      Right(DispatchOutcome.NoFreshTopStory)
    } else {
      // Pick an arbitrary anchor for the legacy single-story audit
      // fields (representativeArticleId, selectionTier). The per-
      // language details live in selection_metadata.
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
          "per_language"          -> perLanguageMeta
        )
      )

      for {
        topId          <- topSummaryRepository.insert(payload)
        _              <- topSummaryRepository.markDispatched(topId, now)
        (sent, failed)  = sendByLanguage(client, frequency, environment, notifMessages)
        _              <- dispatchRepository.recordDispatch(
                            frequency     = frequency,
                            environment   = environment,
                            asOfArticleId = currentMax,
                            newArticles   = newArticles,
                            sent          = sent,
                            failed        = failed,
                            title         = s"top_summary=$topId",
                            body          = notifMessages.keys.toList.sorted.mkString(","),
                            topSummaryId  = Some(topId)
                          )
        _ = logger.info(
              s"dispatch(pool): runId=$runId env=$environment freq=$frequency " +
                s"languages=${picksByLanguage.keys.toList.sorted.mkString(",")} " +
                s"ranks=${picksByLanguage.toList.sortBy(_._1).map { case (l, p) => s"$l:${p.candidate.rank}" }.mkString(",")} " +
                s"sent=$sent failed=$failed newArticles=$newArticles"
            )
      } yield DispatchOutcome.Sent(DispatchResponse(sent, failed, newArticles))
    }
  }

  /** Internal pair carrying a claimed candidate plus the resolved
    * title for its language. Title lookup happens at claim-time so
    * the dispatcher doesn't need to re-query the article rows. */
  private case class PoolPick(
    candidate: NotificationCandidatePicked,
    title:     String
  )

  /** Static per-language "{n} ... articles" suffix. Mirrors the
    * Python COUNT_PHRASES dict from snelmind/agents/top_story.py —
    * keeping them in sync is a manual chore but the set is small + rarely
    * changes (7 ISO codes). */
  private val CountPhrases: Map[String, String] = Map(
    "en" -> "%d new articles",
    "nl" -> "%d nieuwe artikelen",
    "de" -> "%d neue Artikel",
    "fr" -> "%d nouveaux articles",
    "it" -> "%d nuovi articoli",
    "es" -> "%d artículos nuevos",
    "pl" -> "%d nowych artykułów"
  )

  /** Combine each language's localized title with its "— N new
    * articles" suffix. Languages without a suffix template are
    * skipped (defensive; not expected for the 7 supported codes). */
  private def composeNotificationMessages(
    titlesByLang: Map[String, String],
    newArticles: Int
  ): Map[String, String] =
    titlesByLang.flatMap { case (lang, title) =>
      CountPhrases.get(lang).map { suffixTmpl =>
        lang -> s"$title — ${suffixTmpl.format(newArticles)}"
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
