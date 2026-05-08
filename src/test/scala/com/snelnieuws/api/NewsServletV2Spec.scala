package com.snelnieuws.api

import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.typesafe.config.ConfigFactory
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class NewsServletV2Spec
    extends AnyWordSpec
    with ScalatraSuite
    with Matchers
    with DatabaseTestSupport {

  implicit lazy val jsonFormats: Formats = DefaultFormats

  private val testApiKey = "test-api-key"

  private val testConfig = ConfigFactory
    .parseString(
      s"""
         |notifications.enabled = true
         |notifications.api-key = "$testApiKey"
         |articles.cleanup.enabled = false
         |kafka.summarized-import.enabled = false
         |""".stripMargin
    )
    .withFallback(ConfigFactory.load())
    .resolve()

  private val stubApns = new StubApnsMessagingService(acceptAll = true)

  private val testUidByToken = Map(
    "alice-token" -> "uid-alice",
    "bob-token"   -> "uid-bob"
  )
  private val stubVerifier = new FirebaseTokenVerifier.Stub(testUidByToken)

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig = testConfig,
    apns = Some(stubApns),
    verifierOverride = Some(stubVerifier)
  )

  // Mounted at the same /v2/* prefix the bootstrap will use, so requestPath
  // inside the servlet matches what we'll see in production.
  addServlet(components.newsServletV2, "/v2/*")

  // A pre-registered client_id every gated test reuses. Lazy so init runs
  // AFTER beforeAll (which configures the testcontainer database) — an eager
  // val here would NPE on Database.transactor at construction time.
  private lazy val gateClientId: String = {
    val id = UUID.randomUUID().toString
    val regBody = s"""{
      "clientId":  "$id",
      "bundleId":  "com.emudoi.snelnieuws",
      "osVersion": "iOS 18.0"
    }"""
    val regHeaders = Map(
      "Content-Type" -> "application/json",
      "X-Client"     -> "ios/1.4.0"
    )
    post("/v2/clients/register", regBody, regHeaders) {
      assert(status == 200, s"register precondition failed: HTTP $status, body=$body")
    }
    id
  }

  private def gatedHeaders: Map[String, String] = Map(
    "Content-Type"  -> "application/json",
    "X-Client"      -> "ios/1.4.0",
    "X-Client-Key"  -> gateClientId
  )

  private def withAuth(token: String): Map[String, String] =
    gatedHeaders + ("Authorization" -> s"Bearer $token")

  // ─────────────────────────── Gate behavior ─────────────────────────────

  "Gate" should {
    "return 403 when X-Client header is missing" in {
      requireDb()
      get("/v2/everything") {
        status shouldBe 403
        body should include("X-Client")
      }
    }

    "return 403 when X-Client header is the wrong shape" in {
      requireDb()
      get("/v2/everything", Map.empty[String, String], Map("X-Client" -> "web/1.0")) {
        status shouldBe 403
      }
    }

    "return 401 when X-Client present but X-Client-Key missing" in {
      requireDb()
      get("/v2/everything", Map.empty[String, String], Map("X-Client" -> "ios/1.4.0")) {
        status shouldBe 401
        body should include("X-Client-Key")
      }
    }

    "return 401 when X-Client-Key is not a UUID" in {
      requireDb()
      get(
        "/v2/everything",
        Map.empty[String, String],
        Map("X-Client" -> "ios/1.4.0", "X-Client-Key" -> "not-a-uuid")
      ) {
        status shouldBe 401
      }
    }

    "return 401 when X-Client-Key is a UUID we have not seen" in {
      requireDb()
      val unknown = UUID.randomUUID().toString
      get(
        "/v2/everything",
        Map.empty[String, String],
        Map("X-Client" -> "ios/1.4.0", "X-Client-Key" -> unknown)
      ) {
        status shouldBe 401
      }
    }

    "exempt POST /clients/register from the X-Client-Key check" in {
      requireDb()
      val body = s"""{
        "clientId":  "${UUID.randomUUID()}",
        "bundleId":  "com.emudoi.snelnieuws",
        "osVersion": "iOS 18.0"
      }"""
      post(
        "/v2/clients/register",
        body,
        Map("Content-Type" -> "application/json", "X-Client" -> "ios/1.4.0")
      ) {
        status shouldBe 200
      }
    }

    "still require X-Client on POST /clients/register" in {
      requireDb()
      val body = s"""{
        "clientId":  "${UUID.randomUUID()}",
        "bundleId":  "com.emudoi.snelnieuws"
      }"""
      post("/v2/clients/register", body, Map("Content-Type" -> "application/json")) {
        status shouldBe 403
      }
    }
  }

  // ────────────────────────── Client registration ────────────────────────

  "POST /v2/clients/register" should {
    val xClientHeaders = Map("Content-Type" -> "application/json", "X-Client" -> "ios/1.4.0")

    "be idempotent on the same UUID" in {
      requireDb()
      val id = UUID.randomUUID().toString
      val body = s"""{"clientId":"$id","bundleId":"com.emudoi.snelnieuws","osVersion":"iOS 18.0"}"""
      post("/v2/clients/register", body, xClientHeaders) { status shouldBe 200 }
      post("/v2/clients/register", body, xClientHeaders) { status shouldBe 200 }
    }

    "return 400 when clientId is not a UUID" in {
      requireDb()
      val body = """{"clientId":"abc","bundleId":"com.emudoi.snelnieuws"}"""
      post("/v2/clients/register", body, xClientHeaders) { status shouldBe 400 }
    }

    "return 400 when bundleId is empty" in {
      requireDb()
      val body = s"""{"clientId":"${UUID.randomUUID()}","bundleId":""}"""
      post("/v2/clients/register", body, xClientHeaders) { status shouldBe 400 }
    }
  }

  // ───────────────── Articles + config (read-only sanity) ────────────────

  "GET /v2/everything" should {
    "succeed under the full gate" in {
      requireDb()
      get("/v2/everything", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        body should include("\"status\":\"ok\"")
      }
    }
  }

  "GET /v2/categories" should {
    "return the hardcoded canonical list under the full gate" in {
      requireDb()
      get("/v2/categories", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        val list = (org.json4s.jackson.parseJson(body) \ "categories").extract[List[String]]
        list shouldBe List(
          "politics", "economy", "business", "finance", "technology", "science",
          "health", "sports", "culture", "environment", "world",
          "local", "other"
        )
      }
    }

    "return 401 without X-Client-Key (gate still applies)" in {
      requireDb()
      get("/v2/categories", Map.empty[String, String], Map("X-Client" -> "ios/1.4.0")) {
        status shouldBe 401
      }
    }
  }

  "GET /v2/app/config" should {
    "succeed under the full gate" in {
      get("/v2/app/config", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        body should include("minVersion")
      }
    }
  }

  // ─────────────────────────── Subscribe + delete ────────────────────────

  "POST /v2/notifications/subscribe" should {
    "attach client_id from the X-Client-Key header" in {
      requireDb()
      val deviceId = "v2-subscribe-device"
      val body = s"""{
        "deviceId":  "$deviceId",
        "apnsToken": "v2-token",
        "frequency": 2
      }"""
      post("/v2/notifications/subscribe", body, gatedHeaders) {
        status shouldBe 200
      }
      // The repository doesn't expose client_id directly; query the DB.
      // (Smoke: row exists and was upserted; full client_id wiring is
      // covered by AppClientRepositorySpec at the doobie layer.)
      components.notificationSubscriptionRepository
        .findUserIdByDeviceId(deviceId) shouldBe Right(Some(None))
    }
  }

  "DELETE /v2/notifications/:deviceId" should {
    "delete a Skip-mode device row by deviceId" in {
      requireDb()
      val deviceId = "v2-skip-delete-device"
      val body = s"""{
        "deviceId":  "$deviceId",
        "apnsToken": "v2-skip-token",
        "frequency": 3
      }"""
      post("/v2/notifications/subscribe", body, gatedHeaders) { status shouldBe 200 }

      delete(s"/v2/notifications/$deviceId", Map.empty[String, String], gatedHeaders) {
        status shouldBe 204
      }
      components.notificationSubscriptionRepository
        .findUserIdByDeviceId(deviceId) shouldBe Right(None)
    }

    "return 404 for an unknown deviceId" in {
      requireDb()
      delete("/v2/notifications/no-such-device", Map.empty[String, String], gatedHeaders) {
        status shouldBe 404
      }
    }
  }

  // ─────────────────────────── Users (auth path) ─────────────────────────

  "POST /v2/users" should {
    "succeed with X-Client + X-Client-Key + Bearer" in {
      requireDb()
      post("/v2/users", """{"email":"alice@example.com"}""", withAuth("alice-token")) {
        status shouldBe 200
      }
    }

    "return 401 when Bearer is missing (gate passes, route auth fails)" in {
      requireDb()
      post("/v2/users", """{"email":"x@example.com"}""", gatedHeaders) {
        status shouldBe 401
      }
    }
  }

  "DELETE /v2/users/me" should {
    "delete the user and any deviceId in the query string" in {
      requireDb()
      // Make sure alice exists.
      post("/v2/users", """{"email":"alice@example.com"}""", withAuth("alice-token")) {
        status shouldBe 200
      }
      // Subscribe a device under alice so we can prove cleanup.
      val deviceId = "v2-delete-me-device"
      val subBody = s"""{
        "deviceId":  "$deviceId",
        "apnsToken": "v2-delete-me-token",
        "frequency": 2
      }"""
      post("/v2/notifications/subscribe", subBody, withAuth("alice-token")) {
        status shouldBe 200
      }
      delete(s"/v2/users/me?deviceId=$deviceId", Map.empty[String, String], withAuth("alice-token")) {
        status shouldBe 204
      }
      components.notificationSubscriptionRepository
        .findUserIdByDeviceId(deviceId) shouldBe Right(None)
    }
  }
}
