package com.snelnieuws.api

import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService}
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

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig = testConfig,
    apns = Some(stubApns)
  )

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

      // Add a fresh article so newArticles >= 1.
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
        val parsed     = org.json4s.jackson.parseJson(body)
        val sent       = (parsed \ "sent").extract[Int]
        val failed     = (parsed \ "failed").extract[Int]
        val newArticles = (parsed \ "newArticles").extract[Int]
        sent should be >= 1
        failed shouldBe 0
        newArticles should be >= 1
      }

      val batches = stubApns.batches
      batches should not be empty
      batches.flatMap(_.tokens) should contain("dispatch-token-1")
    }

    "return sent=0 / failed=0 when there are no new articles since last dispatch" in {
      requireDb()
      // The previous test's recordDispatch makes lastAsOfArticleId equal to the
      // current latest, so newArticles is 0 here unless someone else inserted.
      stubApns.clear()
      post(
        "/notifications/dispatch?frequency=4",
        "",
        jsonHeader ++ Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 200
        val parsed = org.json4s.jackson.parseJson(body)
        (parsed \ "sent").extract[Int] shouldBe 0
        (parsed \ "failed").extract[Int] shouldBe 0
      }
      // No tokens means no batches recorded.
      stubApns.batches shouldBe empty
    }
  }
}
