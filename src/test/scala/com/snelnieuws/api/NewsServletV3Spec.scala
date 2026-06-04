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
         |# Non-empty so v3's urlToImage absolutisation test can assert
         |# a fully-qualified URL on the wire.
         |images.public-base-url = "https://test.example.com"
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

  /** Destructive wipe — used by tests that assert against the full
    * response set (cursor pagination, is_local labeling, next_cursor=null).
    * Other suites in this build seed their own articles, so a hard wipe
    * here doesn't pollute them as long as we re-seed within the test. */
  private def wipeAllArticles(): Unit = {
    import doobie.implicits._
    import cats.effect.unsafe.implicits.global
    sql"DELETE FROM articles".update.run.transact(Database.transactor).unsafeRunSync()
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
    ageMinutes: Int = 0,
    urlToImage: Option[String] = None,
    language: String = "en"
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
                            content, category, shared_categories, country, shared_countries, language)
      VALUES ($v3Tag, $title, NULL,
              ${s"https://example.com/$v3Tag/${UUID.randomUUID()}"},
              $urlToImage, $publishedAt, NULL,
              $category, $sharedCatsArr, $country, $sharedCntsArr, $language)
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

    "country never filters articles out; is_local is suppressed on the wire" in {
      requireDb()
      wipeAllArticles()
      val nlPrimary = insertV3(title = s"$v3Tag-primary-nl", country = Some("nl"))
      val beShared  = insertV3(title = s"$v3Tag-shared-be", country = Some("be"), sharedCountries = List("nl"))
      val deOnly    = insertV3(title = s"$v3Tag-de",        country = Some("de"))
      val noCountry = insertV3(title = s"$v3Tag-untagged",  country = None)

      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        val parsed   = org.json4s.jackson.parseJson(body)
        val articles = (parsed \ "articles").children
        val byId = articles.map(a =>
          (a \ "id").extract[String] -> (a \ "is_local").extract[Boolean]
        ).toMap
        // All four articles must be returned — country is a label, not a filter.
        byId.keySet should contain allOf (
          nlPrimary.toString, beShared.toString, deOnly.toString, noCountry.toString
        )
        // is_local is intentionally suppressed on the wire (always false) so the
        // apps never render the "Local" badge. Locality is still computed and
        // used server-side for ordering — it's just not exposed. See toApi.
        all (byId.values) shouldBe false
      }
    }

    "paginate via cursor — disjoint pages, has_more=false on last" in {
      requireDb()
      wipeAllArticles()
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
      wipeAllArticles()
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

    "is_local is suppressed on the wire even when country matches" in {
      requireDb()
      wipeAllArticles()
      val nlPrimary = insertV3(title = s"$v3Tag-local", country = Some("nl"))
      val beShared  = insertV3(title = s"$v3Tag-shared", country = Some("be"), sharedCountries = List("nl"))

      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        val parsed   = org.json4s.jackson.parseJson(body)
        val articles = (parsed \ "articles").children
        // Both locally match nl, but the wire flag is suppressed (no badge).
        articles.find(a => (a \ "id").extract[String] == nlPrimary.toString)
          .map(a => (a \ "is_local").extract[Boolean]) shouldBe Some(false)
        articles.find(a => (a \ "id").extract[String] == beShared.toString)
          .map(a => (a \ "is_local").extract[Boolean]) shouldBe Some(false)
      }
    }

    "next_cursor is null when has_more is false" in {
      requireDb()
      wipeAllArticles()
      insertV3(title = s"$v3Tag-only-one", country = Some("nl"))
      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        field[Boolean](body, "has_more") shouldBe Some(false)
        field[String](body, "next_cursor") shouldBe None
      }
    }

    "absolutise relative urlToImage paths (and leave absolute ones alone)" in {
      requireDb()
      wipeAllArticles()
      // V2 image-cache scheme stores paths like /v2/images/aa/bb/<hash>.jpg.
      // v3 must prepend imagesPublicBaseUrl so AsyncImage / Coil can fetch.
      val relId = insertV3(
        title = s"$v3Tag-rel-img",
        country = Some("nl"),
        urlToImage = Some("/v2/images/ab/cd/test.jpg")
      )
      val absId = insertV3(
        title = s"$v3Tag-abs-img",
        country = Some("nl"),
        urlToImage = Some("https://elsewhere.example/foo.jpg")
      )
      get("/v3/feed", Map("country" -> "nl", "limit" -> "50"), gatedHeaders) {
        status shouldBe 200
        val parsed   = org.json4s.jackson.parseJson(body)
        val articles = (parsed \ "articles").children
        val byId = articles.map(a =>
          (a \ "id").extract[String] -> (a \ "urlToImage").extractOpt[String]
        ).toMap
        // Relative path resolved through the configured public base URL.
        // In tests, the base URL comes from reference.conf — assert the
        // returned URL is absolute (has a scheme) and ends with the
        // original path so we don't pin the exact base.
        val resolved = byId(relId.toString).getOrElse("")
        resolved should fullyMatch regex "^https?://.*/v2/images/ab/cd/test\\.jpg$"
        // Already-absolute URLs flow through unchanged.
        byId(absId.toString) shouldBe Some("https://elsewhere.example/foo.jpg")
      }
    }

    "never serialize shared_categories or shared_countries" in {
      requireDb()
      wipeAllArticles()
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
    "return the single article with is_local suppressed (false) on the wire" in {
      requireDb()
      wipeAllArticles()
      val id = insertV3(title = s"$v3Tag-by-id", country = Some("nl"))
      get(s"/v3/articles/$id", Map("country" -> "nl"), gatedHeaders) {
        status shouldBe 200
        field[String](body, "id") shouldBe Some(id.toString)
        field[Boolean](body, "is_local") shouldBe Some(false)
      }
    }

    "return an article with NULL country as is_local=false" in {
      requireDb()
      wipeAllArticles()
      val id = insertV3(title = s"$v3Tag-untagged-by-id", country = None)
      get(s"/v3/articles/$id", Map("country" -> "nl"), gatedHeaders) {
        status shouldBe 200
        field[String](body, "id") shouldBe Some(id.toString)
        field[Boolean](body, "is_local") shouldBe Some(false)
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

    "also return a categories_localized sibling field in English by default" in {
      requireDb()
      get("/v3/categories", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        val parsed = org.json4s.jackson.parseJson(body)
        // legacy `categories` slug array stays exactly as before
        val slugs = (parsed \ "categories").extract[List[String]]
        slugs.head shouldBe "politics"
        slugs.last shouldBe "other"
        // new `categories_localized` mirrors the order index-for-index
        val localized = (parsed \ "categories_localized").children
        localized.size shouldBe slugs.size
        ((localized.head \ "code").extract[String], (localized.head \ "name").extract[String]) shouldBe ("politics", "Politics")
        ((localized.last \ "code").extract[String], (localized.last \ "name").extract[String]) shouldBe ("other", "Other")
      }
    }

    "return Dutch names when ?language=nl is supplied" in {
      requireDb()
      get("/v3/categories", Map("language" -> "nl"), gatedHeaders) {
        status shouldBe 200
        val parsed   = org.json4s.jackson.parseJson(body)
        val slugs    = (parsed \ "categories").extract[List[String]]
        slugs.head shouldBe "politics"     // slug array unchanged
        val localized = (parsed \ "categories_localized").children
        (localized.head \ "name").extract[String] shouldBe "Politiek"
      }
    }

    "fall back to English on an unsupported-but-well-formed locale (?language=ja)" in {
      requireDb()
      get("/v3/categories", Map("language" -> "ja"), gatedHeaders) {
        status shouldBe 200
        val parsed    = org.json4s.jackson.parseJson(body)
        val localized = (parsed \ "categories_localized").children
        (localized.head \ "name").extract[String] shouldBe "Politics"
      }
    }

    "400 on malformed locale (?language=XYZ)" in {
      requireDb()
      get("/v3/categories", Map("language" -> "XYZ"), gatedHeaders) {
        status shouldBe 400
      }
    }

    "honor Accept-Language header when no ?language= is supplied" in {
      requireDb()
      get(
        "/v3/categories",
        Map.empty[String, String],
        gatedHeaders + ("Accept-Language" -> "de-DE,en;q=0.9")
      ) {
        status shouldBe 200
        val parsed    = org.json4s.jackson.parseJson(body)
        val localized = (parsed \ "categories_localized").children
        (localized.head \ "name").extract[String] shouldBe "Politik"
      }
    }
  }

  "GET /v3/languages" should {
    "return the 7-language picker in English by default" in {
      requireDb()
      get("/v3/languages", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        val parsed = org.json4s.jackson.parseJson(body)
        val langs  = (parsed \ "languages").children
        langs.size shouldBe 7
        val codes = langs.map(l => (l \ "code").extract[String])
        codes shouldBe List("de", "fr", "it", "en", "es", "pl", "nl")
        val names = langs.map(l => (l \ "name").extract[String])
        names should contain ("German")
        names should contain ("Dutch")
      }
    }

    "render names in Dutch when ?language=nl" in {
      requireDb()
      get("/v3/languages", Map("language" -> "nl"), gatedHeaders) {
        status shouldBe 200
        val langs = (org.json4s.jackson.parseJson(body) \ "languages").children
        val codeToName = langs.map { l =>
          (l \ "code").extract[String] -> (l \ "name").extract[String]
        }.toMap
        codeToName("de") shouldBe "Duits"
        codeToName("nl") shouldBe "Nederlands"
      }
    }

    "honor Accept-Language with region subtag stripped (fr-FR → fr)" in {
      requireDb()
      get(
        "/v3/languages",
        Map.empty[String, String],
        gatedHeaders + ("Accept-Language" -> "fr-FR,en;q=0.9")
      ) {
        status shouldBe 200
        val langs = (org.json4s.jackson.parseJson(body) \ "languages").children
        val deName = langs.find(l => (l \ "code").extract[String] == "de")
          .map(l => (l \ "name").extract[String])
        deName shouldBe Some("Allemand")
      }
    }

    "fall back to English on an unsupported well-formed locale" in {
      requireDb()
      get("/v3/languages", Map("language" -> "ja"), gatedHeaders) {
        status shouldBe 200
        val deName = (org.json4s.jackson.parseJson(body) \ "languages").children
          .find(l => (l \ "code").extract[String] == "de")
          .map(l => (l \ "name").extract[String])
        deName shouldBe Some("German")
      }
    }

    "400 on a malformed locale" in {
      requireDb()
      get("/v3/languages", Map("language" -> "XYZ"), gatedHeaders) {
        status shouldBe 400
      }
    }

    "403 without X-Client (auth gate applies)" in {
      requireDb()
      get("/v3/languages", Map.empty[String, String], Map.empty[String, String]) {
        status shouldBe 403
      }
    }
  }

  "GET /v3/i18n" should {
    "return the English UI bundle by default" in {
      requireDb()
      get("/v3/i18n", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        val parsed = org.json4s.jackson.parseJson(body)
        val strings = parsed \ "strings"
        (strings \ "common.continue").extract[String] shouldBe "Continue"
        (strings \ "onboarding.lang.title").extract[String] shouldBe "Pick your language"
        (strings \ "settings.title").extract[String] shouldBe "Settings"
      }
    }

    "render the Dutch bundle when ?language=nl" in {
      requireDb()
      get("/v3/i18n", Map("language" -> "nl"), gatedHeaders) {
        status shouldBe 200
        val strings = org.json4s.jackson.parseJson(body) \ "strings"
        (strings \ "common.continue").extract[String] shouldBe "Doorgaan"
        (strings \ "onboarding.lang.title").extract[String] shouldBe "Kies je taal"
        (strings \ "settings.title").extract[String] shouldBe "Instellingen"
      }
    }

    "fall back to English on an unsupported well-formed locale" in {
      requireDb()
      get("/v3/i18n", Map("language" -> "ja"), gatedHeaders) {
        status shouldBe 200
        val strings = org.json4s.jackson.parseJson(body) \ "strings"
        (strings \ "common.continue").extract[String] shouldBe "Continue"
      }
    }

    "400 on a malformed locale" in {
      requireDb()
      get("/v3/i18n", Map("language" -> "XYZ"), gatedHeaders) {
        status shouldBe 400
      }
    }

    "403 without X-Client (auth gate applies)" in {
      requireDb()
      get("/v3/i18n", Map.empty[String, String], Map.empty[String, String]) {
        status shouldBe 403
      }
    }
  }

  "GET /v3/feed language filter" should {
    "default to English when no ?language= and no Accept-Language" in {
      requireDb()
      wipeV3Rows()
      val enId = insertV3("en-only", language = "en")
      val nlId = insertV3("nl-only", language = "nl")
      get("/v3/feed", Map("country" -> "nl"), gatedHeaders) {
        status shouldBe 200
        val ids = idsOf(body)
        ids should contain (enId.toString)
        ids shouldNot contain (nlId.toString)
        // Every returned article carries language=en
        val langs = (org.json4s.jackson.parseJson(body) \ "articles").children
          .map(a => (a \ "language").extract[String])
        langs.foreach(_ shouldBe "en")
      }
    }

    "filter by ?language=nl" in {
      requireDb()
      wipeV3Rows()
      val enId = insertV3("en-only-2", language = "en")
      val nlId = insertV3("nl-only-2", language = "nl")
      get("/v3/feed", Map("country" -> "nl", "language" -> "nl"), gatedHeaders) {
        status shouldBe 200
        val ids = idsOf(body)
        ids should contain (nlId.toString)
        ids shouldNot contain (enId.toString)
      }
    }

    "honor Accept-Language primary subtag (nl-NL → nl)" in {
      requireDb()
      wipeV3Rows()
      val enId = insertV3("en-only-3", language = "en")
      val nlId = insertV3("nl-only-3", language = "nl")
      get(
        "/v3/feed",
        Map("country" -> "nl"),
        gatedHeaders + ("Accept-Language" -> "nl-NL,en;q=0.9")
      ) {
        status shouldBe 200
        val ids = idsOf(body)
        ids should contain (nlId.toString)
        ids shouldNot contain (enId.toString)
      }
    }

    "?language= beats Accept-Language" in {
      requireDb()
      wipeV3Rows()
      val enId = insertV3("en-prefers-q", language = "en")
      val nlId = insertV3("nl-only-q", language = "nl")
      get(
        "/v3/feed",
        Map("country" -> "nl", "language" -> "en"),
        gatedHeaders + ("Accept-Language" -> "nl-NL")
      ) {
        status shouldBe 200
        val ids = idsOf(body)
        ids should contain (enId.toString)
        ids shouldNot contain (nlId.toString)
      }
    }

    "return empty when no rows in the requested language (no error)" in {
      requireDb()
      wipeV3Rows()
      insertV3("only-en", language = "en")
      get("/v3/feed", Map("country" -> "nl", "language" -> "fr"), gatedHeaders) {
        status shouldBe 200
        idsOf(body) shouldBe Nil
      }
    }

    "400 on malformed ?language= value" in {
      requireDb()
      get("/v3/feed", Map("country" -> "nl", "language" -> "XYZ"), gatedHeaders) {
        status shouldBe 400
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

  // Personalised feed coverage for v3.
  //
  // Pre-fix (commit 67c4a89, v3 migration), NewsServletV3 called
  // articleRepository.findV3 directly and bypassed the personalised-
  // feed read path entirely. The `personalised_feed_enabled` flag
  // was on in production, app_clients.last_served_ids kept getting
  // written by old v2 calls (which stopped happening when the apps
  // migrated to v3), but v3 ignored both. Users saw the same top-of-
  // feed articles on every fetch.
  //
  // Tests below verify the restored behaviour:
  //   1. Flag off → /v3/feed returns the cursor-paginated default.
  //      next_cursor is non-null when has_more.
  //   2. Flag on + valid client_id → first fetch returns up to N
  //      articles AND records them in app_clients.last_served_ids.
  //   3. Flag on, second fetch → returns the NEXT batch (the ones
  //      not in served_ids); the first batch's IDs do NOT reappear.
  //   4. Flag on, served_ids covers entire pool → server resets
  //      served_ids to the freshly-served top N (rotation cycle).

  private def setPersonalisedFlag(enabled: Boolean): Unit = {
    import doobie.implicits._
    import cats.effect.unsafe.implicits.global
    sql"UPDATE feature_flags SET is_enabled = $enabled WHERE feature = 'personalised_feed_enabled'"
      .update.run.transact(Database.transactor).unsafeRunSync()
  }

  private def readServedIds(): List[Long] = {
    import doobie.implicits._
    import doobie.postgres.implicits._
    import cats.effect.unsafe.implicits.global
    import java.util.UUID
    val cid: UUID = UUID.fromString(gateClientId)
    val raw: Option[String] =
      sql"SELECT last_served_ids::text FROM app_clients WHERE client_id = $cid"
        .query[Option[String]].unique.transact(Database.transactor).unsafeRunSync()
    raw match {
      case Some(json) if json.nonEmpty =>
        org.json4s.jackson.parseJson(json).extract[List[Long]]
      case _ => Nil
    }
  }

  private def resetServedIds(): Unit = {
    import doobie.implicits._
    import doobie.postgres.implicits._
    import cats.effect.unsafe.implicits.global
    import java.util.UUID
    val cid: UUID = UUID.fromString(gateClientId)
    val emptyJson = "[]"
    sql"UPDATE app_clients SET last_served_ids = $emptyJson::jsonb WHERE client_id = $cid"
      .update.run.transact(Database.transactor).unsafeRunSync()
  }

  "GET /v3/feed (personalised)" should {
    "with flag OFF: paginate by cursor and NOT mark served_ids" in {
      requireDb()
      setPersonalisedFlag(false)
      resetServedIds()
      wipeAllArticles()
      (1 to 6).foreach { i =>
        insertV3(title = s"$v3Tag-flagoff-$i", country = Some("nl"), ageMinutes = i)
      }
      get("/v3/feed", Map("country" -> "nl", "limit" -> "3"), gatedHeaders) {
        status shouldBe 200
        idsOf(body) should have size 3
        // Cursor-based pagination → next_cursor non-null when more.
        field[String](body, "next_cursor") shouldBe defined
      }
      readServedIds() shouldBe empty
    }

    "with flag ON: first fetch returns N articles AND populates last_served_ids" in {
      requireDb()
      setPersonalisedFlag(true)
      resetServedIds()
      wipeAllArticles()
      val seeded = (1 to 6).map { i =>
        insertV3(title = s"$v3Tag-pers-$i", country = Some("nl"), ageMinutes = i)
      }.toList
      val firstBatch: List[Long] =
        get("/v3/feed", Map("country" -> "nl", "limit" -> "3"), gatedHeaders) {
          status shouldBe 200
          val ids = idsOf(body).map(_.toLong)
          ids should have size 3
          // Personalised path: cursor must be NULL (server's served_ids
          // set IS the pagination state).
          field[String](body, "next_cursor") shouldBe empty
          ids
        }
      readServedIds().toSet should contain allElementsOf firstBatch.toSet
      setPersonalisedFlag(false)  // cleanup
    }

    "with flag ON + served_ids present: second fetch excludes the first batch" in {
      requireDb()
      setPersonalisedFlag(true)
      resetServedIds()
      wipeAllArticles()
      (1 to 6).foreach { i =>
        insertV3(title = s"$v3Tag-pers2-$i", country = Some("nl"), ageMinutes = i)
      }
      val firstBatch =
        get("/v3/feed", Map("country" -> "nl", "limit" -> "3"), gatedHeaders) {
          status shouldBe 200
          idsOf(body).map(_.toLong)
        }
      val secondBatch =
        get("/v3/feed", Map("country" -> "nl", "limit" -> "3"), gatedHeaders) {
          status shouldBe 200
          idsOf(body).map(_.toLong)
        }
      firstBatch.toSet.intersect(secondBatch.toSet) shouldBe empty
      // served_ids must contain BOTH batches after the two calls.
      readServedIds().toSet should contain allElementsOf (firstBatch.toSet ++ secondBatch.toSet)
      setPersonalisedFlag(false)
    }

    "with flag ON + pool exhausted: resets served_ids and serves top N (rotation)" in {
      requireDb()
      setPersonalisedFlag(true)
      resetServedIds()
      wipeAllArticles()
      // Seed exactly 3 articles. Serve all 3 (fills served_ids), then
      // serve again — the second call must rotate (reset served_ids
      // to the freshly-served top 3) and return them again.
      val seeded = (1 to 3).map { i =>
        insertV3(title = s"$v3Tag-rotate-$i", country = Some("nl"), ageMinutes = i)
      }.toList
      val firstBatch =
        get("/v3/feed", Map("country" -> "nl", "limit" -> "3"), gatedHeaders) {
          status shouldBe 200
          idsOf(body).map(_.toLong)
        }
      firstBatch.toSet shouldBe seeded.toSet
      val secondBatch =
        get("/v3/feed", Map("country" -> "nl", "limit" -> "3"), gatedHeaders) {
          status shouldBe 200
          idsOf(body).map(_.toLong)
        }
      // Rotation: pool was exhausted (all 3 in served_ids), reset
      // fires, the top 3 are served again. They're the SAME 3.
      secondBatch.toSet shouldBe seeded.toSet
      // served_ids now contains the rotated set, NOT a superset.
      readServedIds().toSet shouldBe seeded.toSet
      setPersonalisedFlag(false)
    }
  }

  // POST /v3/feed/semantic — regression coverage for the
  // 2026-05-22 user-reported "Dutch + custom feed → English news"
  // bug. The iOS + Android clients send `language` in the request
  // BODY (matching the rest of the semantic-feed body-shaped
  // params). Pre-PR #11 the server only read URL query/Accept-
  // Language and fell back to "en", filtering the PG JOIN to
  // English articles. Tests below assert the new behaviour:
  //   1. body.language scopes the JOIN
  //   2. URL ?language= still works as a fallback
  //   3. missing/invalid language → 400
  //   4. embedding length mismatch → 400 (existing behaviour, but
  //      worth pinning down to the new code path)
  "POST /v3/feed/semantic" should {

    /** Make a 1024-dim placeholder embedding. The actual values
      * don't matter for these tests — the bridge call is stubbed
      * via ingestionApiClient (returns empty matches in this test
      * harness because there's no in-cluster ingestion-api), so
      * fallback always fires and we assert on the JOIN-language
      * portion. */
    val dummyEmb: String =
      List.fill(1024)("0.0").mkString("[", ",", "]")

    "scope the fallback feed by language read from request body" in {
      requireDb()
      wipeV3Rows()
      val enId = insertV3(title = "EN article",
        country = Some("nl"), language = "en")
      val nlId = insertV3(title = "NL artikel",
        country = Some("nl"), language = "nl")

      val body =
        s"""{"embedding":$dummyEmb,"language":"nl","limit":10}"""
      post(
        uri = "/v3/feed/semantic?country=nl",
        body = body.getBytes("UTF-8"),
        headers = gatedHeaders
      ) {
        status shouldBe 200
        val ids = idsOf(this.body).map(_.toLong)
        // Only the nl-tagged article should come back, not the en
        // one. Pre-fix this returned the en article because
        // resolveLanguage() defaulted to "en".
        ids should contain (nlId)
        ids should not contain enId
      }
      wipeV3Rows()
    }

    "fall back to URL ?language= when body omits it" in {
      requireDb()
      wipeV3Rows()
      val enId = insertV3(title = "EN article",
        country = Some("nl"), language = "en")
      val deId = insertV3(title = "DE Artikel",
        country = Some("nl"), language = "de")

      val body = s"""{"embedding":$dummyEmb,"limit":10}"""
      post(
        uri = "/v3/feed/semantic?country=nl&language=de",
        body = body.getBytes("UTF-8"),
        headers = gatedHeaders
      ) {
        status shouldBe 200
        val ids = idsOf(this.body).map(_.toLong)
        ids should contain (deId)
        ids should not contain enId
      }
      wipeV3Rows()
    }

    "prefer body language over URL ?language= when both present" in {
      requireDb()
      wipeV3Rows()
      val enId = insertV3(title = "EN article",
        country = Some("nl"), language = "en")
      val frId = insertV3(title = "FR article",
        country = Some("nl"), language = "fr")

      // Body says fr, URL says en. Body should win.
      val body = s"""{"embedding":$dummyEmb,"language":"fr","limit":10}"""
      post(
        uri = "/v3/feed/semantic?country=nl&language=en",
        body = body.getBytes("UTF-8"),
        headers = gatedHeaders
      ) {
        status shouldBe 200
        val ids = idsOf(this.body).map(_.toLong)
        ids should contain (frId)
        ids should not contain enId
      }
      wipeV3Rows()
    }

    "return 400 when body.language is not in the supported set" in {
      requireDb()
      val body =
        s"""{"embedding":$dummyEmb,"language":"xx","limit":10}"""
      post(
        uri = "/v3/feed/semantic?country=nl",
        body = body.getBytes("UTF-8"),
        headers = gatedHeaders
      ) {
        status shouldBe 400
        this.body should include("language")
      }
    }

    "return 400 when embedding is not 1024-dim" in {
      requireDb()
      val shortEmb = List.fill(512)("0.0").mkString("[", ",", "]")
      val body =
        s"""{"embedding":$shortEmb,"language":"nl","limit":10}"""
      post(
        uri = "/v3/feed/semantic?country=nl",
        body = body.getBytes("UTF-8"),
        headers = gatedHeaders
      ) {
        status shouldBe 400
        this.body should include("1024")
      }
    }
  }
}
