package com.snelnieuws.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.{FirebaseApp, FirebaseOptions}
import com.google.firebase.auth.FirebaseAuth
import org.slf4j.LoggerFactory

import java.io.FileInputStream

/** Verifies a Firebase ID token presented in the Authorization header.
  *
  * Production flow: iOS calls `Auth.auth().currentUser?.getIDToken()`,
  * sends it as `Authorization: Bearer <token>`, and the backend verifies
  * via firebase-admin (which fetches Google's public keys to validate the
  * RS256 signature). Returns the uid on success.
  */
trait FirebaseTokenVerifier {
  def verify(authHeader: String): Either[Throwable, String]
}

object FirebaseTokenVerifier {

  private val logger = LoggerFactory.getLogger(getClass)

  def extractBearerToken(authHeader: String): Option[String] = {
    val trimmed = authHeader.trim
    if (trimmed.isEmpty) None
    else if (trimmed.toLowerCase == "bearer") None
    else if (trimmed.toLowerCase.startsWith("bearer ")) {
      val rest = trimmed.substring(7).trim
      if (rest.isEmpty) None else Some(rest)
    } else Some(trimmed)
  }

  /** Real verifier backed by firebase-admin. Initializes the SDK once;
    * safe to construct multiple instances (FirebaseApp.initializeApp is
    * gated on FirebaseApp.getApps being empty). */
  class FirebaseAdmin(projectId: String, serviceAccountPath: String)
      extends FirebaseTokenVerifier {

    init()

    private def init(): Unit = synchronized {
      if (FirebaseApp.getApps.isEmpty) {
        val creds =
          if (serviceAccountPath.nonEmpty) {
            val stream = new FileInputStream(serviceAccountPath)
            try GoogleCredentials.fromStream(stream)
            finally stream.close()
          } else {
            GoogleCredentials.getApplicationDefault()
          }
        val opts = FirebaseOptions.builder()
          .setCredentials(creds)
          .setProjectId(projectId)
          .build()
        FirebaseApp.initializeApp(opts)
        logger.info(s"Firebase Admin SDK initialized for project=$projectId")
      }
    }

    override def verify(authHeader: String): Either[Throwable, String] =
      extractBearerToken(authHeader) match {
        case None =>
          Left(new IllegalArgumentException("missing or empty bearer token"))
        case Some(token) =>
          try Right(FirebaseAuth.getInstance().verifyIdToken(token).getUid)
          catch {
            case e: Exception =>
              logger.debug(s"token verification failed: ${e.getMessage}")
              Left(e)
          }
      }
  }

  /** Rejects every request with 401. Used when firebase.project-id is empty
    * so auth-required routes stay wired up but locked down — important for
    * dev environments without service-account credentials. */
  object RejectAll extends FirebaseTokenVerifier {
    override def verify(authHeader: String): Either[Throwable, String] =
      Left(new IllegalStateException(
        "Firebase verification not configured (set firebase.project-id)"
      ))
  }

  /** Test/dev verifier — accepts a fixed map of token→uid. */
  class Stub(uidByToken: Map[String, String]) extends FirebaseTokenVerifier {
    override def verify(authHeader: String): Either[Throwable, String] =
      extractBearerToken(authHeader) match {
        case None =>
          Left(new IllegalArgumentException("missing or empty bearer token"))
        case Some(token) =>
          uidByToken.get(token) match {
            case Some(uid) => Right(uid)
            case None      => Left(new IllegalArgumentException("unknown stub token"))
          }
      }
  }
}
