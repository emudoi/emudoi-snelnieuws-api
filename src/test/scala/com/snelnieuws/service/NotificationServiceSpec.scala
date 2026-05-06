package com.snelnieuws.service

import com.snelnieuws.{DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.db.Database
import com.snelnieuws.model.{ArticleCreate, SubscribeRequest}
import com.snelnieuws.repository.{
  ArticleRepository,
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

  "subscribe" should {
    "upsert a subscription row" in {
      requireDb()
      val service = new NotificationService(articleRepo, subRepo, dispatchRepo, apns = None)
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
    "return Disabled when apns is None" in {
      requireDb()
      val service = new NotificationService(articleRepo, subRepo, dispatchRepo, apns = None)
      service.dispatch(frequency = None) match {
        case Right(DispatchOutcome.Disabled) => succeed
        case other                           => fail(s"Expected Disabled, got: $other")
      }
    }

    "return Sent with sent=0 when there are no tokens for the frequency" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = new NotificationService(articleRepo, subRepo, dispatchRepo, apns = Some(stub))

      // Use a frequency tier we know has no subscribers.
      service.dispatch(frequency = Some(3)) match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent shouldBe 0
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }
    }

    "send to subscribers for the given frequency when there are new articles" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = new NotificationService(articleRepo, subRepo, dispatchRepo, apns = Some(stub))

      // Subscribe a unique device on frequency=2.
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

      service.dispatch(frequency = Some(2)) match {
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

    "return Sent with newArticles=0 immediately after a previous dispatch with no inserts" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = new NotificationService(articleRepo, subRepo, dispatchRepo, apns = Some(stub))

      // Frequency=2 was just dispatched in the previous test — no fresh inserts here.
      service.dispatch(frequency = Some(2)) match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.newArticles shouldBe 0
          resp.sent shouldBe 0
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }
      stub.batches shouldBe empty
    }
  }
}
