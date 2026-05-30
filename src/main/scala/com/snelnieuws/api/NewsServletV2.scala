package com.snelnieuws.api

import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.model.{
  ArticleCreate,
  Categories,
  CategoriesPayload,
  LastPreferenceResponse,
  NewsFetchResponse,
  RegisterClientRequest,
  SubscribeRequest,
  UpsertUserRequest
}
import com.snelnieuws.repository.AppClientRepository
import com.snelnieuws.service.{ArticleService, NotificationService, UserService}
import org.json4s.{DefaultFormats, Formats, MappingException}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.Try

/** v2 surface. Same routes as v1 (minus the static-HTML pages), plus
  * client registration and an unauth delete-by-deviceId. Every request
  * passes a two-layer gate:
  *
  *   1. X-Client: ios/<version>  — platform attestation. Trivial to spoof
  *      but filters out drive-by scanners.
  *   2. X-Client-Key: <uuid>     — install attestation. UUID is issued by
  *      the app and looked up in app_clients on every call. Exempt only
  *      for POST /clients/register (the bootstrap).
  *
  * v1 stays untouched and unguarded so existing App Store builds keep
  * working. New iOS builds flip baseURL to /v2/ and gain the gates.
  */
class NewsServletV2(
  articleService: ArticleService,
  notificationService: NotificationService,
  userService: UserService,
  appClientRepository: AppClientRepository,
  firebaseVerifier: FirebaseTokenVerifier
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[NewsServletV2])

  // Accepts iOS and Android. Format: `<platform>/<version>` — version is
  // kept lax (any non-empty token) so a routine bundle bump does not
  // require a backend update. Android-specific notification routes live
  // on a separate servlet; the rest of the v2 surface (news, articles,
  // users, categories) is platform-agnostic and shared.
  private val ClientHeaderRe = """^(ios|android)/[^\s]+$""".r

  // Routes that bypass the X-Client-Key check. POST /clients/register is
  // the bootstrap — the app calls it on first launch precisely to obtain
  // a recognised key, so requiring one here would be circular.
  private val KeyExemptPaths: Set[String] = Set("/clients/register")

  before() {
    contentType = formats("json")

    // Layer 1: platform attestation. Reject anything missing or wrong
    // shape with 403. Same check on every route — including /clients/register.
    val xClient = Option(request.getHeader("X-Client")).map(_.trim).getOrElse("")
    if (!ClientHeaderRe.pattern.matcher(xClient).matches()) {
      halt(Forbidden(Map("error" -> "missing or invalid X-Client header")))
    }

    // Layer 2: install attestation. Skipped for register.
    if (!KeyExemptPaths.contains(requestPath)) {
      val keyStr = Option(request.getHeader("X-Client-Key")).map(_.trim).getOrElse("")
      val keyOpt = Try(UUID.fromString(keyStr)).toOption
      keyOpt match {
        case None =>
          halt(Unauthorized(Map("error" -> "missing or malformed X-Client-Key")))
        case Some(uuid) =>
          appClientRepository.isActive(uuid) match {
            case Right(true) =>
              // Best-effort liveness bump. Don't fail the request if it errors.
              appClientRepository.markSeen(uuid)
            case Right(false) =>
              halt(Unauthorized(Map("error" -> "unknown or revoked X-Client-Key")))
            case Left(e) =>
              logger.error(s"app_client lookup failed for $uuid: ${e.getMessage}", e)
              halt(InternalServerError(Map("error" -> "client lookup failed")))
          }
      }
    }
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  private def clientIdFromHeader: Option[UUID] =
    Option(request.getHeader("X-Client-Key"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(s => Try(UUID.fromString(s)).toOption)

  // ────────────────────────────── Articles ───────────────────────────────

  get("/everything") {
    val query = params.getOrElse("q", "")
    val limit = params.getOrElse("pageSize", "100").toInt
    // Pass clientId only when the route is firehose or a known category.
    // Free-text search bypasses personalisation: hiding "seen" matches from
    // an intent-driven query would confuse users (see plan, Phase 4.1).
    val isCategoryLookup =
      query.isEmpty ||
      query == "news" ||
      Categories.all.contains(query.toLowerCase)
    val cidForCall = if (isCategoryLookup) clientIdFromHeader else None
    articleService.findEverything(query, limit, cidForCall) match {
      case Right(articles) =>
        NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load articles: ${e.getMessage}"))
    }
  }

  /** Personalised feed — returns articles whose category is in the
    * caller-supplied list. Used by iOS on the Trending tab when the user
    * has a non-empty category selection.
    *
    * `categories` is a comma-separated list of canonical names (the same
    * set served by GET /categories). Unknown names are silently dropped;
    * if the post-filter list is empty the request is rejected with 400.
    * An empty articles[] is a valid 200 response — iOS surfaces an
    * empty-state card with a "Show all news" button to fall back to
    * /everything.
    */
  get("/feed") {
    val raw = params.getOrElse("categories", "")
    val canonical = Categories.all.toSet
    val parsed = raw
      .split(',')
      .toList
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .filter(canonical.contains)
      .distinct
    if (parsed.isEmpty) {
      BadRequest(Map(
        "error" -> "categories required and must contain at least one canonical category"
      ))
    } else {
      val limit = params.getOrElse("pageSize", "100").toInt
      articleService.findByCategories(parsed, limit, clientIdFromHeader) match {
        case Right(articles) =>
          NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
        case Left(e) =>
          InternalServerError(Map("error" -> s"Failed to load articles: ${e.getMessage}"))
      }
    }
  }

  get("/top-headlines") {
    val category = params.getOrElse("category", "")
    val limit    = params.getOrElse("pageSize", "100").toInt
    articleService.findTopHeadlines(category, limit, clientIdFromHeader) match {
      case Right(articles) =>
        NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load headlines: ${e.getMessage}"))
    }
  }

  post("/articles") {
    try {
      val article = parsedBody.extract[ArticleCreate]
      articleService.create(article) match {
        case Right(created) => Created(created)
        case Left(e) =>
          InternalServerError(Map("error" -> s"Failed to create article: ${e.getMessage}"))
      }
    } catch {
      case e: MappingException =>
        BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
    }
  }

  get("/articles/:id") {
    try {
      val id = params("id").toLong
      articleService.findById(id) match {
        case Right(Some(article)) => article
        case Right(None)          => NotFound(Map("error" -> "Article not found"))
        case Left(e) =>
          InternalServerError(Map("error" -> s"Failed to load article: ${e.getMessage}"))
      }
    } catch {
      case _: NumberFormatException =>
        BadRequest(Map("error" -> s"Invalid ID format: '${params("id")}' — expected a number"))
    }
  }

  delete("/articles/:id") {
    try {
      val id = params("id").toLong
      articleService.delete(id) match {
        case Right(rows) if rows > 0 => NoContent()
        case Right(_)                => NotFound(Map("error" -> "Article not found"))
        case Left(e) =>
          InternalServerError(Map("error" -> s"Failed to delete article: ${e.getMessage}"))
      }
    } catch {
      case _: NumberFormatException =>
        BadRequest(Map("error" -> s"Invalid ID format: '${params("id")}' — expected a number"))
    }
  }

  // Hardcoded list — the canonical taxonomy the snelmind summarizer
  // constrains the LLM to (see `Categories.all` for source-of-truth note).
  // Independent of what's in the DB so the iOS UI shows a stable list.
  get("/categories") {
    Map("categories" -> Categories.all)
  }

  get("/app/config") {
    Map("minVersion" -> "1.5.1")
  }

  // Android force-upgrade gate. Compared as Int against BuildConfig.VERSION_CODE
  // in the app, so any client with versionCode < minVersionCode is shown the
  // force-update screen. minVersionName is informational only (shown in UI / logs).
  get("/app/config/android") {
    Map(
      "minVersionCode" -> 152,
      "minVersionName" -> "1.5.1"
    )
  }

  /** IP-based country lookup for first-launch app onboarding. Reads the
    * `CF-IPCountry` header set by Cloudflare's edge for every request
    * coming through their network. Returns `{"country":"nl"}` (lowercase
    * 2-letter) on success, or `{"country":""}` when:
    *   - the header is absent (request didn't transit Cloudflare),
    *   - the header is `XX` (Cloudflare's "unknown" sentinel),
    *   - the value isn't a valid 2-letter code (defence in depth).
    * Apps treat an empty response as "fall back to device locale" — no
    * error UI is required for this path.
    */
  get("/geo/country") {
    val raw = Option(request.getHeader("CF-IPCountry")).map(_.trim).getOrElse("")
    val country = raw.toLowerCase match {
      case s if s.matches("^[a-z]{2}$") && s != "xx" => s
      case _                                          => ""
    }
    Map("country" -> country)
  }

  // ─────────────────────────── Client registry ───────────────────────────

  /** Bootstrap route. iOS calls on first launch with the UUID it generated
    * locally and stored in Keychain; we record it so subsequent calls'
    * X-Client-Key lookups succeed. Exempt from the X-Client-Key gate. */
  post("/clients/register") {
    try {
      val req = parsedBody.extract[RegisterClientRequest]
      val parsed = Try(UUID.fromString(req.clientId.trim)).toOption
      parsed match {
        case None =>
          BadRequest(Map("error" -> "clientId must be a UUID"))
        case _ if req.bundleId.trim.isEmpty =>
          BadRequest(Map("error" -> "bundleId is required"))
        case Some(uuid) =>
          appClientRepository.upsertOnRegister(
            clientId   = uuid,
            bundleId   = req.bundleId.trim,
            osVersion  = req.osVersion.map(_.trim).filter(_.nonEmpty),
            platform   = "ios"
          ) match {
            case Right(_) => Map("ok" -> true)
            case Left(e) =>
              InternalServerError(Map("error" -> s"Failed to register client: ${e.getMessage}"))
          }
      }
    } catch {
      case e: MappingException =>
        BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
    }
  }

  // ───────────────────────────── Notifications ───────────────────────────

  /** Same semantics as v1 — Authorization optional (logout sends none).
    * Difference: client_id is captured from the X-Client-Key header (which
    * the gate has already verified) and persisted onto the row. */
  post("/notifications/subscribe") {
    try {
      val req = parsedBody.extract[SubscribeRequest]
      if (req.deviceId.trim.isEmpty || req.apnsToken.trim.isEmpty) {
        BadRequest(Map("error" -> "deviceId and apnsToken are required"))
      } else if (req.frequency < 1 || req.frequency > 4) {
        BadRequest(Map("error" -> "frequency must be between 1 and 4"))
      } else {
        val userIdOpt: Option[String] =
          Option(request.getHeader("Authorization")).filter(_.nonEmpty) match {
            case None => None
            case Some(header) =>
              firebaseVerifier.verify(header) match {
                case Right(uid) => Some(uid)
                case Left(e) =>
                  logger.warn(s"subscribe: token verification failed: ${e.getMessage}")
                  halt(Unauthorized(Map("error" -> "invalid or expired token")))
              }
          }
        notificationService.subscribe(req, userIdOpt, clientIdFromHeader) match {
          case Right(_) => Map("ok" -> true)
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to subscribe: ${e.getMessage}"))
        }
      }
    } catch {
      case e: MappingException =>
        BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
    }
  }

  /** New in v2: lets a Skip-mode user (no Firebase account) delete their
    * device's subscription row. Auth is the X-Client + X-Client-Key gate
    * — no Firebase token needed. */
  delete("/notifications/:deviceId") {
    val deviceId = params("deviceId").trim
    if (deviceId.isEmpty) {
      BadRequest(Map("error" -> "deviceId is required"))
    } else {
      notificationService.deleteDevice(deviceId) match {
        case Right(rows) if rows > 0 => NoContent()
        case Right(_)                => NotFound(Map("error" -> "no subscription for that deviceId"))
        case Left(e) =>
          InternalServerError(Map("error" -> s"Failed to delete subscription: ${e.getMessage}"))
      }
    }
  }

  // /notifications/dispatch is intentionally NOT mounted under v2 — it
  // lives in NotificationDispatchServlet, gated only by X-API-Key, so
  // Airflow doesn't need to learn about X-Client / X-Client-Key.

  // ───────────────────────────── Users (auth) ────────────────────────────

  private def requireUid(): String = {
    Option(request.getHeader("Authorization")).filter(_.nonEmpty) match {
      case None =>
        halt(Unauthorized(Map("error" -> "missing Authorization header")))
      case Some(header) =>
        firebaseVerifier.verify(header) match {
          case Right(uid) => uid
          case Left(e) =>
            logger.warn(s"Token verification failed: ${e.getMessage}")
            halt(Unauthorized(Map("error" -> "invalid or expired token")))
        }
    }
  }

  post("/users") {
    val uid = requireUid()
    try {
      val req = parsedBody.extract[UpsertUserRequest]
      if (req.email.trim.isEmpty) {
        BadRequest(Map("error" -> "email is required"))
      } else {
        userService.upsert(uid, req.email.trim) match {
          case Right(_) => Map("ok" -> true)
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to upsert user: ${e.getMessage}"))
        }
      }
    } catch {
      case e: MappingException =>
        BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
    }
  }

  /** Platform-aware: the iOS and Android subscription tables are kept
    * separate, so this route picks the right one off the X-Client header
    * (already validated by the gate to be `(ios|android)/<v>`). iOS reads
    * notification_subscriptions; Android reads android_notification_subscriptions.
    * Categories are NOT platform-specific (they live on the shared users
    * table) and are handled by the /users/me/categories routes instead.
    */
  get("/users/me/last-preference") {
    val uid = requireUid()
    val xClient = Option(request.getHeader("X-Client")).map(_.trim).getOrElse("")
    val result =
      if (xClient.startsWith("android/")) userService.lastFrequencyAndroid(uid)
      else userService.lastFrequency(uid)
    result match {
      case Right(Some(freq)) => LastPreferenceResponse(freq)
      case Right(None)       => NotFound(Map("error" -> "no preferences set"))
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load preference: ${e.getMessage}"))
    }
  }

  /** Returns the user's saved category filter list, or 404 if they
    * haven't saved one. iOS uses this on login (alongside
    * /last-preference) so a 2nd-device install inherits the picks. */
  get("/users/me/categories") {
    val uid = requireUid()
    userService.findCategories(uid) match {
      case Right(Some(list)) => CategoriesPayload(list)
      case Right(None)       => NotFound(Map("error" -> "no categories set"))
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load categories: ${e.getMessage}"))
    }
  }

  /** Overwrites the saved category list. Body shape `{categories:[...]}`.
    * Validated against `Categories.all` — any non-canonical entry is a
    * 400; we don't silently drop them since that'd let a buggy client
    * accumulate orphan tags forever. Empty list is allowed and means
    * "user opted out of filtering" (UI shows everything). */
  put("/users/me/categories") {
    val uid = requireUid()
    try {
      val body = parsedBody.extract[CategoriesPayload]
      val canonical = Categories.all.toSet
      val invalid = body.categories.filterNot(canonical.contains)
      if (invalid.nonEmpty) {
        BadRequest(Map(
          "error"   -> "categories contains non-canonical entries",
          "invalid" -> invalid
        ))
      } else {
        userService.saveCategories(uid, body.categories) match {
          case Right(rows) if rows > 0 => Map("ok" -> true)
          case Right(_) =>
            // No row updated — the user record doesn't exist yet on the
            // backend (likely raced with the upsertUser call from iOS
            // login). Treat as a transient failure rather than a 404
            // since the next sync will succeed.
            InternalServerError(Map("error" -> "user record not found, retry after upsertUser"))
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to save categories: ${e.getMessage}"))
        }
      }
    } catch {
      case e: MappingException =>
        BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
    }
  }

  delete("/users/me") {
    val uid = requireUid()
    val deviceIdOpt = params.get("deviceId").map(_.trim).filter(_.nonEmpty)

    val deviceCleanup: Either[Throwable, Int] = deviceIdOpt match {
      case Some(deviceId) => notificationService.deleteDevice(deviceId)
      case None           => Right(0)
    }

    deviceCleanup match {
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to delete device subscription: ${e.getMessage}"))
      case Right(_) =>
        userService.delete(uid) match {
          case Right(_) => NoContent()
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to delete user: ${e.getMessage}"))
        }
    }
  }
}
