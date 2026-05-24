package com.snelnieuws.api

import com.snelnieuws.service.{VideoDispatchOutcome, VideoDispatchService}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

/** Mounted at `/api/videos/dispatch`. Airflow (or any scheduler) POSTs
  * here with the same X-API-Key the iOS/Android dispatch endpoints
  * use. The service inserts two rows into `top_news_videos` (one v21
  * + one yellow); the litikai-video-generator pipeline polls
  * `WHERE status='pending'` and renders each.
  *
  * Error shape mirrors AndroidNotificationDispatchServlet so the
  * Airflow DAGs that already handle 503/`no_fresh_top_story` don't
  * need a new branch.
  */
class VideoDispatchServlet(
  videoDispatchService: VideoDispatchService,
  notificationsApiKey:  String
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[VideoDispatchServlet])

  before() {
    contentType = formats("json")
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  post("/") {
    val provided = Option(request.getHeader("X-API-Key")).getOrElse("")
    if (notificationsApiKey.isEmpty || provided != notificationsApiKey) {
      Unauthorized(Map("error" -> "invalid or missing X-API-Key"))
    } else {
      videoDispatchService.dispatch() match {
        case Right(VideoDispatchOutcome.Inserted(ids, top1, top5)) =>
          Map(
            "created_ids"      -> ids,
            "top1_article_id"  -> top1,
            "top5_article_ids" -> top5
          )
        case Right(VideoDispatchOutcome.NoFreshTopStory) =>
          ServiceUnavailable(
            Map(
              "error"      -> "no_fresh_top_story",
              "retry_hint" -> "fires when snelmind posts a new top story"
            )
          )
        case Left(e) =>
          InternalServerError(Map("error" -> s"Failed to dispatch: ${e.getMessage}"))
      }
    }
  }
}
