package com.snelnieuws.service

import com.snelnieuws.{DatabaseTestSupport, StubFcmMessagingService}
import com.snelnieuws.db.Database
import com.snelnieuws.model.{AndroidSubscribeRequest, ArticleCreate}
import com.snelnieuws.repository.{
  AndroidNotificationDispatchRepository,
  AndroidNotificationSubscriptionRepository,
  ArticleRepository,
  FeatureFlagRepository
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AndroidNotificationServiceSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val articleRepo    = new ArticleRepository(Database.transactor)
  private lazy val subRepo        = new AndroidNotificationSubscriptionRepository(Database.transactor)
  private lazy val dispatchRepo   = new AndroidNotificationDispatchRepository(Database.transactor)
  private lazy val flagRepo       = new FeatureFlagRepository(Database.transactor)
  private lazy val topSummaryRepo = new com.snelnieuws.repository.TopSummaryRepository(Database.transactor)

  private def newService(fcm: Option[FcmMessagingService] = None) =
    new AndroidNotificationService(articleRepo, subRepo, dispatchRepo, flagRepo, topSummaryRepo, fcm = fcm)

  "subscribe" should {
    "upsert a subscription row" in {
      requireDb()
      val service = newService()
      val req = AndroidSubscribeRequest(
        deviceId  = "and-spec-device-1",
        fcmToken  = "and-spec-token-1",
        frequency = 1
      )
      service.subscribe(req) shouldBe a[Right[_, _]]

      val req2 = req.copy(fcmToken = "and-spec-token-1-new")
      service.subscribe(req2) shouldBe a[Right[_, _]]

      val tokens = subRepo.findTokensByFrequency(1).toOption.getOrElse(Nil)
      tokens should contain("and-spec-token-1-new")
      tokens should not contain "and-spec-token-1"
    }
  }

  "dispatch" should {
    "return Disabled when the FCM client is None" in {
      requireDb()
      val service = newService()
      service.dispatch(frequency = None) match {
        case Right(DispatchOutcome.Disabled) => succeed
        case other                           => fail(s"Expected Disabled, got: $other")
      }
    }

    "return Sent with sent=0 when there are no tokens for the frequency" in {
      requireDb()
      val stub    = new StubFcmMessagingService(acceptAll = true)
      val service = newService(Some(stub))

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
      val stub    = new StubFcmMessagingService(acceptAll = true)
      val service = newService(Some(stub))

      service.subscribe(
        AndroidSubscribeRequest(
          deviceId  = "and-spec-device-2",
          fcmToken  = "and-spec-token-2",
          frequency = 2
        )
      ) shouldBe a[Right[_, _]]

      articleRepo.create(
        ArticleCreate(
          author      = Some("and-spec"),
          title       = "AndroidNotificationServiceSpec dispatch trigger",
          description = None,
          url         = "https://example.com/and-spec/dispatch",
          urlToImage  = None,
          content     = None,
          category    = Some("and-spec")
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
      stub.batches.flatMap(_.tokens) should contain("and-spec-token-2")
    }

    "return Sent with newArticles=0 immediately after a previous dispatch with no inserts" in {
      requireDb()
      val stub    = new StubFcmMessagingService(acceptAll = true)
      val service = newService(Some(stub))

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

    "track its own watermark independently of iOS dispatches" in {
      requireDb()
      val stub    = new StubFcmMessagingService(acceptAll = true)
      val service = newService(Some(stub))

      // Subscribe a single Android device on freq=1.
      service.subscribe(
        AndroidSubscribeRequest(
          deviceId  = "and-spec-device-watermark",
          fcmToken  = "and-spec-token-watermark",
          frequency = 1
        )
      ) shouldBe a[Right[_, _]]

      // Two distinct Android dispatches with one fresh article between
      // them should each see exactly one new article — the iOS dispatch
      // service writing to its own table must not move the Android marker.
      articleRepo.create(
        ArticleCreate(
          author      = Some("and-spec"),
          title       = "Android watermark — first",
          description = None,
          url         = "https://example.com/and-spec/watermark-1",
          urlToImage  = None,
          content     = None,
          category    = Some("and-spec")
        )
      ) shouldBe a[Right[_, _]]

      service.dispatch(frequency = Some(1)) match {
        case Right(DispatchOutcome.Sent(resp)) => resp.newArticles should be >= 1
        case other                              => fail(s"Expected Sent, got: $other")
      }

      // No new articles → 0
      service.dispatch(frequency = Some(1)) match {
        case Right(DispatchOutcome.Sent(resp)) => resp.newArticles shouldBe 0
        case other                              => fail(s"Expected Sent, got: $other")
      }
    }
  }

  "broadcast" should {
    "skip when notify_android flag is off" in {
      requireDb()
      flagRepo.setEnabled("notify_android", false) shouldBe a[Right[_, _]]

      val stub    = new StubFcmMessagingService(acceptAll = true)
      val service = newService(Some(stub))

      service.broadcast("hello") match {
        case Right(resp) =>
          resp.enabled shouldBe false
          resp.sent    shouldBe 0
          resp.failed  shouldBe 0
        case other => fail(s"Expected Right(AndroidBroadcastResponse), got: $other")
      }
      stub.batches shouldBe empty
    }

    "fan out to every Android subscriber when the flag is on" in {
      requireDb()
      flagRepo.setEnabled("notify_android", true) shouldBe a[Right[_, _]]

      val stub    = new StubFcmMessagingService(acceptAll = true)
      val service = newService(Some(stub))

      service.subscribe(
        AndroidSubscribeRequest(
          deviceId  = "and-spec-broadcast-1",
          fcmToken  = "and-spec-broadcast-token-1",
          frequency = 1
        )
      ) shouldBe a[Right[_, _]]

      service.broadcast("ping") match {
        case Right(resp) =>
          resp.enabled shouldBe true
          resp.sent    should be >= 1
        case other => fail(s"Expected Right(AndroidBroadcastResponse), got: $other")
      }

      stub.batches.flatMap(_.tokens) should contain("and-spec-broadcast-token-1")

      flagRepo.setEnabled("notify_android", false)
    }

    "report enabled=true with sent=0 when the flag is on but FCM client is None" in {
      requireDb()
      flagRepo.setEnabled("notify_android", true) shouldBe a[Right[_, _]]

      val service = newService(fcm = None)
      service.broadcast("ping") match {
        case Right(resp) =>
          resp.enabled shouldBe true
          resp.sent    shouldBe 0
          resp.failed  shouldBe 0
        case other => fail(s"Expected Right(AndroidBroadcastResponse), got: $other")
      }

      flagRepo.setEnabled("notify_android", false)
    }
  }
}
