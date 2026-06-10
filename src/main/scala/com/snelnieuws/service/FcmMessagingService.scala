package com.snelnieuws.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.messaging.{
  FirebaseMessaging,
  MessagingErrorCode,
  MulticastMessage,
  Notification => FcmNotification
}
import com.google.firebase.{FirebaseApp, FirebaseOptions}
import com.snelnieuws.repository.AndroidNotificationSubscriptionRepository
import org.slf4j.LoggerFactory

import java.io.FileInputStream

case class FcmConfig(
  projectId: String,
  serviceAccountPath: String,
  // When true the service does not actually deliver — it asks Firebase to
  // validate the request and per-token state without producing a push.
  // Useful in staging or while Android-side integration is still pending.
  dryRun: Boolean = false
)

trait FcmMessagingService {
  /** Sends an alert push to every device token and returns (sent, failed).
    * Implementations should remove tokens FCM reports as `UNREGISTERED` or
    * `INVALID_ARGUMENT` (the token is no longer deliverable) from
    * `android_notification_subscriptions` — symmetric to the iOS APNs path.
    *
    * `articleId`, when present, is attached to the message `data` map so the
    * app can deep-link to that article on tap. Broadcasts pass None.
    */
  def sendBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None
  ): (Int, Int)
}

/** Real FCM client backed by firebase-admin's `FirebaseMessaging`. Auth is
  * via the same service-account JSON used to verify Firebase ID tokens —
  * the SA principal must additionally hold Firebase Cloud Messaging send
  * permission on the project (Firebase Admin SDK Admin Service Agent or
  * roles/firebasecloudmessaging.admin).
  *
  * Sends are batched with `sendEachForMulticast` (max 500 tokens per call).
  * Per-token outcomes are inspected so dead tokens are pruned individually.
  *
  * `dryRun=true` flips Firebase's validate-only flag, which exercises auth
  * + per-token validity without producing a delivered push. We use that to
  * smoke-test the deploy path before any real Android subscribers exist.
  */
class FirebaseFcmMessagingService(
  subscriptionRepository: AndroidNotificationSubscriptionRepository,
  config: FcmConfig
) extends FcmMessagingService {

  private val logger = LoggerFactory.getLogger(classOf[FirebaseFcmMessagingService])

  // FirebaseApp.initializeApp is gated on FirebaseApp.getApps being empty,
  // so this is safe even when FirebaseTokenVerifier.FirebaseAdmin has
  // already initialized the same default app for ID-token verification.
  init()

  private def init(): Unit = synchronized {
    if (FirebaseApp.getApps.isEmpty) {
      val creds =
        if (config.serviceAccountPath.nonEmpty) {
          val stream = new FileInputStream(config.serviceAccountPath)
          try GoogleCredentials.fromStream(stream)
          finally stream.close()
        } else {
          GoogleCredentials.getApplicationDefault()
        }
      val opts = FirebaseOptions.builder()
        .setCredentials(creds)
        .setProjectId(config.projectId)
        .build()
      FirebaseApp.initializeApp(opts)
      logger.info(
        s"Firebase Admin SDK initialized by FCM service for project=${config.projectId}"
      )
    }
  }

  // FCM caps multicast at 500 tokens per request.
  private val MulticastChunk = 500

  override def sendBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None
  ): (Int, Int) = {
    if (tokens.isEmpty) return (0, 0)
    val notification = FcmNotification.builder().setTitle(title).setBody(body).build()
    val messaging    = FirebaseMessaging.getInstance()

    var sent   = 0
    var failed = 0

    tokens.grouped(MulticastChunk).foreach { chunk =>
      val builder = MulticastMessage.builder().setNotification(notification)
      articleId.foreach(id => builder.putData("articleId", id))
      chunk.foreach(builder.addToken)
      val msg = builder.build()

      try {
        val resp = messaging.sendEachForMulticast(msg, config.dryRun)
        sent += resp.getSuccessCount
        // getResponses() preserves input order, so index aligns with chunk.
        val responses = resp.getResponses
        var i = 0
        while (i < responses.size()) {
          val r = responses.get(i)
          if (!r.isSuccessful) {
            failed += 1
            val token  = chunk(i)
            val errOpt = Option(r.getException)
            val code   = errOpt.flatMap(e => Option(e.getMessagingErrorCode))
            code match {
              case Some(MessagingErrorCode.UNREGISTERED) |
                   Some(MessagingErrorCode.INVALID_ARGUMENT) |
                   Some(MessagingErrorCode.SENDER_ID_MISMATCH) =>
                logger.info(s"Pruning rejected FCM token (code=$code)")
                subscriptionRepository.deleteByFcmToken(token) match {
                  case Right(_) =>
                  case Left(e)  => logger.warn(s"Failed to delete dead FCM token: ${e.getMessage}")
                }
              case _ =>
                val msgStr = errOpt.map(_.getMessage).getOrElse("unknown")
                logger.warn(s"FCM rejected token: code=$code msg=$msgStr")
            }
          }
          i += 1
        }
      } catch {
        case e: Exception =>
          // Whole-chunk failure (auth, network, malformed payload). Count
          // the entire chunk as failed so observability matches reality —
          // we can't disambiguate per-token from a top-level throw.
          failed += chunk.size
          logger.warn(s"FCM sendEachForMulticast failed: ${e.getMessage}", e)
      }
    }

    (sent, failed)
  }
}
