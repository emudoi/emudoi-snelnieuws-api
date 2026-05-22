package com.snelnieuws.api

import com.snelnieuws.service.{AndroidNotificationService, DispatchOutcome}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

/** Mounted at the exact path `/android/notifications/dispatch`. Auth is the
  * same shared X-API-Key the iOS dispatch servlet uses — Airflow doesn't
  * need to learn about the X-Client / X-Client-Key gate.
  *
  * No sandbox variant: FCM has a single endpoint for both debug and release
  * builds (Firebase routes by API key, not endpoint host).
  */
class AndroidNotificationDispatchServlet(
  androidNotificationService: AndroidNotificationService,
  notificationsApiKey: String
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[AndroidNotificationDispatchServlet])

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
      val rawFrequency = params.get("frequency")
      val frequencyOpt = rawFrequency.flatMap(_.toIntOption)
      if (rawFrequency.isDefined && frequencyOpt.isEmpty) {
        BadRequest(Map("error" -> "frequency must be a number"))
      } else if (frequencyOpt.exists(f => f < 1 || f > 4)) {
        BadRequest(Map("error" -> "frequency must be between 1 and 4"))
      } else {
        androidNotificationService.dispatch(frequencyOpt) match {
          case Right(DispatchOutcome.Sent(response)) => response
          case Right(DispatchOutcome.Disabled) =>
            ServiceUnavailable(Map("error" -> "android notifications disabled"))
          case Right(DispatchOutcome.NoFreshTopStory) =>
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
}
