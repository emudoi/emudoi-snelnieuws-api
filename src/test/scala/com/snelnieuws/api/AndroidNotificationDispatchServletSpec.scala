package com.snelnieuws.api

import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService, StubFcmMessagingService}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.typesafe.config.ConfigFactory
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AndroidNotificationDispatchServletSpec
    extends AnyWordSpec
    with ScalatraSuite
    with Matchers
    with DatabaseTestSupport {

  implicit lazy val jsonFormats: Formats = DefaultFormats

  private val testApiKey = "test-android-dispatch-api-key"

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
  private val stubFcm  = new StubFcmMessagingService(acceptAll = true)
  private val stubVerifier = new FirebaseTokenVerifier.Stub(Map.empty)

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig        = testConfig,
    apns              = Some(stubApns),
    apnsSandbox       = None,
    fcm               = Some(stubFcm),
    verifierOverride  = Some(stubVerifier)
  )

  addServlet(components.androidNotificationDispatchServlet, "/android/notifications/dispatch")
  addServlet(components.androidNotificationBroadcastServlet, "/android/notifications/broadcast")

  "POST /android/notifications/dispatch" should {
    "return 401 without X-API-Key" in {
      requireDb()
      post("/android/notifications/dispatch") {
        status shouldBe 401
      }
    }

    "return 401 with the wrong X-API-Key" in {
      requireDb()
      post("/android/notifications/dispatch", Map.empty[String, String],
           Map("X-API-Key" -> "wrong")) {
        status shouldBe 401
      }
    }

    "return 200 with a valid X-API-Key (no subscribers, no articles)" in {
      requireDb()
      // §8 dispatch flow requires a fresh top_summary when newArticles>0
      // OR returns Sent(0,0,0) when newArticles==0. Earlier suites in
      // the same DB may have inserted articles, so seed one
      // defensively — the per-language fan-out finds no Android
      // subscribers and returns sent=0 either way.
      val seedPayload = com.snelnieuws.model.TopStoryPayload(
        representativeArticleId = scala.util.Random.nextLong().abs,
        topNews                 = io.circe.Json.obj(),
        notificationMessages    = Map("en" -> "android dispatch-spec headline"),
        selectionTier           = 1,
        selectionMetadata       = io.circe.Json.obj()
      )
      components.topSummaryRepository.insert(seedPayload) shouldBe a[Right[_, _]]

      post(
        "/android/notifications/dispatch",
        Map.empty[String, String],
        Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 200
        body should include("sent")
      }
    }

    "reject a non-numeric frequency" in {
      requireDb()
      post(
        "/android/notifications/dispatch?frequency=abc",
        Map.empty[String, String],
        Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 400
      }
    }

    "reject a frequency outside 1..4" in {
      requireDb()
      post(
        "/android/notifications/dispatch?frequency=9",
        Map.empty[String, String],
        Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 400
      }
    }
  }

  "POST /android/notifications/broadcast" should {
    "return 401 without X-API-Key" in {
      requireDb()
      post(
        "/android/notifications/broadcast",
        """{"text":"hello"}""",
        Map("Content-Type" -> "application/json")
      ) {
        status shouldBe 401
      }
    }

    "return 400 when text is empty" in {
      requireDb()
      post(
        "/android/notifications/broadcast",
        """{"text":""}""",
        Map(
          "Content-Type" -> "application/json",
          "X-API-Key"    -> testApiKey
        )
      ) {
        status shouldBe 400
      }
    }

    "return 200 and run the broadcast pipeline with a valid X-API-Key" in {
      requireDb()
      post(
        "/android/notifications/broadcast",
        """{"text":"hello android"}""",
        Map(
          "Content-Type" -> "application/json",
          "X-API-Key"    -> testApiKey
        )
      ) {
        // Flag-default is false → enabled=false, sent=0. We're asserting
        // the 200 path and JSON shape, not the on-state behavior (covered
        // in the service-level spec).
        status shouldBe 200
        body should include("enabled")
      }
    }
  }
}
