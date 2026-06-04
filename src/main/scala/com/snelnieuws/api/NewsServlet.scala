package com.snelnieuws.api

import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.model.{
  ArticleCreate,
  LastPreferenceResponse,
  NewsFetchResponse,
  SubscribeRequest,
  UpsertUserRequest
}
import com.snelnieuws.service.{ArticleService, NotificationService, UserService}
import org.json4s.{DefaultFormats, Formats, MappingException}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

class NewsServlet(
  articleService: ArticleService,
  notificationService: NotificationService,
  userService: UserService,
  firebaseVerifier: FirebaseTokenVerifier,
  // Read-only service bound to eulang_articles, used to resolve "e<id>"
  // share ids on GET /articles/:id (the public meta endpoint link-preview
  // crawlers hit). Optional so existing callers/tests construct unchanged;
  // when None, "e<id>" ids resolve to nothing (404).
  eulangArticleService: Option[ArticleService] = None
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[NewsServlet])

  before() {
    contentType = formats("json")
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  // GET /everything — q, pageSize
  get("/everything") {
    val query = params.getOrElse("q", "")
    val limit = params.getOrElse("pageSize", "100").toInt
    articleService.findEverything(query, limit) match {
      case Right(articles) =>
        NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load articles: ${e.getMessage}"))
    }
  }

  // GET /top-headlines — category, pageSize
  get("/top-headlines") {
    val category = params.getOrElse("category", "")
    val limit    = params.getOrElse("pageSize", "100").toInt
    articleService.findTopHeadlines(category, limit) match {
      case Right(articles) =>
        NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load headlines: ${e.getMessage}"))
    }
  }

  // POST /articles
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

  // GET /articles/:id — also serves link-preview (Open Graph) metadata to
  // the redirect service. Accepts both plain numeric ids (articles table)
  // and "e<id>" share ids for eulang articles, mirroring v3's routing so
  // shared eulang links resolve a title/description/image.
  get("/articles/:id") {
    ArticleService.decodePublicId(params("id")) match {
      case None =>
        BadRequest(Map("error" -> s"Invalid ID format: '${params("id")}'"))
      case Some((isEulang, rawId)) =>
        val svc = if (isEulang) eulangArticleService.getOrElse(articleService) else articleService
        svc.findById(rawId) match {
          case Right(Some(article)) => article
          case Right(None)          => NotFound(Map("error" -> "Article not found"))
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to load article: ${e.getMessage}"))
        }
    }
  }

  // DELETE /articles/:id
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

  // GET /categories
  get("/categories") {
    articleService.findCategories() match {
      case Right(categories) => Map("categories" -> categories)
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load categories: ${e.getMessage}"))
    }
  }

  // GET /app/config
  get("/app/config") {
    Map("minVersion" -> "1.2.0")
  }

  // POST /notifications/subscribe — called by the iOS app on first launch,
  // and on any later frequency change, token rotation, login, or logout.
  // Idempotent upsert keyed on deviceId.
  //
  // Auth is OPTIONAL:
  //   - Authorization header present and valid → user_id is set on the row.
  //   - Authorization header absent           → user_id is set to NULL
  //                                              (this is how logout unlinks).
  //   - Authorization header present but invalid → 401.
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
        notificationService.subscribe(req, userIdOpt) match {
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

  // /notifications/dispatch lives in NotificationDispatchServlet now.
  // Same X-API-Key behavior — only the carrier servlet has changed.

  // ---------- /users routes (auth required) ----------

  /** Verifies the Authorization header. Halts with 401 when missing or
    * invalid. Returns the verified Firebase uid on success. */
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

  // POST /users — idempotent upsert of the backend user record. Auth required.
  // iOS calls this on signup and (as a backfill) on every login. The uid
  // comes from the verified token; only the email is in the body.
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

  // GET /users/me/last-preference — returns the most-recently-updated
  // frequency for any device owned by this user. iOS uses it on login on
  // a fresh device to skip the onboarding picker. 404 → no prior pref.
  get("/users/me/last-preference") {
    val uid = requireUid()
    userService.lastFrequency(uid) match {
      case Right(Some(freq)) => LastPreferenceResponse(freq)
      case Right(None)       => NotFound(Map("error" -> "no preferences set"))
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load preference: ${e.getMessage}"))
    }
  }

  // DELETE /users/me — called by iOS during account deletion, BEFORE the
  // Firebase deletion (which invalidates the token). FK ON DELETE CASCADE
  // wipes the user's notification_subscriptions rows that have user_id set.
  //
  // Optional `?deviceId=X` query param: also delete that specific device row
  // by deviceId. This covers the case where the device's row was created
  // with user_id=NULL (e.g. signup happened during a backend outage and the
  // POST /users call silently failed, so the user_id link was never made).
  // Without this, the device keeps receiving pushes after account deletion.
  delete("/users/me") {
    val uid = requireUid()
    val deviceIdOpt = params.get("deviceId").map(_.trim).filter(_.nonEmpty)

    val deviceCleanup: Either[Throwable, Int] = deviceIdOpt match {
      case Some(deviceId) =>
        notificationService.deleteDevice(deviceId)
      case None =>
        Right(0)
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

  // /privacy and /support live in StaticContentServlet now.
}
