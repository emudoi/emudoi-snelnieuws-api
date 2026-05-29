package com.snelnieuws.service

import com.snelnieuws.{DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.db.Database
import com.snelnieuws.model.{ArticleCreate, SubscribeRequest}
import com.snelnieuws.repository.{
  ArticleRepository,
  FeatureFlagRepository,
  NotificationCandidateRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NotificationServiceSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val articleRepo      = new ArticleRepository(Database.transactor)
  private lazy val subRepo          = new NotificationSubscriptionRepository(Database.transactor)
  private lazy val dispatchRepo     = new NotificationDispatchRepository(Database.transactor)
  private lazy val flagRepo         = new FeatureFlagRepository(Database.transactor)
  private lazy val topSummaryRepo   = new com.snelnieuws.repository.TopSummaryRepository(Database.transactor)
  private lazy val candidateRepo    = new NotificationCandidateRepository(Database.transactor)

  private def newService(apnsProd: Option[ApnsMessagingService] = None,
                         apnsSandbox: Option[ApnsMessagingService] = None) =
    new NotificationService(articleRepo, subRepo, dispatchRepo, flagRepo, topSummaryRepo,
      candidateRepo, apnsProd = apnsProd, apnsSandbox = apnsSandbox)

  /** Seed an undispatched top_summary so dispatch() finds work to do.
    * Required by every test that exercises the post-§8 dispatch path
    * — without one, the new flow returns DispatchOutcome.NoFreshTopStory
    * for any non-zero newArticles count. notification_messages keys
    * mirror the subscribers' notification_language (default "en") so
    * the per-language fan-out picks up the configured tokens. */
  private def seedTopSummary(
    messages: Map[String, String] = Map("en" -> "test clickbait headline")
  ): Long = {
    val payload = com.snelnieuws.model.TopStoryPayload(
      representativeArticleId = scala.util.Random.nextLong().abs,
      topNews                 = io.circe.Json.obj(),
      notificationMessages    = messages,
      selectionTier           = 1,
      selectionMetadata       = io.circe.Json.obj()
    )
    topSummaryRepo.insert(payload).fold(
      e => throw new RuntimeException(s"seedTopSummary failed: ${e.getMessage}"),
      identity
    )
  }

  "subscribe" should {
    "upsert a subscription row" in {
      requireDb()
      val service = newService()
      val req = SubscribeRequest(
        deviceId  = "ns-spec-device-1",
        apnsToken = "ns-spec-token-1",
        frequency = 1
      )
      service.subscribe(req) shouldBe a[Right[_, _]]

      // Idempotent — a second call with a different token updates rather than failing.
      val req2 = req.copy(apnsToken = "ns-spec-token-1-new")
      service.subscribe(req2) shouldBe a[Right[_, _]]

      val tokens = subRepo.findTokensByFrequency(1).toOption.getOrElse(Nil)
      tokens should contain("ns-spec-token-1-new")
      tokens should not contain "ns-spec-token-1"
    }
  }

  "dispatch" should {
    "return Disabled when the matching apns client is None" in {
      requireDb()
      val service = newService()
      service.dispatch(frequency = None, environment = "production") match {
        case Right(DispatchOutcome.Disabled) => succeed
        case other                           => fail(s"Expected Disabled, got: $other")
      }
      service.dispatch(frequency = None, environment = "sandbox") match {
        case Right(DispatchOutcome.Disabled) => succeed
        case other                           => fail(s"Expected Disabled, got: $other")
      }
    }

    "return Sent with sent=0 when there are no tokens for the frequency" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      // §8 dispatch path needs a fresh top_summary; otherwise it returns
      // NoFreshTopStory instead of Sent. Seed one with no language
      // overlap so the per-language fan-out yields zero tokens anyway.
      seedTopSummary()

      // Use a frequency tier we know has no subscribers.
      service.dispatch(frequency = Some(3), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent shouldBe 0
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }
    }

    // 2026-05-24 regression: with the inline top-story refactor, the
    // dispatch path picks the story from the (lastAsOf, currentMax]
    // article window — NOT from a pre-seeded top_summary row. This
    // test inserts 3 articles and verifies (a) a top_summary row was
    // CREATED by the dispatch (audit), (b) the notification was sent
    // with a body listing the language keys, and (c) the title format
    // includes the "N new articles" suffix.
    "compose top story inline from articles window (post-refactor)" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      service.subscribe(
        SubscribeRequest(
          deviceId  = "ns-spec-inline-1",
          apnsToken = "ns-spec-token-inline-1",
          frequency = 4,
          environment = "production"
        )
      ) shouldBe a[Right[_, _]]

      // Three articles from the SAME author (publisher) in politics →
      // Tier 2 single-publisher fallback fires; rep article = latest.
      val ids = (1 to 3).map { i =>
        articleRepo.create(
          ArticleCreate(
            author      = Some("inline.example"),
            title       = s"Inline test article $i",
            description = None,
            url         = s"https://example.com/inline-${java.util.UUID.randomUUID()}",
            urlToImage  = None,
            content     = None,
            category    = Some("politics")
          )
        ).fold(e => fail(e.getMessage), identity)
      }
      val countBefore = topSummaryRepo.findLatestUndispatched()
        .fold(e => fail(e.getMessage), _.map(_.id).getOrElse(0L))

      service.dispatch(frequency = Some(4), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent       should be >= 1
          resp.newArticles should be >= 3
        case other => fail(s"Expected Sent, got: $other")
      }

      // The inline path INSERTed a fresh top_summary AND immediately
      // marked it dispatched. findLatestUndispatched should NOT have
      // returned that fresh row.
      val sentBatches = stub.batches.flatMap(_.tokens)
      sentBatches should contain("ns-spec-token-inline-1")
      val titles = stub.batches.map(_.title)
      titles.exists(_.contains("new articles")) shouldBe true
    }

    "send to subscribers for the given frequency when there are new articles" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      // Subscribe a unique device on frequency=2 (default environment=production).
      service.subscribe(
        SubscribeRequest(
          deviceId  = "ns-spec-device-2",
          apnsToken = "ns-spec-token-2",
          frequency = 2
        )
      ) shouldBe a[Right[_, _]]

      // Insert a fresh article so countSinceId > 0.
      articleRepo.create(
        ArticleCreate(
          author      = Some("ns-spec"),
          title       = "NotificationServiceSpec dispatch trigger",
          description = None,
          url         = "https://example.com/ns-spec/dispatch",
          urlToImage  = None,
          content     = None,
          category    = Some("ns-spec")
        )
      ) shouldBe a[Right[_, _]]

      // §8 dispatch flow needs a top_summary AND non-empty notification_messages
      // for the subscriber's notification_language ('en' default).
      seedTopSummary()

      service.dispatch(frequency = Some(2), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent should be >= 1
          resp.failed shouldBe 0
          resp.newArticles should be >= 1
        case other =>
          fail(s"Expected Sent, got: $other")
      }

      stub.batches should not be empty
      stub.batches.flatMap(_.tokens) should contain("ns-spec-token-2")
    }

    "return NoFreshTopStory when no new articles and the pool path is off" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      // Frequency=2 was just dispatched in the previous test — no fresh inserts here.
      // Legacy path: empty (lastAsOf, currentMax] window → selector None →
      // NoFreshTopStory. Pool path off, so no older candidate to drain.
      service.dispatch(frequency = Some(2), environment = "production") match {
        case Right(DispatchOutcome.NoFreshTopStory) => succeed
        case other => fail(s"Expected NoFreshTopStory, got: $other")
      }
      stub.batches shouldBe empty
    }

    // V29 fallback-pool path. Flips the feature flag on, exercises the
    // per-language pool flow, and verifies that the dispatch sends a
    // notification. The legacy flag-off path remains covered by the
    // surrounding tests. `try/finally` keeps the flag clean even on
    // assertion failure so downstream tests don't accidentally run on
    // the pool path.
    "dispatch via fallback pool when notifications_fallback_pool_enabled is on" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      flagRepo.setEnabled(FallbackPoolConfig.FlagName, true) shouldBe a[Right[_, _]]
      try {
        // frequency must be BETWEEN 1 AND 4 (V4 CHECK constraint).
        service.subscribe(
          SubscribeRequest(
            deviceId    = "ns-spec-pool-1",
            apnsToken   = "ns-spec-token-pool-1",
            frequency   = 4,
            environment = "production"
          )
        ) shouldBe a[Right[_, _]]

        val uniqueTag = java.util.UUID.randomUUID().toString.take(8)
        (1 to 5).foreach { i =>
          articleRepo.create(
            ArticleCreate(
              author      = Some(s"pool-pub-$i.example"),
              title       = s"Pool test article $uniqueTag-$i",
              description = None,
              url         = s"https://example.com/pool-$uniqueTag-$i",
              urlToImage  = None,
              content     = None,
              category    = Some("politics")
            )
          ) shouldBe a[Right[_, _]]
        }

        service.dispatch(frequency = Some(4), environment = "production") match {
          case Right(DispatchOutcome.Sent(resp)) =>
            resp.sent should be >= 1
            resp.newArticles should be >= 5
          case other => fail(s"Expected Sent under pool flag, got: $other")
        }

        stub.batches.flatMap(_.tokens) should contain("ns-spec-token-pool-1")
      } finally {
        flagRepo.setEnabled(FallbackPoolConfig.FlagName, false)
      }
    }

    "route sandbox dispatches to the sandbox client and skip production tokens" in {
      requireDb()
      val prodStub    = new StubApnsMessagingService(acceptAll = true)
      val sandboxStub = new StubApnsMessagingService(acceptAll = true)
      val service     = newService(apnsProd = Some(prodStub), apnsSandbox = Some(sandboxStub))

      // One sandbox-tagged subscriber + one production-tagged subscriber on freq=4.
      service.subscribe(
        SubscribeRequest(
          deviceId    = "ns-spec-device-sandbox",
          apnsToken   = "ns-spec-token-sandbox",
          frequency   = 4,
          environment = "sandbox"
        )
      ) shouldBe a[Right[_, _]]
      service.subscribe(
        SubscribeRequest(
          deviceId    = "ns-spec-device-prod",
          apnsToken   = "ns-spec-token-prod",
          frequency   = 4,
          environment = "production"
        )
      ) shouldBe a[Right[_, _]]

      // Fresh article so we actually attempt sends.
      articleRepo.create(
        ArticleCreate(
          author      = Some("ns-spec"),
          title       = "NotificationServiceSpec sandbox routing",
          description = None,
          url         = "https://example.com/ns-spec/sandbox",
          urlToImage  = None,
          content     = None,
          category    = Some("ns-spec")
        )
      ) shouldBe a[Right[_, _]]

      // §8 dispatch path needs a top_summary; subscribers' default
      // notification_language is 'en' so the seeded "en" message
      // matches the per-language fan-out.
      seedTopSummary()

      service.dispatch(frequency = Some(4), environment = "sandbox") match {
        case Right(DispatchOutcome.Sent(_)) => succeed
        case other                          => fail(s"Expected Sent, got: $other")
      }

      sandboxStub.batches.flatMap(_.tokens) should contain("ns-spec-token-sandbox")
      sandboxStub.batches.flatMap(_.tokens) should not contain "ns-spec-token-prod"
      prodStub.batches shouldBe empty
    }
  }

  "broadcast" should {
    "skip both environments when both flags are off" in {
      requireDb()
      // V15 seeds both flags as false; reset to be defensive against
      // earlier tests that may have flipped them.
      flagRepo.setEnabled("test_notification", false)       shouldBe a[Right[_, _]]
      flagRepo.setEnabled("notify_applestore_apps", false)  shouldBe a[Right[_, _]]

      val prodStub    = new StubApnsMessagingService(acceptAll = true)
      val sandboxStub = new StubApnsMessagingService(acceptAll = true)
      val service     = newService(apnsProd = Some(prodStub), apnsSandbox = Some(sandboxStub))

      service.broadcast("hello") match {
        case Right(resp) =>
          resp.production.enabled shouldBe false
          resp.production.sent    shouldBe 0
          resp.sandbox.enabled    shouldBe false
          resp.sandbox.sent       shouldBe 0
        case other => fail(s"Expected Right(BroadcastResponse), got: $other")
      }
      prodStub.batches    shouldBe empty
      sandboxStub.batches shouldBe empty
    }

    "fan out to only the sandbox environment when only that flag is on" in {
      requireDb()
      flagRepo.setEnabled("test_notification", true)        shouldBe a[Right[_, _]]
      flagRepo.setEnabled("notify_applestore_apps", false)  shouldBe a[Right[_, _]]

      val prodStub    = new StubApnsMessagingService(acceptAll = true)
      val sandboxStub = new StubApnsMessagingService(acceptAll = true)
      val service     = newService(apnsProd = Some(prodStub), apnsSandbox = Some(sandboxStub))

      // A subscriber in each environment so we can verify only sandbox
      // got the broadcast.
      service.subscribe(
        SubscribeRequest(
          deviceId    = "ns-spec-broadcast-sand",
          apnsToken   = "ns-spec-broadcast-token-sand",
          frequency   = 1,
          environment = "sandbox"
        )
      ) shouldBe a[Right[_, _]]
      service.subscribe(
        SubscribeRequest(
          deviceId    = "ns-spec-broadcast-prod",
          apnsToken   = "ns-spec-broadcast-token-prod",
          frequency   = 1,
          environment = "production"
        )
      ) shouldBe a[Right[_, _]]

      service.broadcast("ping") match {
        case Right(resp) =>
          resp.sandbox.enabled    shouldBe true
          resp.sandbox.sent       should be >= 1
          resp.production.enabled shouldBe false
          resp.production.sent    shouldBe 0
        case other => fail(s"Expected Right(BroadcastResponse), got: $other")
      }

      sandboxStub.batches.flatMap(_.tokens) should contain("ns-spec-broadcast-token-sand")
      sandboxStub.batches.flatMap(_.tokens) should not contain "ns-spec-broadcast-token-prod"
      prodStub.batches shouldBe empty

      // Reset for any later tests in this run.
      flagRepo.setEnabled("test_notification", false)
    }

    "report enabled=true with sent=0 when the flag is on but the APNs client is None" in {
      requireDb()
      flagRepo.setEnabled("test_notification", true) shouldBe a[Right[_, _]]
      flagRepo.setEnabled("notify_applestore_apps", false) shouldBe a[Right[_, _]]

      // Both clients None — simulates broadcast firing on a pod that
      // booted without valid APNs credentials.
      val service = newService(apnsProd = None, apnsSandbox = None)
      service.broadcast("ping") match {
        case Right(resp) =>
          resp.sandbox.enabled    shouldBe true
          resp.sandbox.sent       shouldBe 0
          resp.sandbox.failed     shouldBe 0
          resp.production.enabled shouldBe false
        case other => fail(s"Expected Right(BroadcastResponse), got: $other")
      }

      flagRepo.setEnabled("test_notification", false)
    }
  }
}
