package com.snelnieuws.api

import cats.effect.unsafe.implicits.global
import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService, StubFcmMessagingService}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.typesafe.config.ConfigFactory
import doobie.implicits._
import doobie.postgres.implicits._
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.OffsetDateTime
import java.util.UUID

class VideoDispatchServletSpec
    extends AnyWordSpec
    with ScalatraSuite
    with Matchers
    with DatabaseTestSupport {

  implicit lazy val jsonFormats: Formats = DefaultFormats

  private val testApiKey = "test-video-dispatch-api-key"

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

  private val stubApns     = new StubApnsMessagingService(acceptAll = true)
  private val stubFcm      = new StubFcmMessagingService(acceptAll = true)
  private val stubVerifier = new FirebaseTokenVerifier.Stub(Map.empty)

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig        = testConfig,
    apns              = Some(stubApns),
    apnsSandbox       = None,
    fcm               = Some(stubFcm),
    verifierOverride  = Some(stubVerifier)
  )

  addServlet(components.videoDispatchServlet, "/api/videos/dispatch")

  private def wipeArticles(): Unit =
    sql"DELETE FROM articles".update.run.transact(Database.transactor).unsafeRunSync()

  private def wipeVideos(): Unit =
    sql"DELETE FROM top_news_videos".update.run.transact(Database.transactor).unsafeRunSync()

  /** Insert article rows directly via SQL — bypasses the
    * `ArticleRepository.create` path (which uses default published_at
    * and skips category/country/language we need for the selector). */
  private def seedArticles(rows: Seq[(String, String, String, String, OffsetDateTime, String, String)]): List[Long] = {
    // tuple: (title, author, url, description, publishedAt, category, country)
    rows.toList.map { case (title, author, url, description, publishedAt, category, country) =>
      sql"""
        INSERT INTO articles
          (author, title, description, url, url_to_image, published_at, content,
           category, shared_categories, country, shared_countries, language)
        VALUES
          ($author, $title, $description, $url, NULL, $publishedAt, NULL,
           $category, ARRAY[]::text[], $country, ARRAY[]::text[], 'en')
        RETURNING id
      """.query[Long].unique.transact(Database.transactor).unsafeRunSync()
    }
  }

  "POST /api/videos/dispatch" should {

    "return 401 without X-API-Key" in {
      requireDb()
      post("/api/videos/dispatch") {
        status shouldBe 401
      }
    }

    "return 401 with the wrong X-API-Key" in {
      requireDb()
      post(
        "/api/videos/dispatch",
        Map.empty[String, String],
        Map("X-API-Key" -> "wrong")
      ) {
        status shouldBe 401
      }
    }

    "return 503 no_fresh_top_story when the window is empty" in {
      requireDb()
      wipeArticles()
      post(
        "/api/videos/dispatch",
        Map.empty[String, String],
        Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 503
        body should include("no_fresh_top_story")
      }
    }

    "return 200 and insert exactly two pending rows on the happy path" in {
      requireDb()
      wipeArticles()
      wipeVideos()
      // Seed 5 multi-publisher politics articles, all in the same
      // (politics, nl, 10:00–14:00) bucket → selector picks them all
      // via Tier 1 (most recent first), so top1 = id 5.
      val baseTs = OffsetDateTime.parse("2026-05-24T10:00:00Z")
      val marker = s"video-dispatch-spec-${UUID.randomUUID().toString.take(6)}"
      val ids = seedArticles(Seq(
        (s"$marker T1", "reuters.com", s"https://x.example/$marker/1",
         s"Body 1 for $marker", baseTs.plusMinutes(10), "politics", "nl"),
        (s"$marker T2", "bbc.com",     s"https://x.example/$marker/2",
         s"Body 2 for $marker", baseTs.plusMinutes(20), "politics", "nl"),
        (s"$marker T3", "guardian.com",s"https://x.example/$marker/3",
         s"Body 3 for $marker", baseTs.plusMinutes(30), "politics", "nl"),
        (s"$marker T4", "ap.example",  s"https://x.example/$marker/4",
         s"Body 4 for $marker", baseTs.plusMinutes(40), "politics", "nl"),
        (s"$marker T5", "afp.example", s"https://x.example/$marker/5",
         s"Body 5 for $marker", baseTs.plusMinutes(50), "politics", "nl")
      ))
      ids should have length 5

      post(
        "/api/videos/dispatch",
        Map.empty[String, String],
        Map("X-API-Key" -> testApiKey)
      ) {
        status shouldBe 200
        val payload = parse(body)
        val createdIds = (payload \ "created_ids").extract[List[Long]]
        createdIds should have length 2

        val rows = sql"""
          SELECT id, anchor, variant, text, status
            FROM top_news_videos
           WHERE id = ANY($createdIds)
           ORDER BY id ASC
        """.query[(Long, String, String, String, String)]
          .to[List]
          .transact(Database.transactor)
          .unsafeRunSync()

        rows should have length 2
        rows.map(_._2).toSet shouldBe Set("erica")
        rows.map(_._3) shouldBe List("v21", "yellow")
        rows.map(_._5).toSet shouldBe Set("pending")

        val v21Text    = rows(0)._4
        val yellowText = rows(1)._4

        v21Text should startWith("Top news of the day. ")
        // top story is the freshest = T5 → "Body 5 for $marker"
        v21Text should include(s"Body 5 for $marker")

        yellowText should startWith("Top 5 news of the day. ")
        // Titles ordered freshest → oldest: T5, T4, T3, T2, T1
        val yellowAfterPrefix = yellowText.stripPrefix("Top 5 news of the day. ")
        val expectedTitlesInOrder = List("T5", "T4", "T3", "T2", "T1").map(t => s"$marker $t")
        expectedTitlesInOrder.foreach { t =>
          yellowAfterPrefix should include(t)
        }
        // Order: each subsequent title's first occurrence > previous one's.
        val positions: List[Int] = expectedTitlesInOrder.map(t => yellowAfterPrefix.indexOf(t))
        positions shouldBe positions.sortWith(_ < _)
      }
    }
  }
}
