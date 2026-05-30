package com.snelnieuws.service

import com.snelnieuws.{DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.db.Database
import com.snelnieuws.model.{ArticleCreate, SubscribeRequest}
import com.snelnieuws.repository.{
  ArticleRepository,
  FeatureFlagRepository,
  NotificationCandidateRepository,
  NotificationSubscriptionRepository
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NotificationServiceSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val articleRepo   = new ArticleRepository(Database.transactor)
  private lazy val subRepo       = new NotificationSubscriptionRepository(Database.transactor)
  private lazy val flagRepo      = new FeatureFlagRepository(Database.transactor)
  private lazy val candidateRepo = new NotificationCandidateRepository(Database.transactor)

  private def newService(apnsProd: Option[ApnsMessagingService] = None,
                         apnsSandbox: Option[ApnsMessagingService] = None) =
    new NotificationService(articleRepo, subRepo, flagRepo,
      candidateRepo, apnsProd = apnsProd, apnsSandbox = apnsSandbox)

  /** Insert a fresh (newest) "en" article so the per-language pool has a
    * claimable candidate this tick. selectTopN's Tier-4 fill always
    * includes the most-recent article, and it hasn't been consumed, so
    * dispatch resolves a pick for "en". */
  private def seedRecentArticle(tag: String): Long =
    articleRepo.create(
      ArticleCreate(
        author      = Some(s"$tag.example"),
        title       = s"$tag headline",
        description = None,
        url         = s"https://example.com/$tag-${java.util.UUID.randomUUID()}",
        urlToImage  = None,
        content     = None,
        category    = Some("politics")
      )
    ).fold(e => throw new RuntimeException(e.getMessage), _.id)

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

      // A claimable "en" candidate must exist, otherwise dispatch returns
      // NoFreshTopStory instead of Sent. Use a frequency tier with no
      // subscribers so the per-language fan-out yields zero tokens.
      seedRecentArticle("ns-spec-notokens")

      service.dispatch(frequency = Some(3), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent shouldBe 0
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }
    }

    "claim a per-language top story and send it to that language's subscribers" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      service.subscribe(
        SubscribeRequest(
          deviceId    = "ns-spec-inline-1",
          apnsToken   = "ns-spec-token-inline-1",
          frequency   = 4,
          environment = "production"
        )
      ) shouldBe a[Right[_, _]]

      // Three articles from the SAME author (publisher) in politics →
      // Tier 2 single-publisher fallback fires; rep article = latest.
      (1 to 3).foreach { i =>
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

      service.dispatch(frequency = Some(4), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent should be >= 1
        case other => fail(s"Expected Sent, got: $other")
      }

      stub.batches.flatMap(_.tokens) should contain("ns-spec-token-inline-1")
      // Title is the article headline, with no trailing "N new articles" suffix.
      stub.batches.map(_.title).foreach(_ should not include "new articles")
    }

    "send to subscribers for the given frequency when a candidate exists" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      service.subscribe(
        SubscribeRequest(
          deviceId  = "ns-spec-device-2",
          apnsToken = "ns-spec-token-2",
          frequency = 2
        )
      ) shouldBe a[Right[_, _]]

      seedRecentArticle("ns-spec-freq2")

      service.dispatch(frequency = Some(2), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent should be >= 1
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }

      stub.batches should not be empty
      stub.batches.flatMap(_.tokens) should contain("ns-spec-token-2")
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

      seedRecentArticle("ns-spec-sandbox")

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
