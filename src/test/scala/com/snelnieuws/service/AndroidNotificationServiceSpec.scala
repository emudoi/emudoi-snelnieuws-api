package com.snelnieuws.service

import cats.effect.unsafe.implicits.global
import com.snelnieuws.{DatabaseTestSupport, StubFcmMessagingService}
import com.snelnieuws.db.Database
import com.snelnieuws.model.{AndroidSubscribeRequest, ArticleCreate}
import doobie.implicits._
import com.snelnieuws.repository.{
  AndroidNotificationSubscriptionRepository,
  ArticleRepository,
  FeatureFlagRepository,
  NotificationCandidateRepository
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AndroidNotificationServiceSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val articleRepo   = new ArticleRepository(Database.transactor)
  private lazy val subRepo       = new AndroidNotificationSubscriptionRepository(Database.transactor)
  private lazy val flagRepo      = new FeatureFlagRepository(Database.transactor)
  private lazy val candidateRepo = new NotificationCandidateRepository(Database.transactor)

  private def newService(fcm: Option[FcmMessagingService] = None) =
    new AndroidNotificationService(articleRepo, subRepo, flagRepo, candidateRepo, fcm = fcm)

  /** Insert a fresh (newest) "en" article so the per-language pool has a
    * claimable candidate this tick (Tier-4 fill always includes the
    * most-recent unconsumed article). */
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

      // Establish the "no tokens at this frequency" precondition on the shared
      // DB rather than assuming it — other suites leave Android subscriptions
      // behind, and dispatch matches `frequency >= N` (a slot threshold), so a
      // freq-3 dispatch also fans out to freq-4 subscribers. Clear freq >= 3.
      sql"DELETE FROM android_notification_subscriptions WHERE frequency >= 3"
        .update.run.transact(Database.transactor).unsafeRunSync()

      // A claimable "en" candidate must exist or dispatch returns
      // NoFreshTopStory; freq=3 has no subscribers so sent=0.
      seedRecentArticle("and-spec-notokens")

      service.dispatch(frequency = Some(3)) match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent shouldBe 0
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }
    }

    "send to subscribers for the given frequency when a candidate exists" in {
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

      seedRecentArticle("and-spec-freq2")

      service.dispatch(frequency = Some(2)) match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent should be >= 1
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }

      stub.batches should not be empty
      stub.batches.flatMap(_.tokens) should contain("and-spec-token-2")
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
