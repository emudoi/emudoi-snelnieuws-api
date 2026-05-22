package com.snelnieuws.api

import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.model.{ArticleV3Row, Categories, CategoryNames, Languages}
import com.snelnieuws.repository.AppClientRepository
import com.snelnieuws.service.{ArticleService, NotificationService, UserService}
import com.snelnieuws.util.CursorCodec
import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
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
  imagesPublicBaseUrl: String
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

    articleRepository.findV3(country, language, categories, cursorOpt, limit) match {
      case Right((rows, hasMore)) =>
        val articles = rows.map(toApi)
        val nextCursor =
          if (hasMore && rows.nonEmpty) {
            val last = rows.last
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
          Try(params("id").toLong).toOption match {
            case None =>
              BadRequest(Map("error" -> s"Invalid ID format: '${params("id")}' — expected a number"))
            case Some(id) =>
              articleRepository.findV3ById(country, language, id) match {
                case Right(Some(row)) => toApi(row)
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

  // ─────────────────────────────── Internals ─────────────────────────────

  private val publishedAtFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

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
      is_local    = row.isLocal,
      language    = row.language
    )

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
