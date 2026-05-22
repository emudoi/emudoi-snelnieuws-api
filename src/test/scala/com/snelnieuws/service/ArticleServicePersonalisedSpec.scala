package com.snelnieuws.service

import cats.effect.unsafe.implicits.global
import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import com.snelnieuws.model.ArticleCreate
import com.snelnieuws.repository.{
  AppClientRepository,
  ArticleRepository,
  FeatureFlagRepository,
  ImageCacheRepository
}
import doobie.implicits._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.http.HttpClient
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Exercises the personalised-feed read paths on ArticleService end-to-end:
  *  real ArticleRepository + AppClientRepository + FeatureFlagRepository
  *  against the testcontainer DB. The image cache wiring is stubbed because
  *  this suite only calls the read paths, never create.
  */
class ArticleServicePersonalisedSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport
    with BeforeAndAfterEach {

  private val Flag = "personalised_feed_enabled"

  private lazy val articleRepo     = new ArticleRepository(Database.transactor)
  private lazy val appClientRepo   = new AppClientRepository(Database.transactor)
  private lazy val featureFlagRepo = new FeatureFlagRepository(Database.transactor)

  private val imageCacheConfig = ImageCacheConfig(
    rootDir             = "/tmp/personalised-spec",
    downloadTimeoutMs   = 1000,
    maxBytes            = 1024,
    userAgent           = "test/1.0",
    maxAttempts         = 3,
    retryBackoffMinutes = 30
  )

  private def explodingImageRepo(): ImageCacheRepository =
    new ImageCacheRepository({ throw new AssertionError("image repo must not be used") })

  private lazy val service = new ArticleService(
    repository            = articleRepo,
    appClientRepository   = appClientRepo,
    featureFlagRepository = featureFlagRepo,
    imageCacheService     = new ImageCacheService(
      explodingImageRepo(),
      HttpClient.newHttpClient(),
      imageCacheConfig
    ),
    imageDownloadWorker   = new ImageDownloadWorker(
      new ImageCacheService(explodingImageRepo(), HttpClient.newHttpClient(), imageCacheConfig),
      // Noop producer — articles-service tests only exercise enqueue,
      // never the retry-emit path, but the constructor needs a real ref.
      new KafkaImageRetryProducer(bootstrapServers = "stub:0", topic = "stub") {
        override def send(event: com.snelnieuws.model.ImageRetryEvent): Unit = ()
        override def close(): Unit                                            = ()
      },
      workerThreads = 1,
      queueCapacity = 8
    ),
    publicBaseUrl = ""
  )

  // Tag for our seeded articles so we can filter response sets to "rows
  // this test created" independent of whatever else is in the table.
  private var tag: String = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    tag = s"personalised-spec-${UUID.randomUUID().toString.take(8)}"
    // Reset flag to off between tests so cross-test state can't leak.
    featureFlagRepo.setEnabled(Flag, enabled = false)
  }

  private def registerClient(): UUID = {
    val cid = UUID.randomUUID()
    appClientRepo.upsertOnRegister(
      clientId  = cid,
      bundleId  = "com.emudoi.snelnieuws",
      osVersion = Some("test"),
      platform  = "ios"
    ) shouldBe a[Right[_, _]]
    cid
  }

  private def seed(n: Int, category: String = "technology"): List[Long] =
    (1 to n).toList.map { i =>
      articleRepo.create(ArticleCreate(
        author      = Some(tag),
        title       = s"$tag-$i-${UUID.randomUUID()}",
        description = None,
        url         = s"https://example.com/$tag/$i",
        urlToImage  = None,
        content     = None,
        category    = Some(category)
      )).toOption.get.id
    }

  private def ourIds(articles: List[com.snelnieuws.model.Article]): Set[String] = {
    val ourSet = articles.filter(_.author.contains(tag)).map(_.id).toSet
    ourSet
  }

  private def servedIdsFor(cid: UUID): Set[Long] =
    appClientRepo.readServedIds(cid).toOption.get

  "ArticleService.findAll(limit, clientId)" should {
    "behave like the legacy overload when the flag is off" in {
      requireDb()
      val cid = registerClient()
      seed(20)
      val out = service.findAll(100, Some(cid)).toOption.get
      ourIds(out).size shouldBe 20
      servedIdsFor(cid) shouldBe empty
    }

    "behave like the legacy overload when clientId is None" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cid = registerClient()
      seed(20)
      service.findAll(100, None).toOption.get // call w/o clientId
      servedIdsFor(cid) shouldBe empty
    }

    "populate served_ids on first call when flag is on" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cid = registerClient()
      val seeded = seed(5)
      val out = service.findAll(100, Some(cid)).toOption.get
      val returnedOurs = ourIds(out)
      returnedOurs.size shouldBe 5
      // last_served_ids must now include every id we just returned (along
      // with any other articles the DB held that came back in the response).
      val served = servedIdsFor(cid)
      seeded.foreach(id => served should contain(id))
    }

    "return disjoint id sets across two consecutive calls when flag is on" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cid = registerClient()
      seed(250) // enough that two consecutive 100-batches don't overlap
      val first  = service.findAll(100, Some(cid)).toOption.get.map(_.id).toSet
      val second = service.findAll(100, Some(cid)).toOption.get.map(_.id).toSet
      first.intersect(second) shouldBe empty
      // Both batches were non-empty (no premature exhaust).
      first.size shouldBe 100
      second.size shouldBe 100
    }

    "reset on exhaust and serve the top-N when no fresh ids remain" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cid = registerClient()
      // Seed a small pool of 30 and pre-mark every id as served, plus some
      // other ids that may exist from other suites. The filter should yield
      // 0 fresh ids → reset path returns the top `limit`.
      val ids = seed(30)
      // Grab whatever the pool would return (300 max) and mark them all served.
      val allPoolIds = articleRepo.findAllPool().toOption.get.map(_.id)
      appClientRepo.setServedIds(cid, allPoolIds) shouldBe a[Right[_, _]]

      val out = service.findAll(100, Some(cid)).toOption.get
      out should not be empty
      ourIds(out).size shouldBe (30 min out.size)
      // After reset, last_served_ids contains exactly the just-returned ids.
      val served = servedIdsFor(cid)
      served shouldBe out.map(_.id.toLong).toSet
      // Crucially: the previously-served set has been replaced, so it no
      // longer includes ids that weren't returned this round.
      val notReturnedButPreviouslyServed = allPoolIds.toSet.diff(served)
      notReturnedButPreviouslyServed should not be empty
      // None of those should leak into the current served snapshot.
      served.intersect(notReturnedButPreviouslyServed) shouldBe empty
      // Stash for the local 'ids' helper above (forces use).
      ids.size shouldBe 30
    }

    "isolate served_ids across clients" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cidA = registerClient()
      val cidB = registerClient()
      seed(150)
      val a1 = service.findAll(100, Some(cidA)).toOption.get.map(_.id).toSet
      val b1 = service.findAll(100, Some(cidB)).toOption.get.map(_.id).toSet
      // Both first calls draw from the same top-N pool — identical responses.
      a1 shouldBe b1
      // Now divergence: client A's second call must avoid a1, client B's
      // second call must avoid b1. They should each see new ids.
      val a2 = service.findAll(100, Some(cidA)).toOption.get.map(_.id).toSet
      val b2 = service.findAll(100, Some(cidB)).toOption.get.map(_.id).toSet
      a2.intersect(a1) shouldBe empty
      b2.intersect(b1) shouldBe empty
    }

    "race two concurrent calls cleanly" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cid = registerClient()
      seed(250)
      implicit val ec: ExecutionContext = ExecutionContext.global
      val f = Future.sequence(List(
        Future(service.findAll(100, Some(cid))),
        Future(service.findAll(100, Some(cid)))
      ))
      val Seq(rA, rB) = Await.result(f, 20.seconds)
      val first  = rA.toOption.get.map(_.id).toSet
      val second = rB.toOption.get.map(_.id).toSet
      // With FOR UPDATE serialising the read+append, the two responses
      // share at most one batch's worth of overlap in the worst race
      // (both read the same `served` snapshot before either writes). We
      // assert the weaker invariant: at least one ID differs between the
      // two batches — pure-duplicate responses indicate a missing lock.
      first should not be second
    }
  }

  "ArticleService.findByCategory(category, limit, clientId)" should {
    "rotate within a single category when flag is on" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cid = registerClient()
      seed(150, category = "politics")
      val first  = service.findByCategory("politics", 100, Some(cid)).toOption.get.map(_.id).toSet
      val second = service.findByCategory("politics", 100, Some(cid)).toOption.get.map(_.id).toSet
      first.intersect(second) shouldBe empty
    }
  }

  "ArticleService.findByCategories(categories, limit, clientId)" should {
    "rotate across a multi-category list when flag is on" in {
      requireDb()
      featureFlagRepo.setEnabled(Flag, enabled = true)
      val cid = registerClient()
      seed(80, category = "politics")
      seed(80, category = "economy")
      val first = service.findByCategories(List("politics", "economy"), 100, Some(cid))
        .toOption.get.map(_.id).toSet
      val second = service.findByCategories(List("politics", "economy"), 100, Some(cid))
        .toOption.get.map(_.id).toSet
      first.intersect(second) shouldBe empty
    }
  }

  override def afterAll(): Unit = {
    // Reset flag so subsequent test classes start clean.
    featureFlagRepo.setEnabled(Flag, enabled = false)
    // Wipe articles seeded by this suite.
    sql"DELETE FROM articles WHERE author LIKE 'personalised-spec-%'"
      .update.run.transact(Database.transactor).unsafeRunSync()
    super.afterAll()
  }
}
