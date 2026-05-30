package com.snelnieuws.api

import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.typesafe.config.ConfigFactory
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NewsServletSpec
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

  // Map of bearer token → Firebase uid that the stub verifier accepts.
  // Tests use these to exercise authenticated routes; any other token is rejected.
  private val testUidByToken = Map(
    "alice-token" -> "uid-alice",
    "bob-token"   -> "uid-bob"
  )
  private val stubVerifier = new FirebaseTokenVerifier.Stub(testUidByToken)

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig = testConfig,
    apns = Some(stubApns),
    apnsSandbox = None,
    verifierOverride = Some(stubVerifier)
  )

  // Mirror production: dispatch + static now live in their own servlets.
  // Mount them at the same exact paths the bootstrap uses so the existing
  // test cases (which hit /notifications/dispatch, /privacy, /support)
  // continue to exercise real production routing.
  addServlet(components.notificationDispatchServlet, "/notifications/dispatch")
  addServlet(components.staticContentServlet, "/privacy")
  addServlet(components.staticContentServlet, "/support")
  addServlet(components.newsServlet, "/*")

  private val jsonHeader = Map("Content-Type" -> "application/json")

  "GET /everything" should {
    "return 200 with the seeded articles when no query is given" in {
      requireDb()
      get("/everything") {
        status shouldBe 200
        body should include("\"status\":\"ok\"")
        val totalResults = (org.json4s.jackson.parseJson(body) \ "totalResults").extract[Int]
        totalResults should be > 0
      }
    }

    "fall back to search when category does not match but text does" in {
      requireDb()
      // Create an article whose title contains a unique searchable token but
      // whose category is unique so a category lookup returns empty first.
      val createBody = """{
        "title":       "TestSearchToken zzqq Article",
        "description": "fallback search test",
        "url":         "https://example.com/test/search-token",
        "category":    "TestSearchCategoryUnique"
      }"""
      post("/articles", createBody, jsonHeader) {
        status shouldBe 201
      }
      // Search by a token that matches title but no category equals it.
      get("/everything?q=zzqq") {
        status shouldBe 200
        val totalResults = (org.json4s.jackson.parseJson(body) \ "totalResults").extract[Int]
        totalResults should be >= 1
        body should include("TestSearchToken")
      }
    }
  }

  "GET /top-headlines" should {
    "return 200 for known seed category" in {
      requireDb()
      get("/top-headlines?category=Health") {
        status shouldBe 200
        body should include("\"status\":\"ok\"")
      }
    }

    "return 200 with all headlines when category is empty" in {
      requireDb()
      get("/top-headlines") {
        status shouldBe 200
        val totalResults = (org.json4s.jackson.parseJson(body) \ "totalResults").extract[Int]
        totalResults should be > 0
      }
    }
  }

  "POST /articles" should {
    "create an article and return 201" in {
      requireDb()
      val createBody = """{
        "title":       "Servlet-Created Article",
        "description": "test description",
        "url":         "https://example.com/servlet-created",
        "category":    "ServletTestCategory"
      }"""
      post("/articles", createBody, jsonHeader) {
        status shouldBe 201
        body should include("Servlet-Created Article")
        val id = (org.json4s.jackson.parseJson(body) \ "id").extract[String]
        id.toLong should be > 0L
      }
    }

    "return 400 for invalid body shape" in {
      requireDb()
      val badBody = """{"title": 12345}""" // title should be string
      post("/articles", badBody, jsonHeader) {
        status shouldBe 400
      }
    }
  }

  "GET /articles/:id" should {
    "return the article when it exists" in {
      requireDb()
      val createBody = """{
        "title":       "Lookup Article",
        "description": "lookup",
        "url":         "https://example.com/lookup",
        "category":    "ServletTestCategory"
      }"""
      var createdId = ""
      post("/articles", createBody, jsonHeader) {
        status shouldBe 201
        createdId = (org.json4s.jackson.parseJson(body) \ "id").extract[String]
      }
      get(s"/articles/$createdId") {
        status shouldBe 200
        body should include("Lookup Article")
      }
    }

    "return 404 when the id does not exist" in {
      requireDb()
      get("/articles/9999999") {
        status shouldBe 404
      }
    }

    "return 400 when the id is not a number" in {
      get("/articles/abc") {
        status shouldBe 400
      }
    }
  }

  "DELETE /articles/:id" should {
    "delete an existing article and return 204" in {
      requireDb()
      val createBody = """{
        "title":       "To Be Deleted Servlet",
        "url":         "https://example.com/delete-me-servlet"
      }"""
      var createdId = ""
      post("/articles", createBody, jsonHeader) {
        status shouldBe 201
        createdId = (org.json4s.jackson.parseJson(body) \ "id").extract[String]
      }
      delete(s"/articles/$createdId") {
        status shouldBe 204
      }
    }

    "return 404 when the id does not exist" in {
      requireDb()
      delete("/articles/9999999") {
        status shouldBe 404
      }
    }

    "return 400 when the id is not a number" in {
      delete("/articles/abc") {
        status shouldBe 400
      }
    }
  }

  "GET /categories" should {
    "return at least the seeded categories" in {
      requireDb()
      get("/categories") {
        status shouldBe 200
        val categories = (org.json4s.jackson.parseJson(body) \ "categories").extract[List[String]]
        categories should not be empty
      }
    }
  }

  "GET /app/config" should {
    "return the minVersion" in {
      get("/app/config") {
        status shouldBe 200
        body should include("minVersion")
      }
    }
  }

  "GET /privacy" should {
    "serve the privacy HTML" in {
      get("/privacy") {
        status shouldBe 200
        header("Content-Type") should startWith("text/html")
      }
    }
  }

  "GET /support" should {
    "serve the support HTML" in {
      get("/support") {
        status shouldBe 200
        header("Content-Type") should startWith("text/html")
      }
    }
  }

  "POST /notifications/subscribe" should {
    "upsert a subscription" in {
      requireDb()
      val subBody = """{
        "deviceId":  "device-test-1",
        "apnsToken": "token-test-1",
        "frequency": 2
      }"""
      post("/notifications/subscribe", subBody, jsonHeader) {
        status shouldBe 200
        body should include("\"ok\":true")
      }
    }

    "return 400 when deviceId is empty" in {
      val subBody = """{
        "deviceId":  "",
        "apnsToken": "token",
        "frequency": 2
      }"""
      post("/notifications/subscribe", subBody, jsonHeader) {
        status shouldBe 400
      }
    }

    "return 400 when apnsToken is empty" in {
      val subBody = """{
        "deviceId":  "device-test-2",
        "apnsToken": "",
        "frequency": 2
      }"""
      post("/notifications/subscribe", subBody, jsonHeader) {
        status shouldBe 400
      }
    }

    "return 400 when frequency is out of range" in {
      val subBody = """{
        "deviceId":  "device-test-3",
        "apnsToken": "token",
        "frequency": 9
      }"""
      post("/notifications/subscribe", subBody, jsonHeader) {
        status shouldBe 400
      }
    }

    "leave user_id NULL when no Authorization header is sent" in {
      requireDb()
      val subBody = """{
        "deviceId":  "subscribe-anonymous-device",
        "apnsToken": "subscribe-anonymous-token",
        "frequency": 2
      }"""
      post("/notifications/subscribe", subBody, jsonHeader) {
        status shouldBe 200
      }
      val sub = components.notificationSubscriptionRepository
      sub.findUserIdByDeviceId("subscribe-anonymous-device") shouldBe Right(Some(None))
    }

    "set user_id from a verified Bearer token" in {
      requireDb()
      // alice must already exist in users to satisfy the FK.
      post(
        "/users",
        """{"email":"alice@example.com"}""",
        jsonHeader ++ Map("Authorization" -> "Bearer alice-token")
      ) { status shouldBe 200 }

      val subBody = """{
        "deviceId":  "subscribe-authed-device",
        "apnsToken": "subscribe-authed-token",
        "frequency": 1
      }"""
      post(
        "/notifications/subscribe",
        subBody,
        jsonHeader ++ Map("Authorization" -> "Bearer alice-token")
      ) {
        status shouldBe 200
      }
      components.notificationSubscriptionRepository
        .findUserIdByDeviceId("subscribe-authed-device") shouldBe Right(Some(Some("uid-alice")))
    }

    "clear user_id when subscribe is called without auth on a previously-linked row (logout)" in {
      requireDb()
      // Pre-condition from prior test: subscribe-authed-device is linked to uid-alice.
      val subBody = """{
        "deviceId":  "subscribe-authed-device",
        "apnsToken": "subscribe-authed-token",
        "frequency": 1
      }"""
      post("/notifications/subscribe", subBody, jsonHeader) { status shouldBe 200 }
      components.notificationSubscriptionRepository
        .findUserIdByDeviceId("subscribe-authed-device") shouldBe Right(Some(None))
    }

    "return 401 when Authorization header is present but invalid" in {
      val subBody = """{
        "deviceId":  "subscribe-bad-token-device",
        "apnsToken": "subscribe-bad-token-token",
        "frequency": 1
      }"""
      post(
        "/notifications/subscribe",
        subBody,
        jsonHeader ++ Map("Authorization" -> "Bearer mallory-token")
      ) {
        status shouldBe 401
      }
    }
  }

  "POST /users" should {
    val authAlice = jsonHeader ++ Map("Authorization" -> "Bearer alice-token")

    "create a user record on first call" in {
      requireDb()
      post("/users", """{"email":"alice@example.com"}""", authAlice) {
        status shouldBe 200
        body should include("\"ok\":true")
      }
    }

    "be idempotent (second call updates email)" in {
      requireDb()
      post("/users", """{"email":"alice@example.com"}""", authAlice) {
        status shouldBe 200
      }
      post("/users", """{"email":"alice2@example.com"}""", authAlice) {
        status shouldBe 200
      }
    }

    "return 401 when Authorization header is missing" in {
      post("/users", """{"email":"x@example.com"}""", jsonHeader) {
        status shouldBe 401
      }
    }

    "return 401 when token is unknown" in {
      post(
        "/users",
        """{"email":"x@example.com"}""",
        jsonHeader ++ Map("Authorization" -> "Bearer mallory-token")
      ) {
        status shouldBe 401
      }
    }

    "return 400 when email is empty" in {
      post("/users", """{"email":""}""", authAlice) {
        status shouldBe 400
      }
    }

  }

  "GET /users/me/last-preference" should {
    val authBob = jsonHeader ++ Map("Authorization" -> "Bearer bob-token")

    "return 404 when the user has no subscription rows" in {
      requireDb()
      // Ensure bob exists but has no linked subscription.
      post("/users", """{"email":"bob@example.com"}""", authBob) { status shouldBe 200 }
      get("/users/me/last-preference", headers = authBob) {
        status shouldBe 404
      }
    }

    "return 401 when Authorization header is missing" in {
      get("/users/me/last-preference") {
        status shouldBe 401
      }
    }

    "return the most-recently-updated frequency when one exists" in {
      requireDb()
      // Subscribe a device tied to bob (we set user_id directly here since
      // task #2 hasn't yet wired auth into /notifications/subscribe).
      post("/users", """{"email":"bob@example.com"}""", authBob) { status shouldBe 200 }
      val sub = """{
        "deviceId":  "users-spec-bob-device",
        "apnsToken": "users-spec-bob-token",
        "frequency": 2
      }"""
      post("/notifications/subscribe", sub, jsonHeader) { status shouldBe 200 }
      import cats.effect.unsafe.implicits.global
      import doobie.implicits._
      sql"""UPDATE notification_subscriptions
            SET user_id = 'uid-bob'
            WHERE device_id = 'users-spec-bob-device'"""
        .update.run.transact(Database.transactor).unsafeRunSync()

      get("/users/me/last-preference", headers = authBob) {
        status shouldBe 200
        val parsed = org.json4s.jackson.parseJson(body)
        (parsed \ "frequency").extract[Int] shouldBe 2
      }
    }
  }

  "DELETE /users/me" should {
    val authAlice = jsonHeader ++ Map("Authorization" -> "Bearer alice-token")

    "return 401 when Authorization header is missing" in {
      delete("/users/me") {
        status shouldBe 401
      }
    }

    "also delete the device row when ?deviceId=X is provided (covers user_id=NULL case)" in {
      requireDb()
      // Simulate the post-bug-fix scenario: device row exists with user_id=NULL
      // (e.g. signup happened during a backend outage, /users never created).
      // CASCADE wouldn't cover this row; ?deviceId= explicitly cleans it up.
      val sub = """{
        "deviceId":  "delete-me-orphan-device",
        "apnsToken": "delete-me-orphan-token",
        "frequency": 2
      }"""
      post("/notifications/subscribe", sub, jsonHeader) { status shouldBe 200 }

      // Sanity: the row exists and is anonymous.
      components.notificationSubscriptionRepository
        .findUserIdByDeviceId("delete-me-orphan-device") shouldBe Right(Some(None))

      // alice exists (auth required to call DELETE /users/me).
      post("/users", """{"email":"alice@example.com"}""", authAlice) { status shouldBe 200 }

      delete("/users/me?deviceId=delete-me-orphan-device", headers = authAlice) {
        status shouldBe 204
      }

      // The orphan row is gone.
      components.notificationSubscriptionRepository
        .findUserIdByDeviceId("delete-me-orphan-device") shouldBe Right(None)
    }

    "remove the user (cascade-deleting their subscription rows) and return 204" in {
      requireDb()
      // Set up: alice exists + has a subscription tied to her uid.
      post("/users", """{"email":"alice@example.com"}""", authAlice) { status shouldBe 200 }
      val sub = """{
        "deviceId":  "users-spec-alice-delete-device",
        "apnsToken": "users-spec-alice-delete-token",
        "frequency": 2
      }"""
      post("/notifications/subscribe", sub, jsonHeader) { status shouldBe 200 }
      import cats.effect.unsafe.implicits.global
      import doobie.implicits._
      sql"""UPDATE notification_subscriptions
            SET user_id = 'uid-alice'
            WHERE device_id = 'users-spec-alice-delete-device'"""
        .update.run.transact(Database.transactor).unsafeRunSync()

      delete("/users/me", headers = authAlice) {
        status shouldBe 204
      }

      // Subscription row was cascade-deleted.
      val remaining = sql"""SELECT COUNT(*) FROM notification_subscriptions
                            WHERE device_id = 'users-spec-alice-delete-device'"""
        .query[Int].unique.transact(Database.transactor).unsafeRunSync()
      remaining shouldBe 0
    }
  }

  "POST /notifications/dispatch" should {
    "return 401 when X-API-Key is missing" in {
      post("/notifications/dispatch", "", jsonHeader) {
        status shouldBe 401
      }
    }

    "return 401 when X-API-Key is wrong" in {
      post("/notifications/dispatch", "", jsonHeader ++ Map("X-API-Key" -> "nope")) {
        status shouldBe 401
      }
    }

    "return 400 when frequency is non-numeric" in {
      post(
        "/notifications/dispatch?frequency=abc",
        "",
        jsonHeader ++ Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 400
      }
    }

    "return 400 when frequency is out of range" in {
      post(
        "/notifications/dispatch?frequency=99",
        "",
        jsonHeader ++ Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 400
      }
    }

    "send to subscribed tokens for the given frequency tier" in {
      requireDb()
      stubApns.clear()

      // Subscriber on a unique frequency that no other test uses.
      val subBody = """{
        "deviceId":  "dispatch-device-1",
        "apnsToken": "dispatch-token-1",
        "frequency": 4
      }"""
      post("/notifications/subscribe", subBody, jsonHeader) {
        status shouldBe 200
      }

      // A fresh "en" article gives the per-language pool a claimable
      // candidate (default language is "en", matching the subscriber).
      val articleBody = """{
        "title":       "Dispatch Trigger Article",
        "url":         "https://example.com/dispatch/1"
      }"""
      post("/articles", articleBody, jsonHeader) {
        status shouldBe 201
      }

      post(
        "/notifications/dispatch?frequency=4",
        "",
        jsonHeader ++ Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 200
        val parsed = org.json4s.jackson.parseJson(body)
        val sent   = (parsed \ "sent").extract[Int]
        val failed = (parsed \ "failed").extract[Int]
        sent should be >= 1
        failed shouldBe 0
      }

      val batches = stubApns.batches
      batches should not be empty
      batches.flatMap(_.tokens) should contain("dispatch-token-1")
    }
  }
}
