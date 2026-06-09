package com.snelnieuws.api

import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.model.{ArticleV3Row, Categories, CategoryNames, EmbedQueryRequest, Languages, UiStrings, UserEvent, UserEventsBatch}
import com.snelnieuws.repository.AppClientRepository
import com.snelnieuws.model.ArticleV3Row
import com.snelnieuws.service.{ArticleService, IngestionApiClient, IngestionApiError, NotificationService, SemanticQueryService, UserService}
import com.snelnieuws.util.CursorCodec
import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

import java.time.format.DateTimeFormatter
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID
import scala.util.Try

/** v3 article surface. Same X-Client / X-Client-Key gate as v2. Differs from
  * v2 in three ways:
  *   - `country` is a required query param on every list endpoint.
  *   - Pagination is cursor-based (opaque base64); responses include
  *     `next_cursor` and `has_more` instead of a flat array.
  *   - Each article carries `is_local` (matched against the caller's
  *     country via `articles.country` or `articles.shared_countries`).
  *
  * Auth-required user routes, notifications, client registration, and config
  * remain on v2. v3 is article-fetch only.
  */
class NewsServletV3(
  articleRepository: com.snelnieuws.repository.ArticleRepository,
  articleService: ArticleService,
  notificationService: NotificationService,
  userService: UserService,
  appClientRepository: AppClientRepository,
  firebaseVerifier: FirebaseTokenVerifier,
  /** Used to resolve relative image paths (e.g. `/v2/images/...`) into
    * absolute URLs in the v3 response — mirrors what `ArticleService.
    * absolutiseStoredUrl` does for the v1/v2 paths. Without this, the
    * apps' AsyncImage / Coil fail because `urlToImage` carries only a
    * path with no host. */
  imagesPublicBaseUrl: String,
  semanticQueryService: SemanticQueryService,
  ingestionApiClient: IngestionApiClient,
  // Backing repo for eulang_articles, used to route GET /v3/articles/:id
  // for blended eulang items (id >= ArticleService.EulangIdOffset). Optional
  // so test harnesses construct unchanged; None → behaves articles-only.
  eulangArticleRepository: Option[com.snelnieuws.repository.ArticleRepository] = None,
  // First-party engagement-event sink (POST /v3/events). Optional so test
  // harnesses construct unchanged; None → the endpoint accepts and no-ops.
  eventRepository: Option[com.snelnieuws.repository.EventRepository] = None,
  // Served-slate log (recommender Phase-0). Optional so test harnesses
  // construct unchanged; None → feed serves are not logged. Best-effort: a
  // logging failure never affects the feed response.
  feedServeRepository: Option[com.snelnieuws.repository.FeedServeRepository] = None
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[NewsServletV3])

  private val ClientHeaderRe = """^(ios|android)/[^\s]+$""".r
  private val CountryRe = """^[a-z]{2}$""".r
  // Same shape as CountryRe — a 2-letter lowercase ISO 639-1 primary
  // tag. Region subtags ('nl-NL') are stripped before this regex runs.
  private val LanguageRe = """^[a-z]{2}$""".r

  // Default and ceiling page sizes. Anything bigger than the max would make
  // the cursor pagination meaningless and put pressure on the index.
  private val DefaultLimit = 20
  private val MaxLimit     = 50

  before() {
    contentType = formats("json")

    val xClient = Option(request.getHeader("X-Client")).map(_.trim).getOrElse("")
    if (!ClientHeaderRe.pattern.matcher(xClient).matches()) {
      halt(Forbidden(Map("error" -> "missing or invalid X-Client header")))
    }

    val keyStr = Option(request.getHeader("X-Client-Key")).map(_.trim).getOrElse("")
    val keyOpt = Try(UUID.fromString(keyStr)).toOption
    keyOpt match {
      case None =>
        halt(Unauthorized(Map("error" -> "missing or malformed X-Client-Key")))
      case Some(uuid) =>
        appClientRepository.isActive(uuid) match {
          case Right(true) =>
            appClientRepository.markSeen(uuid)
          case Right(false) =>
            halt(Unauthorized(Map("error" -> "unknown or revoked X-Client-Key")))
          case Left(e) =>
            logger.error(s"app_client lookup failed for $uuid: ${e.getMessage}", e)
            halt(InternalServerError(Map("error" -> "client lookup failed")))
        }
    }
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  // ───────────────────────────── List endpoints ──────────────────────────

  get("/feed")          { handleList() }
  get("/everything")    { handleList() }
  get("/top-headlines") { handleList() }

  private def handleList(): Any = {
    val country = params.get("country").map(_.trim.toLowerCase).getOrElse("")
    if (!CountryRe.pattern.matcher(country).matches()) {
      return BadRequest(Map("error" -> "country is required and must be a 2-letter lowercase code"))
    }

    val language = resolveLanguage() match {
      case Right(l)  => l
      case Left(msg) => return BadRequest(Map("error" -> msg))
    }

    val limit = params.get("limit")
      .flatMap(s => Try(s.toInt).toOption)
      .map(n => math.min(math.max(n, 1), MaxLimit))
      .getOrElse(DefaultLimit)

    val categories = params.get("categories")
      .map(_.split(',').toList.map(_.trim.toLowerCase).filter(_.nonEmpty))
      .getOrElse(Nil)
      .filter(Categories.all.toSet.contains)
      .distinct

    val cursorOpt: Option[(Instant, Long)] = params.get("cursor").map(_.trim).filter(_.nonEmpty) match {
      case None => None
      case Some(s) =>
        CursorCodec.decode(s) match {
          case Right(decoded) => Some(decoded)
          case Left(_)        => return BadRequest(Map("error" -> "invalid cursor"))
        }
    }

    // Personalised-feed gate: when `personalised_feed_enabled` is on
    // AND the client is authenticated (which v3 always is — the
    // before() block above hard-fails non-UUID X-Client-Key), route
    // through ArticleService.personalisedV3Fetch. The personalised
    // path:
    //
    //   - Ignores the cursor (server-side served_ids set IS the
    //     pagination state — a published_at cursor would double-count
    //     exclusions).
    //   - Returns `next_cursor = null` so the client doesn't try to
    //     paginate; the next /v3/feed call will serve the next
    //     unseen page automatically.
    //   - Falls through to findV3 on the legacy path (flag off OR
    //     no clientId) so behaviour is identical when the feature is
    //     disabled.
    //
    // This restores parity with NewsServletV2's article-service
    // routing — when the v3 migration shipped (commit 67c4a89) it
    // called findV3 directly, accidentally cutting v3 off from the
    // server-side served-id tracking.
    // Feed blend ratio: local (country-match) items per 1 other, default 3
    // (→ 3:1). Clamped 1..20. Only meaningful when the eulang blend is on.
    val localPer = params.get("ratio")
      .flatMap(s => Try(s.toInt).toOption)
      .map(n => math.min(math.max(n, 1), 20))
      .getOrElse(ArticleService.DefaultLocalPerCycle)

    val cidOpt = clientIdFromHeader().toOption
    val result: Either[Throwable, (List[ArticleService.SourcedArticleV3], Boolean)] =
      if (articleService.personalisedV3Available(cidOpt)) {
        articleService.personalisedV3Fetch(
          cidOpt, country, language, categories, limit,
          localPer = localPer, otherPer = ArticleService.DefaultOtherPerCycle
        )
      } else {
        // Legacy path is articles-only (eulang=false); wrap so the response
        // mapping is uniform.
        articleRepository.findV3(country, language, categories, cursorOpt, limit)
          .map { case (rows, more) => (rows.map(ArticleService.SourcedArticleV3(_, eulang = false)), more) }
      }

    result match {
      case Right((rows, hasMore)) =>
        val articles = rows.map(toApiSourced)
        // Recommender Phase-0: log the served slate (best-effort). The public
        // ids here are exactly what the apps report back in user_events, so
        // reward joins are direct. Never affects the response.
        for {
          repo <- feedServeRepository
          cid  <- cidOpt
          if articles.nonEmpty
        } repo.logServe(
          clientId = cid,
          items    = articles.map(_.id),
          listName = if (categories.isEmpty) None else Some(categories.mkString(",")),
          country  = Some(country),
          language = Some(language)
        )
        // Cursor is meaningful ONLY on the legacy path. The
        // personalised path uses the served_ids set; sending a
        // next_cursor there would invite the client to paginate
        // into stale rows. Falsy next_cursor keeps the client on
        // the "call /v3/feed again, get fresh content" rhythm.
        val nextCursor =
          if (!articleService.personalisedV3Available(cidOpt) && hasMore && rows.nonEmpty) {
            val last = rows.last.row
            Some(CursorCodec.encode(last.publishedAt.toInstant, last.id))
          } else None
        PaginatedArticlesResponseV3(
          articles    = articles,
          next_cursor = nextCursor,
          has_more    = hasMore
        )
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load articles: ${e.getMessage}"))
    }
  }

  // ───────────────────────────── Single article ──────────────────────────

  get("/articles/:id") {
    val country = params.get("country").map(_.trim.toLowerCase).getOrElse("")
    if (!CountryRe.pattern.matcher(country).matches()) {
      BadRequest(Map("error" -> "country is required and must be a 2-letter lowercase code"))
    } else {
      resolveLanguage() match {
        case Left(msg) =>
          BadRequest(Map("error" -> msg))
        case Right(language) =>
          // Decode the gate id: "e123" → eulang_articles, "123" → articles.
          // The raw id is what the repos query; the namespaced id only ever
          // exists at this boundary.
          ArticleService.decodePublicId(params("id")) match {
            case None =>
              BadRequest(Map("error" -> s"Invalid ID format: '${params("id")}'"))
            case Some((isEulang, rawId)) =>
              val lookup: Either[Throwable, Option[ArticleV3Row]] =
                if (isEulang)
                  eulangArticleRepository
                    .map(_.findV3ById(country, language, rawId))
                    .getOrElse(Right(None))
                else
                  articleRepository.findV3ById(country, language, rawId)
              lookup match {
                case Right(Some(row)) =>
                  toApiSourced(ArticleService.SourcedArticleV3(row, eulang = isEulang))
                case Right(None)      => NotFound(Map("error" -> "Article not found"))
                case Left(e) =>
                  InternalServerError(Map("error" -> s"Failed to load article: ${e.getMessage}"))
              }
          }
      }
    }
  }

  // ──────────────────────────────── Categories ───────────────────────────
  //
  // BACKWARDS-COMPAT CONTRACT: the `categories` field must remain a JSON
  // array of lowercase slug strings forever — installed apps parse it
  // as `[String]`. The locale-aware payload is added as a sibling
  // `categories_localized: [{code, name}]` field. Old clients ignoring
  // unknown JSON fields (Codable / Moshi / Gson defaults — all tolerant)
  // see no behavior change. CRITICAL: `categories[i]` and
  // `categories_localized[i].code` refer to the same slug at the same
  // index, regardless of locale, so positional cross-referencing works.

  get("/categories") {
    resolveLanguage() match {
      case Right(locale) =>
        Map(
          "categories"            -> Categories.all,
          "categories_localized"  -> CategoryNames.forLocale(locale)
        )
      case Left(msg) =>
        BadRequest(Map("error" -> msg))
    }
  }

  // ──────────────────────────────── Languages ────────────────────────────
  //
  // Same auth gate as /categories. Pure static read — no DB call.
  // Rendering order is constant across locales; only the display
  // `name` values change. Unknown but well-formed locales fall back to
  // the English row (no error). Malformed locales (e.g. ?language=XYZ)
  // 400, consistent with the list endpoints.

  get("/languages") {
    resolveLanguage() match {
      case Right(locale) =>
        Map("languages" -> Languages.forLocale(locale))
      case Left(msg) =>
        BadRequest(Map("error" -> msg))
    }
  }

  // ──────────────────────────────── i18n ─────────────────────────────────
  //
  // Backend-driven UI translation bundle for the iOS + Android clients.
  // Same locale-resolution + fallback semantics as /categories and
  // /languages: unknown but well-formed locales fall back to the
  // English row; malformed locales 400. Apps fetch on startup + on
  // language switch, cache the response, and look up strings via their
  // L10n helper. Adding a language tomorrow = extend UiStrings.scala +
  // redeploy; no app release needed.

  get("/i18n") {
    resolveLanguage() match {
      case Right(locale) =>
        Map("strings" -> UiStrings.forLocale(locale))
      case Left(msg) =>
        BadRequest(Map("error" -> msg))
    }
  }

  // ────────────────────── Semantic-query CRUD endpoints ──────────────────
  //
  // POST   /v3/embeddings/query  — embed the user's query text via the
  //                                 ingestion-api bridge, persist the
  //                                 row keyed on client_id, return the
  //                                 1024-dim vector so the app caches
  //                                 the same value the server stored.
  // GET    /v3/embeddings/query  — fetch the saved row by client_id;
  //                                 if absent AND X-User-Id is present,
  //                                 try cross-device sync via user_id.
  // DELETE /v3/embeddings/query  — drop the row.
  //
  // semantic_search/backend_tasks.txt §8.

  post("/embeddings/query") {
    clientIdFromHeader() match {
      case Left(msg)       => BadRequest(Map("error" -> msg))
      case Right(clientId) =>
        val req = Try(parsedBody.extract[EmbedQueryRequest]).toOption
        val text = req.map(_.text).map(_.trim).getOrElse("")
        if (text.isEmpty || text.length > 200) {
          BadRequest(Map(
            "error"  -> "text must be non-empty and <= 200 chars",
            "length" -> text.length
          ))
        } else {
          val userId = userIdFromHeader()
          semanticQueryService.setQuery(
            clientId  = clientId,
            userId    = userId,
            queryText = text,
            language  = req.flatMap(_.language)
          ) match {
            case Right(emb) =>
              Map(
                "embedding" -> emb.toList,
                "dim"       -> emb.length,
                "model"     -> "multilingual-e5-large"
              )
            case Left(e: IngestionApiError) =>
              logger.warn(s"embed bridge returned ${e.status}: ${e.body.take(200)}")
              ServiceUnavailable(Map(
                "error"  -> "embedding_service_unavailable",
                "detail" -> e.getMessage
              ))
            case Left(e) =>
              logger.error(s"embed bridge failed: ${e.getMessage}", e)
              InternalServerError(Map("error" -> e.getMessage))
          }
        }
    }
  }

  // First-party engagement events. The apps batch impression/open/close/
  // read_engaged/swipe/share events and flush them here; client_id comes from
  // the X-Client-Key gate. Stored real-time in user_events (Phase 1). Best-
  // effort: unknown event types are dropped, and the call still 200s.
  post("/events") {
    clientIdFromHeader() match {
      case Left(msg)       => BadRequest(Map("error" -> msg))
      case Right(clientId) =>
        val events = Try(parsedBody.extract[UserEventsBatch]).toOption.map(_.events).getOrElse(Nil)
        if (events.isEmpty) {
          BadRequest(Map("error" -> "events must be a non-empty array"))
        } else if (events.size > UserEvent.MaxBatchSize) {
          BadRequest(Map("error" -> s"too many events (max ${UserEvent.MaxBatchSize})"))
        } else {
          eventRepository.map(_.insertBatch(clientId, events)) match {
            case Some(Right(n)) => Map("accepted" -> n)
            case None           => Map("accepted" -> 0) // sink not wired (tests)
            case Some(Left(e)) =>
              logger.error(s"Failed to record events: ${e.getMessage}", e)
              InternalServerError(Map("error" -> "failed to record events"))
          }
        }
    }
  }

  get("/embeddings/query") {
    clientIdFromHeader() match {
      case Left(msg)       => BadRequest(Map("error" -> msg))
      case Right(clientId) =>
        val userId = userIdFromHeader()
        // 1. Try by client_id first (this device).
        // 2. If not found AND user_id present, try by user_id
        //    (cross-device sync).
        val rowOpt = semanticQueryService.getQuery(clientId) match {
          case Right(Some(r)) => Some(r)
          case _ =>
            userId.flatMap { uid =>
              semanticQueryService.getQueryByUser(uid).toOption.flatten
            }
        }
        rowOpt match {
          case None =>
            NotFound(Map("error" -> "no_saved_query"))
          case Some(r) =>
            Map(
              "query_text" -> r.queryText,
              "embedding"  -> r.embedding.toList,
              "language"   -> r.language,
              "updated_at" -> r.updatedAt.toString
            )
        }
    }
  }

  delete("/embeddings/query") {
    clientIdFromHeader() match {
      case Left(msg)       => BadRequest(Map("error" -> msg))
      case Right(clientId) =>
        semanticQueryService.deleteQuery(clientId) match {
          case Right(n) => Map("deleted" -> n)
          case Left(e)  => InternalServerError(Map("error" -> e.getMessage))
        }
    }
  }

  // ─────────────────────── Semantic feed (UNION + fallback) ──────────────
  //
  // POST /v3/feed/semantic — accepts the cached embedding from the app
  // (so we don't re-embed on every feed open), runs Milvus kNN via the
  // bridge, joins URLs to the local articles table, optionally unions
  // with user-selected categories, applies the language filter, and
  // returns the merged result in chronological order.
  //
  // Falls back to the default feed (semantic_fallback=true) when the
  // bridge returned no matches OR was unavailable. See
  // semantic_search/backend_tasks.txt §9.

  post("/feed/semantic") {
    val country = params.get("country").map(_.trim.toLowerCase).getOrElse("")
    if (!CountryRe.pattern.matcher(country).matches())
      BadRequest(Map("error" -> "country is required and must be a 2-letter lowercase code"))
    else {
      // Parse the body once, up-front, so we can use body.language for
      // language resolution (the iOS + Android clients send language
      // here rather than as a URL query — pre-fix, the server only
      // consulted resolveLanguage() which falls back to "en" when no
      // ?language= query is present, and the body's language was
      // silently dropped → Dutch users saw English articles).
      val body = Try(parsedBody.extract[SemanticFeedRequest]).toOption.getOrElse(
        SemanticFeedRequest(embedding = Nil, categories = None, limit = None,
                            cursor = None, language = None)
      )
      // Body takes precedence; URL/Accept-Language is the fallback so
      // a future client that only sends ?language= still works.
      val languageE: Either[String, String] = body.language
        .map(_.trim.toLowerCase).filter(_.nonEmpty)
        .map { l =>
          if (LanguageRe.pattern.matcher(l).matches() && Languages.codes.contains(l)) Right(l)
          else Left(s"language must be one of: ${Languages.codes.mkString(",")}")
        }
        .getOrElse(resolveLanguage())

      languageE match {
        case Left(msg)       => BadRequest(Map("error" -> msg))
        case Right(language) =>
        val embedding: Array[Float] = body.embedding.iterator.map(_.toFloat).toArray
        if (embedding.length != 1024) {
          BadRequest(Map("error" -> "embedding must be 1024-dim", "got" -> embedding.length))
        } else {
          val limit = body.limit
            .map(n => math.min(math.max(n, 1), MaxLimit))
            .getOrElse(DefaultLimit)
          val categories = body.categories.getOrElse(Nil)
            .map(_.trim.toLowerCase).filter(_.nonEmpty)
            .filter(Categories.all.toSet.contains)
            .distinct
          val cursorOpt: Option[(Instant, Long)] = body.cursor.flatMap(_.trim match {
            case ""  => None
            case raw => CursorCodec.decode(raw).toOption
          })

          // 1. Semantic matches via the bridge.
          //
          // Neither language nor country is passed as a Milvus filter.
          //
          // Milvus's snelmind_articles collection was populated by the
          // raw-article ingestion path which records only what the
          // upstream firehose hands it — in practice nearly every row
          // has language='en' and country=null (the geo-IP tagger
          // doesn't run for articles ingested from RSS feeds, which is
          // most of them). AND-ing either filter at the Milvus level
          // drops the match count to zero for anyone whose picker is
          // not exactly the magic majority value. The 2026-05-22 user
          // report ("No news for your query — China, US, Trump, Modi"
          // with picker=Dutch+NL) was the country=nl filter killing
          // an otherwise healthy 4-match result set; the prior commit
          // 29db2b8 fixed the language half of the same bug.
          //
          // Instead, let Milvus return the top-K nearest URLs
          // unconstrained. `findV3ByUrls` below already applies the
          // language filter at the PG JOIN level (defence in depth +
          // semantic correctness for the user's picker); country is
          // handled by the merged result's `is_local` flag rather
          // than as a hard filter. Net effect: the user sees Milvus
          // hits that DO have a localized counterpart in `articles`;
          // hits without one drop out at the JOIN and we cleanly
          // fall back to the default feed — which is the correct
          // behaviour when no localized content matches the query.
          val matchesE = ingestionApiClient.searchSemantic(
            embedding = embedding,
            language  = None,
            country   = None,
            limit     = MaxLimit,
            minScore  = 0.70
          )

          val (semanticUrls, semanticFallback, fallbackReason) = matchesE match {
            case Right(matches) if matches.nonEmpty =>
              (matches.map(_.url).toSet, false, None)
            case Right(_) =>
              (Set.empty[String], true, Some("no_matches"))
            case Left(_) =>
              (Set.empty[String], true, Some("service_unavailable"))
          }

          val semanticArticles: List[ArticleV3Row] =
            if (semanticUrls.isEmpty) Nil
            else articleRepository
              .findV3ByUrls(semanticUrls.toList, country, language)
              .getOrElse(Nil)

          val categoryArticles: List[ArticleV3Row] =
            if (categories.isEmpty) Nil
            else articleRepository
              .findV3(country, language, categories, cursorOpt, limit)
              .map(_._1).getOrElse(Nil)

          val merged: List[ArticleV3Row] =
            (semanticArticles ++ categoryArticles)
              .groupBy(_.id).values.map(_.head).toList
              .sortBy(_.publishedAt)(Ordering[OffsetDateTime].reverse)
              .take(limit)

          val (finalArticles, finalFallback, finalReason) =
            if (merged.isEmpty && semanticFallback) {
              articleRepository.findV3(country, language, Nil, cursorOpt, limit)
                .map(_._1) match {
                  case Right(rows) => (rows, true, fallbackReason)
                  case Left(_)     => (List.empty[ArticleV3Row], true, Some("service_unavailable"))
                }
            } else {
              (merged, semanticFallback, fallbackReason)
            }

          PaginatedSemanticResponse(
            articles                 = finalArticles.map(toApi),
            has_more                 = false,
            semantic_fallback        = finalFallback,
            semantic_fallback_reason = finalReason
          )
        }
      }
    }
  }

  // ─────────────────────────────── Internals ─────────────────────────────

  private val publishedAtFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

  /** Encode a sourced row for the wire: eulang items get the "e{id}"
    * namespaced id (so they round-trip to the eulang table on detail
    * fetch); articles are unchanged. The id transform lives only here at
    * the gate — the rest of the system works on raw per-table ids. */
  private def toApiSourced(s: ArticleService.SourcedArticleV3): ArticleV3 =
    toApi(s.row).copy(id = ArticleService.encodePublicId(s.eulang, s.row.id))

  private def toApi(row: ArticleV3Row): ArticleV3 =
    ArticleV3(
      id          = row.id.toString,
      author      = row.author,
      title       = row.title,
      description = row.description,
      url         = row.url,
      urlToImage  = absolutiseImage(row.urlToImage),
      publishedAt = publishedAtFmt.format(row.publishedAt),
      content     = row.content,
      category    = row.category,
      country     = row.country,
      // Intentionally always false on the wire: the apps render a "Local"
      // pill whenever is_local is true, and we don't want that badge shown.
      // The real locality flag (row.isLocal) is still computed and used
      // server-side to drive the 3:1 local-first blend + foreign-local
      // down-rank — this only suppresses the client-facing badge.
      is_local    = false,
      language    = row.language
    )

  /** Re-extract the client_id UUID from the X-Client-Key header. The
    * before() block has already validated it as a UUID and verified
    * activity, so this is a parse-only path — we just don't store it
    * across the before/get split in Scalatra. Returns Right(uuid) on
    * happy path (matches what before() let through). */
  private def clientIdFromHeader(): Either[String, UUID] = {
    val raw = Option(request.getHeader("X-Client-Key")).map(_.trim).getOrElse("")
    Try(UUID.fromString(raw)).toOption
      .toRight("X-Client-Key must be a UUID")
  }

  /** Optional X-User-Id header — used by the semantic-query endpoints
    * for cross-device sync via the user_semantic_queries.user_id
    * lookup branch. None when the header is absent or empty. */
  private def userIdFromHeader(): Option[String] =
    Option(request.getHeader("X-User-Id"))
      .map(_.trim)
      .filter(_.nonEmpty)

  /** Resolve the language for this request. Order of precedence:
    *   1. `?language=xx` query param.
    *   2. `Accept-Language` header (primary subtag — `nl-NL` → `nl`,
    *      `fr-FR,en;q=0.9` → `fr`).
    *   3. Default 'en'.
    * Validates the result against the 2-letter lowercase regex. Used
    * by every endpoint that may filter by, render in, or surface a
    * language: handleList, /articles/:id, /categories, /languages. */
  private[api] def resolveLanguage(): Either[String, String] = {
    val raw = params.get("language")
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .orElse(
        Option(request.getHeader("Accept-Language"))
          .map(_.trim.toLowerCase)
          .filter(_.nonEmpty)
          .map(_.split(Array(',', ';', '-')).head.trim)
      )
      .getOrElse("en")
    if (LanguageRe.pattern.matcher(raw).matches()) Right(raw)
    else Left(s"invalid language: '$raw'; must be a 2-letter lowercase code")
  }

  /** Stored values starting with `/` are server-relative paths from the
    * v2 image-cache scheme (`/v2/images/...`); prepend the configured
    * base URL so the response carries an absolute URL. Legacy absolute
    * URLs flow through unchanged. Same logic as
    * `ArticleService.absolutiseStoredUrl`. */
  private def absolutiseImage(stored: Option[String]): Option[String] = stored.map { v =>
    if (v.startsWith("/")) imagesPublicBaseUrl + v else v
  }
}

// Top-level case classes so json4s emits stable field names without needing
// custom serializers. `is_local`, `next_cursor`, `has_more` are intentionally
// snake_case to match the cross-platform JSON convention used by the apps.
case class ArticleV3(
  id: String,
  author: Option[String],
  title: String,
  description: Option[String],
  url: String,
  urlToImage: Option[String],
  publishedAt: String,
  content: Option[String],
  category: Option[String],
  country: Option[String],
  is_local: Boolean,
  language: String
)

case class PaginatedArticlesResponseV3(
  articles: List[ArticleV3],
  next_cursor: Option[String],
  has_more: Boolean
)

/** Body of POST /v3/feed/semantic. `embedding` is mandatory and must
  * be exactly 1024 doubles; the servlet validates length before
  * calling the bridge. `categories` is optional (defaults to []),
  * `limit` defaults to 20 with max 50, `cursor` only meaningful for
  * the category branch (the semantic branch is capped at top-50, no
  * pagination). */
case class SemanticFeedRequest(
  embedding: List[Double],
  categories: Option[List[String]],
  limit: Option[Int],
  cursor: Option[String],
  // Optional. When present, OVERRIDES the URL/Accept-Language
  // resolution path. The iOS + Android clients send language here
  // (matching the rest of /v3/feed/semantic's body-shaped params)
  // rather than as a URL query — before this field existed the
  // server ignored it, defaulted to "en", and Dutch users saw English
  // articles bleeding past the language filter.
  language: Option[String]
)

/** Response shape for /v3/feed/semantic. `has_more` is always false in
  * v1 because the merged set is capped at limit ≤ 50 and there's no
  * meaningful cursor on a UNION of semantic + category. Apps that need
  * pagination should fall back to /v3/feed. */
case class PaginatedSemanticResponse(
  articles: List[ArticleV3],
  has_more: Boolean,
  semantic_fallback: Boolean,
  semantic_fallback_reason: Option[String]
)
