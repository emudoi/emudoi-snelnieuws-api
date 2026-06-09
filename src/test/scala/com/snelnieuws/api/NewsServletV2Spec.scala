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
    apnsSandbox = None,
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

    "accept android/<v> as a valid X-Client (regex relaxed)" in {
      requireDb()
      // Falls through to the X-Client-Key check (no key sent → 401, not
      // the 403 the regex would otherwise emit). Confirms the platform
      // gate now permits Android in addition to iOS.
      get("/v2/everything", Map.empty[String, String], Map("X-Client" -> "android/1.4.0")) {
        status shouldBe 401
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

  "GET /v2/feed" should {
    // Distinct titles per insert so we can identify our own rows in the
    // response body without depending on what else is in the DB. Categories
    // chosen from Categories.all to satisfy the canonical-name validation.
    "filter articles to the supplied categories" in {
      requireDb()
      val politicsTitle = s"feed-spec-politics-${UUID.randomUUID()}"
      val sportsTitle   = s"feed-spec-sports-${UUID.randomUUID()}"
      components.articleService.create(ArticleCreate(
        author = Some("feed-spec"), title = politicsTitle, description = None,
        url = s"https://example.com/feed-spec/${UUID.randomUUID()}",
        urlToImage = None, content = None, category = Some("politics")
      )) shouldBe a[Right[_, _]]
      components.articleService.create(ArticleCreate(
        author = Some("feed-spec"), title = sportsTitle, description = None,
        url = s"https://example.com/feed-spec/${UUID.randomUUID()}",
        urlToImage = None, content = None, category = Some("sports")
      )) shouldBe a[Right[_, _]]

      get("/v2/feed", Map("categories" -> "politics"), gatedHeaders) {
        status shouldBe 200
        body should include("\"status\":\"ok\"")
        body should include(politicsTitle)
        body should not include sportsTitle
      }
    }

    "silently drop unknown category names but use the valid ones" in {
      requireDb()
      get("/v2/feed", Map("categories" -> "politics,not-a-real-category"), gatedHeaders) {
        status shouldBe 200
        body should include("\"status\":\"ok\"")
      }
    }

    "return 400 when all supplied categories are unknown" in {
      requireDb()
      get("/v2/feed", Map("categories" -> "not-real,also-not-real"), gatedHeaders) {
        status shouldBe 400
      }
    }

    "return 400 when the categories param is missing or empty" in {
      requireDb()
      get("/v2/feed", Map.empty[String, String], gatedHeaders) {
        status shouldBe 400
      }
      get("/v2/feed", Map("categories" -> ""), gatedHeaders) {
        status shouldBe 400
      }
    }

    "return 401 without X-Client-Key (gate still applies)" in {
      requireDb()
      get("/v2/feed", Map("categories" -> "politics"), Map("X-Client" -> "ios/1.4.0")) {
        status shouldBe 401
      }
    }
  }

  "GET /v2/categories" should {
    "return the hardcoded canonical list under the full gate" in {
      requireDb()
      get("/v2/categories", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        val list = (org.json4s.jackson.parseJson(body) \ "categories").extract[List[String]]
        // Assert against the source of truth (Categories.all) so this can't go
        // stale again when the snelmind taxonomy is expanded — the previous
        // hardcoded 13-item literal drifted when the list grew to 21 (3aab6fb).
        list shouldBe com.snelnieuws.model.Categories.all.toList
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

  "GET /v2/app/config/android" should {
    "return minVersionCode and minVersionName" in {
      get("/v2/app/config/android", Map.empty[String, String], gatedHeaders) {
        status shouldBe 200
        body should include("minVersionCode")
        body should include("minVersionName")
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

  "PUT /v2/users/me/categories + GET /v2/users/me/categories" should {
    "round-trip a valid list" in {
      requireDb()
      // Use a fresh uid so we don't collide with state left by other tests.
      // alice + bob are the two stub-mapped tokens; bob is unused elsewhere
      // for users-row writes, so a clean slate.
      post("/v2/users", """{"email":"bob@example.com"}""", withAuth("bob-token")) {
        status shouldBe 200
      }
      put(
        "/v2/users/me/categories",
        """{"categories":["politics","finance","health"]}""",
        withAuth("bob-token")
      ) {
        status shouldBe 200
      }
      get("/v2/users/me/categories", Map.empty[String, String], withAuth("bob-token")) {
        status shouldBe 200
        val list = (org.json4s.jackson.parseJson(body) \ "categories").extract[List[String]]
        list shouldBe List("politics", "finance", "health")
      }
    }

    "accept an empty list (Skip-mode equivalent)" in {
      requireDb()
      post("/v2/users", """{"email":"bob@example.com"}""", withAuth("bob-token")) {
        status shouldBe 200
      }
      put(
        "/v2/users/me/categories",
        """{"categories":[]}""",
        withAuth("bob-token")
      ) {
        status shouldBe 200
      }
      // GET returns Some([]) — the route maps it to a 200 with empty list,
      // distinct from 404 which means "never saved".
      get("/v2/users/me/categories", Map.empty[String, String], withAuth("bob-token")) {
        status shouldBe 200
        val list = (org.json4s.jackson.parseJson(body) \ "categories").extract[List[String]]
        list shouldBe empty
      }
    }

    "reject non-canonical entries with 400" in {
      requireDb()
      post("/v2/users", """{"email":"bob@example.com"}""", withAuth("bob-token")) {
        status shouldBe 200
      }
      put(
        "/v2/users/me/categories",
        """{"categories":["politics","not-a-real-category"]}""",
        withAuth("bob-token")
      ) {
        status shouldBe 400
        body should include("not-a-real-category")
      }
    }

    "PUT returns 401 without Bearer (gate passes, route auth fails)" in {
      requireDb()
      put("/v2/users/me/categories", """{"categories":[]}""", gatedHeaders) {
        status shouldBe 401
      }
    }

    "GET returns 404 when the user has never saved a list" in {
      requireDb()
      // Force the column to NULL so the test is order-independent —
      // earlier tests using alice-token may have written rows but only
      // the categories column matters here.
      import doobie.implicits._
      import cats.effect.unsafe.implicits.global
      post("/v2/users", """{"email":"alice@example.com"}""", withAuth("alice-token")) {
        status shouldBe 200
      }
      sql"UPDATE users SET selected_categories = NULL WHERE id = 'uid-alice'"
        .update.run.transact(com.snelnieuws.db.Database.transactor).unsafeRunSync()

      get("/v2/users/me/categories", Map.empty[String, String], withAuth("alice-token")) {
        status shouldBe 404
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

    "also clean up Android subscriptions linked to the user" in {
      requireDb()
      val uid = "uid-bob"
      // Bob exists.
      post("/v2/users", """{"email":"bob@example.com"}""", withAuth("bob-token")) {
        status shouldBe 200
      }
      // Seed both an iOS subscription and an Android subscription under bob.
      post(
        "/v2/notifications/subscribe",
        """{"deviceId":"bob-ios-dev","apnsToken":"bob-ios-token","frequency":3}""",
        withAuth("bob-token")
      ) {
        status shouldBe 200
      }
      components.androidNotificationSubscriptionRepository.upsert(
        deviceId  = "bob-and-dev",
        fcmToken  = "bob-and-token",
        frequency = 4,
        userId    = Some(uid)
      ) shouldBe a[Right[_, _]]

      // Sanity: both rows exist before delete.
      components.androidNotificationSubscriptionRepository
        .lastFrequencyByUserId(uid) shouldBe Right(Some(4))

      delete("/v2/users/me", Map.empty[String, String], withAuth("bob-token")) {
        status shouldBe 204
      }

      // iOS row gone via FK cascade; Android row gone via the new explicit
      // cleanup in UserService.delete.
      components.androidNotificationSubscriptionRepository
        .lastFrequencyByUserId(uid) shouldBe Right(None)
    }
  }

  "GET /v2/users/me/last-preference" should {
    "route to the iOS table when called with an iOS X-Client" in {
      requireDb()
      // alice already exists from the DELETE spec setup; reuse her uid.
      // Seed alice with an iOS subscription at freq=2 and an Android
      // subscription at freq=4. last-preference must return 2 for an iOS
      // X-Client and 4 for an Android X-Client.
      post("/v2/users", """{"email":"alice@example.com"}""", withAuth("alice-token")) {
        status shouldBe 200
      }
      components.notificationSubscriptionRepository.upsert(
        deviceId    = "alice-ios-prefdev",
        apnsToken   = "alice-ios-prefdev-apns",
        frequency   = 2,
        environment = "production",
        userId      = Some("uid-alice")
      ) shouldBe a[Right[_, _]]
      components.androidNotificationSubscriptionRepository.upsert(
        deviceId  = "alice-and-prefdev",
        fcmToken  = "alice-and-prefdev-fcm",
        frequency = 4,
        userId    = Some("uid-alice")
      ) shouldBe a[Right[_, _]]

      // iOS X-Client → iOS frequency.
      get("/v2/users/me/last-preference", Map.empty[String, String], withAuth("alice-token")) {
        status shouldBe 200
        body should include("\"frequency\":2")
      }
    }

    "route to the Android table when called with an Android X-Client" in {
      requireDb()
      // Same setup as the previous case (reuses the seeded rows). Switch
      // the X-Client header to android/* and confirm we now read the
      // android_notification_subscriptions row.
      val androidHeaders = Map(
        "Content-Type"  -> "application/json",
        "X-Client"      -> "android/1.4.0",
        "X-Client-Key"  -> gateClientId,
        "Authorization" -> "Bearer alice-token"
      )
      get("/v2/users/me/last-preference", Map.empty[String, String], androidHeaders) {
        status shouldBe 200
        body should include("\"frequency\":4")
      }
    }

    "return 404 when the platform-specific table has no rows for the user" in {
      requireDb()
      // bob has been deleted in the cross-platform DELETE test above.
      // Re-create bob without any subscriptions.
      post("/v2/users", """{"email":"bob@example.com"}""", withAuth("bob-token")) {
        status shouldBe 200
      }
      get("/v2/users/me/last-preference", Map.empty[String, String], withAuth("bob-token")) {
        status shouldBe 404
      }
    }
  }

  // ─────────────────────── Personalised feed (Phase 5) ───────────────────
  //
  // These tests rely on the personalised_feed_enabled feature flag. The
  // helpers register a fresh client per test so they're insensitive to
  // any served-id history left by suite-level state.

  private val PersonalisedFlag = "personalised_feed_enabled"

  private def freshClient(): String = {
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
      assert(status == 200, s"register failed: HTTP $status, body=$body")
    }
    id
  }

  private def headersFor(cid: String): Map[String, String] = Map(
    "Content-Type"  -> "application/json",
    "X-Client"      -> "ios/1.4.0",
    "X-Client-Key"  -> cid
  )

  private def idsOf(responseBody: String): List[String] = {
    val parsed = org.json4s.jackson.parseJson(responseBody)
    (parsed \ "articles").children.map(a => (a \ "id").extract[String])
  }

  private def seedArticles(n: Int, category: String, tag: String): Unit = {
    (1 to n).foreach { i =>
      components.articleService.create(com.snelnieuws.model.ArticleCreate(
        author      = Some(tag),
        title       = s"$tag-$category-$i-${UUID.randomUUID()}",
        description = None,
        url         = s"https://example.com/$tag/$category/$i",
        urlToImage  = None,
        content     = None,
        category    = Some(category)
      )) shouldBe a[Right[_, _]]
    }
  }

  "Personalised feed — flag off (regression guard)" should {
    "return byte-identical id sets across two consecutive calls" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
      val cid = freshClient()
      val tag = s"pf-flag-off-${UUID.randomUUID().toString.take(8)}"
      seedArticles(20, "technology", tag)

      val firstIds  = get("/v2/everything", Map.empty, headersFor(cid)) {
        status shouldBe 200
        idsOf(body)
      }
      val secondIds = get("/v2/everything", Map.empty, headersFor(cid)) {
        status shouldBe 200
        idsOf(body)
      }
      // Order can differ because interleaveBySource shuffles by source, but
      // the *set* of returned ids must match exactly when the filter is off.
      firstIds.toSet shouldBe secondIds.toSet
    }
  }

  "Personalised feed — flag on" should {
    "rotate /v2/everything across two calls" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cid = freshClient()
      val tag = s"pf-everything-${UUID.randomUUID().toString.take(8)}"
      seedArticles(250, "technology", tag)

      val first  = get("/v2/everything", Map.empty, headersFor(cid)) { idsOf(body) }
      val second = get("/v2/everything", Map.empty, headersFor(cid)) { idsOf(body) }
      first.toSet.intersect(second.toSet) shouldBe empty
      // Reset flag so unrelated tests aren't surprised.
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "rotate /v2/feed across two calls" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cid = freshClient()
      val tag = s"pf-feed-${UUID.randomUUID().toString.take(8)}"
      seedArticles(150, "politics", tag)
      seedArticles(150, "economy",  tag)

      val first  = get("/v2/feed", Map("categories" -> "politics,economy"), headersFor(cid)) { idsOf(body) }
      val second = get("/v2/feed", Map("categories" -> "politics,economy"), headersFor(cid)) { idsOf(body) }
      first.toSet.intersect(second.toSet) shouldBe empty
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "rotate /v2/top-headlines across two calls" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cid = freshClient()
      val tag = s"pf-top-${UUID.randomUUID().toString.take(8)}"
      seedArticles(250, "sports", tag)

      val first  = get("/v2/top-headlines", Map("category" -> "sports"), headersFor(cid)) { idsOf(body) }
      val second = get("/v2/top-headlines", Map("category" -> "sports"), headersFor(cid)) { idsOf(body) }
      first.toSet.intersect(second.toSet) shouldBe empty
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "bypass personalisation for free-text search q=trump" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cid = freshClient()
      val tag = s"pf-search-${UUID.randomUUID().toString.take(8)}"
      // Seed articles whose title contains 'trump' so the search hits.
      (1 to 5).foreach { i =>
        components.articleService.create(com.snelnieuws.model.ArticleCreate(
          author = Some(tag),
          title  = s"$tag-trump-piece-$i-${UUID.randomUUID()}",
          description = None,
          url = s"https://example.com/$tag/search/$i",
          urlToImage = None, content = None, category = Some("politics")
        )) shouldBe a[Right[_, _]]
      }
      // q=trump is not in Categories.all → servlet routes to bypass.
      val first  = get("/v2/everything", Map("q" -> "trump"), headersFor(cid)) { idsOf(body).toSet }
      val second = get("/v2/everything", Map("q" -> "trump"), headersFor(cid)) { idsOf(body).toSet }
      first shouldBe second
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "personalise q=politics (a canonical category name)" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cid = freshClient()
      val tag = s"pf-cat-q-${UUID.randomUUID().toString.take(8)}"
      seedArticles(150, "politics", tag)
      val first  = get("/v2/everything", Map("q" -> "politics"), headersFor(cid)) { idsOf(body).toSet }
      val second = get("/v2/everything", Map("q" -> "politics"), headersFor(cid)) { idsOf(body).toSet }
      first.intersect(second) shouldBe empty
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "isolate served history across two clients" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cidA = freshClient()
      val cidB = freshClient()
      val tag = s"pf-iso-${UUID.randomUUID().toString.take(8)}"
      seedArticles(250, "technology", tag)

      val a1 = get("/v2/everything", Map.empty, headersFor(cidA)) { idsOf(body).toSet }
      val b1 = get("/v2/everything", Map.empty, headersFor(cidB)) { idsOf(body).toSet }
      // First calls draw from the same top-N pool, so the *sets* match.
      a1 shouldBe b1

      val a2 = get("/v2/everything", Map.empty, headersFor(cidA)) { idsOf(body).toSet }
      val b2 = get("/v2/everything", Map.empty, headersFor(cidB)) { idsOf(body).toSet }
      a2.intersect(a1) shouldBe empty
      b2.intersect(b1) shouldBe empty
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "cycle through reset when the pool is small (no empty responses)" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cid = freshClient()
      val tag = s"pf-reset-${UUID.randomUUID().toString.take(8)}"
      // The DB already has rows from other tests, so we can't make the
      // *pool* small. Instead drive the same client through three
      // consecutive calls and assert none are empty.
      seedArticles(50, "culture", tag)
      val n1 = get("/v2/everything", Map.empty, headersFor(cid)) { idsOf(body).size }
      val n2 = get("/v2/everything", Map.empty, headersFor(cid)) { idsOf(body).size }
      val n3 = get("/v2/everything", Map.empty, headersFor(cid)) { idsOf(body).size }
      // Each call returned *something*. The reset path activates if a
      // client exhausts the pool; in all cases the response has > 0
      // articles since the DB has > 0 articles.
      List(n1, n2, n3).foreach(_ should be > 0)
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "still enforce the gate (401 without X-Client-Key when flag on)" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      get("/v2/everything", Map.empty, Map("X-Client" -> "ios/1.4.0")) {
        status shouldBe 401
      }
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }

    "bump last_seen_at on the request even when filter is active (regression)" in {
      requireDb()
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = true)
      val cid = freshClient()
      val cidUuid = UUID.fromString(cid)
      val tag = s"pf-markseen-${UUID.randomUUID().toString.take(8)}"
      seedArticles(20, "world", tag)

      import doobie.implicits._
      import doobie.postgres.implicits._
      import cats.effect.unsafe.implicits.global
      import java.time.OffsetDateTime
      // Force last_seen_at into the past so we can detect a bump.
      sql"UPDATE app_clients SET last_seen_at = NOW() - INTERVAL '1 hour' WHERE client_id = $cidUuid"
        .update.run.transact(com.snelnieuws.db.Database.transactor).unsafeRunSync()

      get("/v2/everything", Map.empty, headersFor(cid)) { status shouldBe 200 }

      val seenAfter: OffsetDateTime = sql"SELECT last_seen_at FROM app_clients WHERE client_id = $cidUuid"
        .query[OffsetDateTime].unique.transact(com.snelnieuws.db.Database.transactor).unsafeRunSync()
      // Bumped to "now" — must be within the last few seconds.
      val secondsAgo = java.time.Duration.between(seenAfter, OffsetDateTime.now()).getSeconds
      secondsAgo should be < 60L
      components.featureFlagRepository.setEnabled(PersonalisedFlag, enabled = false)
    }
  }
}
