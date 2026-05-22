package com.snelnieuws.api

import com.snelnieuws.service.{DispatchOutcome, NotificationService}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

/** Mounted at exact paths `/notifications/dispatch` (production) and
  * `/notifications/dispatch-sandbox` (Xcode-debug installs). Auth is the
  * same shared-secret X-API-Key that's been here since this lived inside
  * NewsServlet — no Firebase token, no v2 client gate. The only thing
  * that varies between the two mounts is `environment`, which selects
  * which APNs client (api.push.apple.com vs api.sandbox.push.apple.com)
  * NotificationService routes through and which subset of subscription
  * tokens it queries.
  *
  * Kept separate from v2 deliberately: bringing dispatch under the v2
  * gate would force Airflow to also send X-Client + X-Client-Key, and
  * we have no reason to couple internal-cron auth with mobile-app auth.
  */
class NotificationDispatchServlet(
  notificationService: NotificationService,
  notificationsApiKey: String,
  environment: String
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[NotificationDispatchServlet])

  before() {
    contentType = formats("json")
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  // The mount is exact (`/notifications/dispatch`), so the servlet sees
  // the request as path-info "/" — that's what this route matches.
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
        notificationService.dispatch(frequencyOpt, environment) match {
          case Right(DispatchOutcome.Sent(response)) => response
          case Right(DispatchOutcome.Disabled) =>
            ServiceUnavailable(Map("error" -> "notifications disabled"))
          case Right(DispatchOutcome.NoFreshTopStory) =>
            // Distinct 503 shape so the Airflow watcher's
            // advance-state step can detect this and SKIP the pivot
            // advance — see notifications_clickbait_tasks.txt §8 + §9.
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
