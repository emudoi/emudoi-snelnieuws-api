package com.snelnieuws.api

import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.snelnieuws.model.ArticleCreate
import com.typesafe.config.ConfigFactory
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class NewsServletV3Spec
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
  private val stubVerifier = new FirebaseTokenVerifier.Stub(Map.empty)

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig = testConfig,
    apns = Some(stubApns),
    apnsSandbox = None,
    verifierOverride = Some(stubVerifier)
  )

  addServlet(components.newsServletV3, "/v3/*")
  // Mount v2 too so we can register a client_id through the existing
  // /v2/clients/register route — v3 inherits the same gate but doesn't
  // expose its own register endpoint.
  addServlet(components.newsServletV2, "/v2/*")

  private lazy val gateClientId: String = {
    val id = UUID.randomUUID().toString
    val regBody = s"""{
      "clientId":  "$id",
      "bundleId":  "com.emudoi.snelnieuws",
      "osVersion": "iOS 18.0"
    }"""
    post(
      "/v2/clients/register",
      regBody,
      Map("Content-Type" -> "application/json", "X-Client" -> "ios/1.4.0")
    ) {
      assert(status == 200, s"register precondition failed: HTTP $status, body=$body")
    }
    id
  }

  private def gatedHeaders: Map[String, String] = Map(
    "Content-Type" -> "application/json",
    "X-Client"     -> "ios/1.4.0",
    "X-Client-Key" -> gateClientId
  )

  private val v3Tag: String = s"v3-spec-${UUID.randomUUID().toString.take(8)}"

  private def wipeV3Rows(): Unit = {
    import doobie.implicits._
    import cats.effect.unsafe.implicits.global
    sql"DELETE FROM articles WHERE author = $v3Tag"
      .update.run.transact(Database.transactor).unsafeRunSync()
  }

  /** Direct DB insert so we can set `country` + `shared_countries` +
    * `shared_categories` — fields the ArticleCreate / ArticleService write
    * path doesn't surface yet. publishedAt is back-stamped per index so
    * cursor ordering is predictable.
    */
  private def insertV3(
    title: String,
    category: Option[String] = Some("politics"),
    sharedCategories: List[String] = Nil,
    country: Option[String] = Some("nl"),
    sharedCountries: List[String] = Nil,
    ageMinutes: Int = 0
  ): Long = {
    import doobie.implicits._
    import doobie.postgres.implicits._
    import cats.effect.unsafe.implicits.global
    import java.time.OffsetDateTime
    val publishedAt = OffsetDateTime.now().minusMinutes(ageMinutes)
    val sharedCatsArr: Option[List[String]] =
      if (sharedCategories.isEmpty) None else Some(sharedCategories)
    val sharedCntsArr: Option[List[String]] =
      if (sharedCountries.isEmpty) None else Some(sharedCountries)
    sql"""
      INSERT INTO articles (author, title, description, url, url_to_image, published_at,
                            content, category, shared_categories, country, shared_countries)
      VALUES ($v3Tag, $title, NULL,
              ${s"https://example.com/$v3Tag/${UUID.randomUUID()}"},
              NULL, $publishedAt, NULL,
              $category, $sharedCatsArr, $country, $sharedCntsArr)
      RETURNING id
    """.query[Long].unique.transact(Database.transactor).unsafeRunSync()
  }

  private def idsOf(responseBody: String): List[String] = {
    val parsed = org.json4s.jackson.parseJson(responseBody)
    (parsed \ "articles").children.map(a => (a \ "id").extract[String])
  }

  private def field[T: Manifest](responseBody: String, path: String): Option[T] = {
    val parsed = org.json4s.jackson.parseJson(responseBody)
    (parsed \ path).extractOpt[T]
  }

  "GET /v3/feed" should {
    "return 400 when country is missing" in {
      requireDb()
      get("/v3/feed", Map.empty[String, String], gatedHeaders) {
        status shouldBe 400
        body should include("country")
      }
    }

    "return 400 when country is malformed (not 2 lowercase letters)" in {
      requireDb()
      get("/v3/feed", Map("country" -> "USA"), gatedHeaders) {
        status shouldBe 400
      }
      get("/v3/feed", Map("country" -> "n"), gatedHeaders) {
        status shouldBe 400
      }
    }

    "return only articles whose country matches OR shared_countries contains it" in {
      requireDb()
      wipeV3Rows()
      val nlPrimary = insertV3(title = s"$v3Tag-primary-nl", country = Some("nl"))
      val beShared  = insertV3(title = s"$v3Tag-shared-be", country = Some("be"), sharedCountries = List("nl"))
      val deOnly    = insertV3(title = s"$v3Tag-de",        country = Some("de"))

      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        val ids = idsOf(body).toSet
        ids should contain (nlPrimary.toString)
        ids should contain (beShared.toString)
        ids should not contain deOnly.toString
      }
    }

    "paginate via cursor — disjoint pages, has_more=false on last" in {
      requireDb()
      wipeV3Rows()
      // 7 NL articles, monotonically older by ageMinutes so order is stable.
      val ids = (1 to 7).map { i =>
        insertV3(title = s"$v3Tag-page-$i", country = Some("nl"), ageMinutes = i)
      }.toList
      val limit = 3

      val page1Ids = get("/v3/feed", Map("country" -> "nl", "limit" -> limit.toString), gatedHeaders) {
        status shouldBe 200
        field[Boolean](body, "has_more") shouldBe Some(true)
        field[String](body, "next_cursor") should not be empty
        idsOf(body)
      }
      val cursor1 = field[String](
        get("/v3/feed", Map("country" -> "nl", "limit" -> limit.toString), gatedHeaders) { body },
        "next_cursor"
      ).get

      val page2Ids = get(
        "/v3/feed",
        Map("country" -> "nl", "limit" -> limit.toString, "cursor" -> cursor1),
        gatedHeaders
      ) {
        status shouldBe 200
        field[Boolean](body, "has_more") shouldBe Some(true)
        idsOf(body)
      }
      val cursor2 = field[String](
        get(
          "/v3/feed",
          Map("country" -> "nl", "limit" -> limit.toString, "cursor" -> cursor1),
          gatedHeaders
        ) { body },
        "next_cursor"
      ).get

      val page3Ids = get(
        "/v3/feed",
        Map("country" -> "nl", "limit" -> limit.toString, "cursor" -> cursor2),
        gatedHeaders
      ) {
        status shouldBe 200
        // 7 articles, pages 3+3+1 → page 3 has 1 article and has_more=false.
        field[Boolean](body, "has_more") shouldBe Some(false)
        field[String](body, "next_cursor") shouldBe None
        idsOf(body)
      }

      page1Ids.toSet.intersect(page2Ids.toSet) shouldBe empty
      page1Ids.toSet.intersect(page3Ids.toSet) shouldBe empty
      page2Ids.toSet.intersect(page3Ids.toSet) shouldBe empty
      (page1Ids ++ page2Ids ++ page3Ids).toSet should contain allElementsOf ids.map(_.toString).toSet
    }

    "category widening — match via shared_categories" in {
      requireDb()
      wipeV3Rows()
      // category=world, shared_categories=[sports] — should appear under
      // a sports filter even though its primary category is world.
      val widened = insertV3(
        title = s"$v3Tag-widened",
        category = Some("world"),
        sharedCategories = List("sports")
      )
      // A pure politics article should NOT show up when filtering for sports.
      val politicsOnly = insertV3(title = s"$v3Tag-politics-only", category = Some("politics"))

      get("/v3/feed", Map("country" -> "nl", "categories" -> "sports", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        val ids = idsOf(body).toSet
        ids should contain (widened.toString)
        ids should not contain politicsOnly.toString
      }
    }

    "is_local=true when article.country == request.country" in {
      requireDb()
      wipeV3Rows()
      val nlPrimary = insertV3(title = s"$v3Tag-local", country = Some("nl"))
      val beShared  = insertV3(title = s"$v3Tag-shared", country = Some("be"), sharedCountries = List("nl"))

      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        val parsed   = org.json4s.jackson.parseJson(body)
        val articles = (parsed \ "articles").children
        articles.find(a => (a \ "id").extract[String] == nlPrimary.toString)
          .map(a => (a \ "is_local").extract[Boolean]) shouldBe Some(true)
        // Shared-country article is also is_local (the request country is in
        // its shared_countries) — the spec defines is_local as either match.
        articles.find(a => (a \ "id").extract[String] == beShared.toString)
          .map(a => (a \ "is_local").extract[Boolean]) shouldBe Some(true)
      }
    }

    "next_cursor is null when has_more is false" in {
      requireDb()
      wipeV3Rows()
      insertV3(title = s"$v3Tag-only-one", country = Some("nl"))
      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        field[Boolean](body, "has_more") shouldBe Some(false)
        field[String](body, "next_cursor") shouldBe None
      }
    }

    "never serialize shared_categories or shared_countries" in {
      requireDb()
      wipeV3Rows()
      insertV3(
        title = s"$v3Tag-no-leak",
        country = Some("nl"),
        sharedCountries = List("be", "de"),
        sharedCategories = List("sports", "world")
      )
      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        body should not include "shared_countries"
        body should not include "shared_categories"
      }
    }
  }

  "GET /v3/articles/:id" should {
    "return the single article with is_local set" in {
      requireDb()
      wipeV3Rows()
      val id = insertV3(title = s"$v3Tag-by-id", country = Some("nl"))
      get(s"/v3/articles/$id", Map("country" -> "nl"), gatedHeaders) {
        status shouldBe 200
        field[String](body, "id") shouldBe Some(id.toString)
        field[Boolean](body, "is_local") shouldBe Some(true)
      }
    }

    "return 404 for unknown id" in {
      requireDb()
      get("/v3/articles/999999999", Map("country" -> "nl"), gatedHeaders) {
        status shouldBe 404
      }
    }
  }

  "GET /v3/categories" should {
    "return the hardcoded canonical list under the gate" in {
      requireDb()
      get("/v3/categories", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        val list = (org.json4s.jackson.parseJson(body) \ "categories").extract[List[String]]
        list should contain ("politics")
        list should contain ("sports")
      }
    }
  }

  "Gate" should {
    "still apply to /v3/* — 401 without X-Client-Key" in {
      requireDb()
      get("/v3/feed", Map("country" -> "nl"), Map("X-Client" -> "ios/1.4.0")) {
        status shouldBe 401
      }
    }
  }
}
