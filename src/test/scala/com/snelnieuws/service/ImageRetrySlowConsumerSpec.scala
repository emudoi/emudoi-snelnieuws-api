package com.snelnieuws.service

import com.snelnieuws.model.{ImageCacheRow, ImageCacheStatus, ImageRetryEvent}
import com.snelnieuws.repository.ImageCacheRepository
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.http.HttpClient
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentLinkedQueue

/** Unit tests for ImageRetrySlowConsumer.processOne. The Kafka loop is
  * left to integration verification (§10(c)/(g)) — what matters here is
  * that the per-message decision tree behaves correctly: skip on
  * already-downloaded rows, invoke the slow downloader on 'failed'
  * rows, and tolerate malformed JSON / DB blips without crashing.
  */
class ImageRetrySlowConsumerSpec extends AnyWordSpec with Matchers {

  // Bottom of the dependency tree — never accessed for the assertions below
  // (the consumer always overrides resolveOrFetch via the stub downloader).
  private def explodingRepo(): ImageCacheRepository =
    new ImageCacheRepository({ throw new AssertionError("repo must not be used") })

  private val baseConfig = ImageCacheConfig(
    rootDir             = "/tmp/img-slow-spec",
    downloadTimeoutMs   = 1000,
    maxBytes            = 1024,
    userAgent           = "test/1.0",
    maxAttempts         = 3,
    retryBackoffMinutes = 30
  )

  /** Stub repository that returns a configurable findByUrl result. */
  private class StubRepo(rowByUrl: Map[String, ImageCacheRow] = Map.empty,
                         lookupShouldFail: Boolean = false)
      extends ImageCacheRepository({ throw new AssertionError("transactor must not be used") }) {
    val bumpCalls = new ConcurrentLinkedQueue[String]()
    override def findByUrl(sourceUrl: String): Either[Throwable, Option[ImageCacheRow]] = {
      if (lookupShouldFail) Left(new RuntimeException("db blip"))
      else Right(rowByUrl.get(sourceUrl))
    }
    override def bumpAttempt(sourceUrl: String): Either[Throwable, Int] = {
      bumpCalls.add(sourceUrl)
      Right(1)
    }
  }

  /** Stub slow downloader that records every URL it's asked to fetch
    * and lets the test choose success vs. failure. */
  private class StubSlowDownloader(
    repo: ImageCacheRepository,
    decide: String => Either[Throwable, ImageDownloadResult]
  ) extends ImageSlowDownloader(
        imageCacheService = new ImageCacheService(repo, HttpClient.newHttpClient(), baseConfig),
        imageCacheRepo    = repo,
        config            = baseConfig,
        slowConfig        = ImageSlowConfig(connectTimeoutMs = 1000, readTimeoutMs = 1000)
      ) {
    val calls = new ConcurrentLinkedQueue[String]()
    override def downloadAndStore(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
      calls.add(sourceUrl)
      decide(sourceUrl)
    }
  }

  private def consumerWith(
    repo: ImageCacheRepository,
    slow: ImageSlowDownloader
  ): ImageRetrySlowConsumer =
    new ImageRetrySlowConsumer(
      imageCacheRepo   = repo,
      slowDownloader   = slow,
      bootstrapServers = "stub:0",
      topic            = "image-retry-slow",
      consumerGroup    = "test-group",
      autoOffsetReset  = "earliest"
    )

  private def downloadedRow(url: String): ImageCacheRow = ImageCacheRow(
    sourceUrl     = url,
    relativePath  = "aa/bb/cafef00d.jpg",
    contentType   = Some("image/jpeg"),
    sizeBytes     = Some(100L),
    status        = ImageCacheStatus.Downloaded,
    downloadedAt  = Some(OffsetDateTime.now()),
    lastAttemptAt = OffsetDateTime.now(),
    attempts      = 1
  )

  private def failedRow(url: String): ImageCacheRow = ImageCacheRow(
    sourceUrl     = url,
    relativePath  = "aa/bb/feeddead.jpg",
    contentType   = None,
    sizeBytes     = None,
    status        = ImageCacheStatus.Failed,
    downloadedAt  = None,
    lastAttemptAt = OffsetDateTime.now().minusHours(1),
    attempts      = 1,
    lastFailureReason     = Some("timeout"),
    lastFailureStatusCode = None
  )

  private def eventJson(url: String, reason: String = "timeout"): String =
    ImageRetryEvent(
      eventType      = ImageRetryEvent.EventTypeRetryRequested,
      sourceUrl      = url,
      originalReason = reason,
      firstAttemptAt = OffsetDateTime.now().toString
    ).asJson.noSpaces

  "ImageRetrySlowConsumer.processOne" should {

    "skip download when image_cache row is already 'downloaded'" in {
      val url  = "https://example.com/a.jpg"
      val repo = new StubRepo(rowByUrl = Map(url -> downloadedRow(url)))
      val slow = new StubSlowDownloader(repo, _ => fail("should not be called"))
      val cons = consumerWith(repo, slow)
      cons.processOne(eventJson(url))
      slow.calls.size shouldBe 0
    }

    "invoke slowDownloader when row is 'failed'" in {
      val url  = "https://example.com/b.jpg"
      val repo = new StubRepo(rowByUrl = Map(url -> failedRow(url)))
      val slow = new StubSlowDownloader(
        repo,
        u => Right(ImageDownloadResult(u, Some("image/jpeg"), 100L))
      )
      val cons = consumerWith(repo, slow)
      cons.processOne(eventJson(url))
      slow.calls.size                shouldBe 1
      slow.calls.peek()              shouldBe url
    }

    "invoke slowDownloader when row is missing entirely" in {
      val url  = "https://example.com/c.jpg"
      val repo = new StubRepo() // empty
      val slow = new StubSlowDownloader(
        repo,
        u => Right(ImageDownloadResult(u, None, 0L))
      )
      val cons = consumerWith(repo, slow)
      cons.processOne(eventJson(url))
      slow.calls.size shouldBe 1
    }

    "tolerate downloader failure without crashing or re-emitting" in {
      val url  = "https://example.com/d.jpg"
      val repo = new StubRepo(rowByUrl = Map(url -> failedRow(url)))
      val slow = new StubSlowDownloader(
        repo,
        _ => Left(new RuntimeException("still down"))
      )
      val cons = consumerWith(repo, slow)
      // Must not throw.
      cons.processOne(eventJson(url))
      slow.calls.size shouldBe 1
      // The stub downloader itself does not call repo.bumpAttempt in this
      // test (the real ImageSlowDownloader does so internally; the stub
      // overrides downloadAndStore entirely). Either way the consumer
      // must not crash.
    }

    "log + skip a malformed-JSON payload without invoking the downloader" in {
      val repo = new StubRepo()
      val slow = new StubSlowDownloader(repo, _ => fail("should not be called"))
      val cons = consumerWith(repo, slow)
      cons.processOne("not even close to JSON")
      cons.processOne("""{"unrecognised":"event"}""")
      slow.calls.size shouldBe 0
    }

    "tolerate a null payload" in {
      val repo = new StubRepo()
      val slow = new StubSlowDownloader(repo, _ => fail("should not be called"))
      val cons = consumerWith(repo, slow)
      cons.processOne(null)
      slow.calls.size shouldBe 0
    }

    "skip the slow download when image_cache lookup itself fails" in {
      val url  = "https://example.com/e.jpg"
      val repo = new StubRepo(lookupShouldFail = true)
      val slow = new StubSlowDownloader(repo, _ => fail("should not be called"))
      val cons = consumerWith(repo, slow)
      cons.processOne(eventJson(url))
      slow.calls.size shouldBe 0
    }
  }
}
