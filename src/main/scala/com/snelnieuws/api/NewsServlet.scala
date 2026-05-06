package com.snelnieuws.api

import com.snelnieuws.model.{ArticleCreate, NewsFetchResponse, SubscribeRequest}
import com.snelnieuws.service.{ArticleService, DispatchOutcome, NotificationService}
import org.json4s.{DefaultFormats, Formats, MappingException}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

class NewsServlet(
  articleService: ArticleService,
  notificationService: NotificationService,
  notificationsApiKey: String
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

  // GET /articles/:id
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

  // POST /notifications/subscribe — called by the iOS app on first launch
  // (and on any later frequency change or token rotation). Idempotent upsert
  // keyed on deviceId. No auth: this is keyed by the device's stable UUID.
  post("/notifications/subscribe") {
    try {
      val req = parsedBody.extract[SubscribeRequest]
      if (req.deviceId.trim.isEmpty || req.apnsToken.trim.isEmpty) {
        BadRequest(Map("error" -> "deviceId and apnsToken are required"))
      } else if (req.frequency < 1 || req.frequency > 4) {
        BadRequest(Map("error" -> "frequency must be between 1 and 4"))
      } else {
        notificationService.subscribe(req) match {
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

  // POST /notifications/dispatch — called by Airflow with no body. Counts
  // articles published since the last dispatch for this frequency tier and
  // multicasts a generic "X new articles" message. Optional query param
  // `frequency` (1–4) filters subscribers AND scopes the "since last dispatch"
  // lookup to that tier; omit to fan out to all. Auth via X-API-Key header.
  post("/notifications/dispatch") {
    val provided = Option(request.getHeader("X-API-Key")).getOrElse("")
    if (notificationsApiKey.isEmpty || provided != notificationsApiKey) {
      Unauthorized(Map("error" -> "invalid or missing X-API-Key"))
    } else {
      val rawFrequency  = params.get("frequency")
      val frequencyOpt  = rawFrequency.flatMap(_.toIntOption)
      if (rawFrequency.isDefined && frequencyOpt.isEmpty) {
        BadRequest(Map("error" -> "frequency must be a number"))
      } else if (frequencyOpt.exists(f => f < 1 || f > 4)) {
        BadRequest(Map("error" -> "frequency must be between 1 and 4"))
      } else {
        notificationService.dispatch(frequencyOpt) match {
          case Right(DispatchOutcome.Sent(response)) => response
          case Right(DispatchOutcome.Disabled) =>
            ServiceUnavailable(Map("error" -> "notifications disabled"))
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to dispatch: ${e.getMessage}"))
        }
      }
    }
  }

  // Privacy policy — referenced from App Store listing and in-app menu
  get("/privacy") {
    serveStatic("static/privacy.html")
  }

  // Support page — referenced from App Store listing
  get("/support") {
    serveStatic("static/support.html")
  }

  private def serveStatic(resourcePath: String): Any = {
    Option(getClass.getClassLoader.getResourceAsStream(resourcePath)) match {
      case Some(stream) =>
        try {
          val bytes = stream.readAllBytes()
          contentType = "text/html; charset=utf-8"
          response.setHeader("Cache-Control", "public, max-age=3600")
          new String(bytes, "UTF-8")
        } finally {
          stream.close()
        }
      case None =>
        NotFound(Map("error" -> s"Resource $resourcePath not found"))
    }
  }
}
