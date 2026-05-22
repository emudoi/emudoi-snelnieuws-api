package com.snelnieuws.service

import com.snelnieuws.model.ImageRetryEvent
import com.snelnieuws.repository.ImageCacheRepository
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.net.http.HttpClient
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

class ImageDownloadWorkerSpec extends AnyWordSpec with Matchers with Eventually {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(3, Seconds), interval = Span(50, Millis))

  // Repository whose transactor thunk explodes on access — proves the
  // worker test stays out of the DB path.
  private def explodingRepo(): ImageCacheRepository =
    new ImageCacheRepository({ throw new AssertionError("repo must not be used in this test") })

  private val baseConfig = ImageCacheConfig(
    rootDir             = "/tmp/img-worker-spec",
    downloadTimeoutMs   = 1000,
    maxBytes            = 1024,
    userAgent           = "test/1.0",
    maxAttempts         = 3,
    retryBackoffMinutes = 30
  )

  /** Stub service that records every URL it was asked to fetch and lets
    * the test choose the result. Subclasses ImageCacheService so the worker
    * sees a real type — overriding only resolveOrFetch. */
  private class RecordingService(
    decide: String => Either[Throwable, ImageDownloadResult]
  ) extends ImageCacheService(explodingRepo(), HttpClient.newHttpClient(), baseConfig) {
    val calls = new ConcurrentLinkedQueue[String]()
    override def resolveOrFetch(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
      calls.add(sourceUrl)
      decide(sourceUrl)
    }
  }

  /** Stub Kafka producer that records sends without touching a broker.
    * The worker only uses .send (and .close on shutdown via Components,
    * not the worker itself), so we override only what's exercised. */
  private class StubRetryProducer
      extends KafkaImageRetryProducer(
        bootstrapServers = "stub:0",
        topic            = "stub-topic"
      ) {
    val events = new ConcurrentLinkedQueue[ImageRetryEvent]()
    @volatile var sendShouldThrow: Boolean = false
    override def send(event: ImageRetryEvent): Unit = {
      if (sendShouldThrow) {
        throw new RuntimeException("producer is sad")
      }
      events.add(event)
    }
    override def close(): Unit = ()
  }

  private def newWorker(
    svc: ImageCacheService,
    producer: KafkaImageRetryProducer = new StubRetryProducer(),
    threads: Int = 2,
    capacity: Int = 16,
    metricMs: Long = 60_000L
  ): ImageDownloadWorker =
    new ImageDownloadWorker(svc, producer, threads, capacity, metricMs)

  "ImageDownloadWorker.enqueue" should {
    "drain enqueued URLs through resolveOrFetch" in {
      val ok = (u: String) => Right(ImageDownloadResult(u, Some("image/jpeg"), 10L))
      val svc = new RecordingService(ok)
      val worker = newWorker(svc)
      worker.start()
      try {
        (1 to 5).foreach(i => worker.enqueue(s"https://example.com/$i.jpg") shouldBe true)
        eventually {
          svc.calls.size shouldBe 5
        }
        worker.droppedCount() shouldBe 0L
      } finally {
        worker.stop()
      }
    }

    "skip empty URLs without enqueueing" in {
      val svc    = new RecordingService(_ => Right(ImageDownloadResult("x", None, 0L)))
      val worker = newWorker(svc, threads = 1)
      worker.start()
      try {
        worker.enqueue("")    shouldBe false
        worker.enqueue("   ") shouldBe false
        worker.enqueue(null)  shouldBe false
        Thread.sleep(50)
        svc.calls.size shouldBe 0
      } finally {
        worker.stop()
      }
    }

    "drop jobs when the queue is full instead of blocking" in {
      // Single thread, capacity=1, slow service: only one job can be in-flight,
      // queue has room for one. Subsequent enqueues during that window must
      // be dropped rather than block the caller.
      val gate = new CountDownLatch(1)
      val seen = new AtomicInteger(0)
      val slow: String => Either[Throwable, ImageDownloadResult] = _ => {
        seen.incrementAndGet()
        gate.await(2, TimeUnit.SECONDS) // hold the worker thread
        Right(ImageDownloadResult("x", None, 0L))
      }
      val svc    = new RecordingService(slow)
      val worker = newWorker(svc, threads = 1, capacity = 1)
      worker.start()
      try {
        // First enqueue gets picked up by the worker thread immediately.
        worker.enqueue("u-1") shouldBe true
        // Wait for the worker to actually take(); without this, the next
        // enqueue would just fill the empty queue.
        eventually { seen.get() shouldBe 1 }
        // Slot is now empty — the next enqueue fills it.
        worker.enqueue("u-2") shouldBe true
        // Both worker and queue are saturated — these should drop.
        worker.enqueue("u-3") shouldBe false
        worker.enqueue("u-4") shouldBe false
        worker.droppedCount() shouldBe 2L
      } finally {
        gate.countDown()
        worker.stop()
      }
    }

    "stop cleanly without hanging" in {
      val svc    = new RecordingService(_ => Right(ImageDownloadResult("x", None, 0L)))
      val worker = newWorker(svc, capacity = 8)
      worker.start()
      worker.enqueue("https://example.com/a.jpg")
      worker.stop()
      // After stop, further enqueues must be no-ops.
      worker.enqueue("https://example.com/late.jpg") shouldBe false
    }
  }

  "ImageDownloadWorker slow-retry hand-off" should {

    "emit ImageRetryEvent on retryableSlow failures" in {
      val producer = new StubRetryProducer()
      // Service returns a ClassifiedDownloadException with Timeout (retryableSlow=true)
      val slowFail: String => Either[Throwable, ImageDownloadResult] = url =>
        Left(
          new ClassifiedDownloadException(
            DownloadFailure.Timeout,
            new java.net.http.HttpTimeoutException(s"timed out fetching $url")
          )
        )
      val svc    = new RecordingService(slowFail)
      val worker = newWorker(svc, producer)
      worker.start()
      try {
        worker.enqueue("https://slow.example/a.jpg") shouldBe true
        eventually {
          producer.events.size shouldBe 1
        }
        val ev = producer.events.peek()
        ev.eventType      shouldBe ImageRetryEvent.EventTypeRetryRequested
        ev.sourceUrl      shouldBe "https://slow.example/a.jpg"
        ev.originalReason shouldBe "timeout"
        ev.firstAttemptAt should not be empty
      } finally {
        worker.stop()
      }
    }

    "NOT emit on non-retryable ClassifiedDownloadException" in {
      val producer = new StubRetryProducer()
      val permFail: String => Either[Throwable, ImageDownloadResult] = _ =>
        Left(
          new ClassifiedDownloadException(
            DownloadFailure.Http4xx(404),
            new RuntimeException("non-2xx status 404 fetching x")
          )
        )
      val svc    = new RecordingService(permFail)
      val worker = newWorker(svc, producer)
      worker.start()
      try {
        worker.enqueue("https://example.com/missing.jpg") shouldBe true
        // Give the worker a beat to process — should NOT have emitted.
        Thread.sleep(150)
        producer.events.size shouldBe 0
      } finally {
        worker.stop()
      }
    }

    "swallow producer.send exceptions without crashing the worker" in {
      val producer = new StubRetryProducer()
      producer.sendShouldThrow = true
      val slowFail: String => Either[Throwable, ImageDownloadResult] = _ =>
        Left(
          new ClassifiedDownloadException(
            DownloadFailure.ConnectionError,
            new java.net.ConnectException("refused")
          )
        )
      val svc    = new RecordingService(slowFail)
      val worker = newWorker(svc, producer)
      worker.start()
      try {
        worker.enqueue("https://example.com/a.jpg") shouldBe true
        // Even though producer.send throws, the worker must remain
        // operational. Feed a second URL — it must also be processed.
        eventually { svc.calls.size shouldBe 1 }
        worker.enqueue("https://example.com/b.jpg") shouldBe true
        eventually { svc.calls.size shouldBe 2 }
      } finally {
        worker.stop()
      }
    }
  }
}
