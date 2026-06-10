package com.snelnieuws.service

import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.{SimpleApnsPushNotification, TokenUtil}
import com.eatthepath.pushy.apns.{ApnsClient, ApnsClientBuilder, PushNotificationResponse}
import com.snelnieuws.repository.NotificationSubscriptionRepository
import org.slf4j.LoggerFactory

import java.io.File
import java.util.concurrent.CompletableFuture
import scala.jdk.OptionConverters._

case class ApnsConfig(
  keyPath: String,
  keyId: String,
  teamId: String,
  bundleId: String,
  sandbox: Boolean
)

trait ApnsMessagingService {
  /** Sends an alert push to every device token in parallel and returns
   *  (sent, failed). Implementations should remove tokens APNs reports as
   *  `Unregistered` or `BadDeviceToken` from `notification_subscriptions`.
   *
   *  `articleId`, when present, is added as a top-level custom field next to
   *  `aps` so the app can deep-link to that article on tap. Broadcasts pass
   *  None (no single article).
   */
  def sendBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None
  ): (Int, Int)
}

/** Real APNs HTTP/2 client, authenticated via JWT signed with the provided
 *  .p8 key. `sandbox=true` targets api.sandbox.push.apple.com (Debug iOS
 *  builds); false targets api.push.apple.com (TestFlight + App Store).
 *  Components only constructs this when notifications are enabled and
 *  config (keyId/teamId/key file) is valid.
 */
class PushyApnsMessagingService(
  subscriptionRepository: NotificationSubscriptionRepository,
  config: ApnsConfig
) extends ApnsMessagingService {

  private val logger = LoggerFactory.getLogger(classOf[PushyApnsMessagingService])

  private val client: ApnsClient = {
    val host =
      if (config.sandbox) ApnsClientBuilder.DEVELOPMENT_APNS_HOST
      else ApnsClientBuilder.PRODUCTION_APNS_HOST
    val signingKey =
      ApnsSigningKey.loadFromPkcs8File(new File(config.keyPath), config.teamId, config.keyId)
    val c = new ApnsClientBuilder()
      .setApnsServer(host)
      .setSigningKey(signingKey)
      .build()
    logger.info(
      s"APNs client initialized (sandbox=${config.sandbox}, bundle=${config.bundleId}, keyId=${config.keyId})"
    )
    c
  }

  def sendBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None
  ): (Int, Int) = {
    if (tokens.isEmpty) return (0, 0)
    val payload = buildPayload(title, body, articleId)

    val futures: List[(String, CompletableFuture[PushNotificationResponse[SimpleApnsPushNotification]])] =
      tokens.map { token =>
        val sanitized    = TokenUtil.sanitizeTokenString(token)
        val notification = new SimpleApnsPushNotification(sanitized, config.bundleId, payload)
        (token, client.sendNotification(notification))
      }

    var sent   = 0
    var failed = 0
    futures.foreach { case (token, fut) =>
      try {
        val resp = fut.get()
        if (resp.isAccepted) {
          sent += 1
        } else {
          failed += 1
          val reason = resp.getRejectionReason.toScala.getOrElse("unknown")
          if (reason == "Unregistered" || reason == "BadDeviceToken") {
            logger.info(s"Pruning rejected token (reason=$reason)")
            subscriptionRepository.deleteByApnsToken(token) match {
              case Right(_) =>
              case Left(e)  => logger.warn(s"Failed to delete dead token: ${e.getMessage}")
            }
          } else {
            logger.warn(s"APNs rejected token: reason=$reason")
          }
        }
      } catch {
        case e: Exception =>
          failed += 1
          logger.warn(s"APNs send failed: ${e.getMessage}")
      }
    }

    (sent, failed)
  }

  private def buildPayload(title: String, body: String, articleId: Option[String]): String = {
    val aps = s""""aps":{"alert":{"title":"${escape(title)}","body":"${escape(body)}"},"sound":"default"}"""
    // Custom keys live alongside `aps` (APNs ignores them); the app reads
    // userInfo["articleId"] on tap to deep-link to the article.
    val extra = articleId.map(id => s""","articleId":"${escape(id)}"""").getOrElse("")
    s"""{$aps$extra}"""
  }

  private def escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}
