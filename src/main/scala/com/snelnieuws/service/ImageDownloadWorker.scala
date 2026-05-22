package com.snelnieuws.service

import com.snelnieuws.model.ImageRetryEvent
import org.slf4j.LoggerFactory

import java.time.{OffsetDateTime, ZoneOffset}
import java.util.concurrent.{
  ArrayBlockingQueue,
  ExecutorService,
  Executors,
  ThreadFactory,
  TimeUnit
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/** Background image-download pump.
  *
  * The consumer (and POST /v2/articles) compute the content-addressed
  * relative path for every article URL and write it straight onto
  * articles.url_to_image — *before* any bytes are fetched. This worker's
  * only job is to make those bytes appear at the matching path on NFS.
  * A failure here doesn't break the article: the servlet falls through
  * to the bundled fallback for paths that don't exist on disk yet.
  *
  * Bounded queue + drop-on-full keeps memory bounded under bursty
  * Kafka traffic. With dedup happening implicitly inside
  * ImageCacheService.resolveOrFetch (DB lookup before fetch), enqueueing
  * the same URL from multiple articles is cheap.
  *
  * On a ClassifiedDownloadException whose `retryableSlow=true` (timeout,
  * connection error, 5xx, ambiguous "other"), the URL is handed off to
  * the Kafka image-retry-slow topic. A separate in-process consumer
  * (ImageRetrySlowConsumer) reads that topic and re-tries with a longer
  * timeout — one attempt only. Non-retryable failures (4xx, oversize,
  * unsupported scheme, signed-token-expired) stay 'failed' for good. */
class ImageDownloadWorker(
  imageCacheService: ImageCacheService,
  retryProducer: KafkaImageRetryProducer,
  workerThreads: Int,
  queueCapacity: Int,
  metricLogIntervalMs: Long = 60_000L
) {

  private val logger  = LoggerFactory.getLogger(classOf[ImageDownloadWorker])
  private val started = new AtomicBoolean(false)
  private val running = new AtomicBoolean(false)

  private val queue   = new ArrayBlockingQueue[String](queueCapacity)
  private val dropped = new AtomicLong(0)

  // Sentinel pushed to each worker on shutdown to wake them out of
  // queue.take(). Distinct identity (eq comparison) so a real URL string
  // equal to the literal can never be misread as a stop signal.
  private val PoisonPill: String = new String("__shutdown__")

  private lazy val executor: ExecutorService =
    Executors.newFixedThreadPool(
      workerThreads,
      new ThreadFactory {
        private val counter = new AtomicLong(0)
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"image-download-worker-${counter.incrementAndGet()}")
          t.setDaemon(true)
          t
        }
      }
    )

  // Dedicated single-thread executor for the periodic metric log so it
  // never contends with the download pool for time slices. Daemon so a
  // botched shutdown can't keep the JVM alive.
  private lazy val metricExecutor: ExecutorService =
    Executors.newSingleThreadExecutor(new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "image-download-worker-metrics")
        t.setDaemon(true)
        t
      }
    })

  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      logger.info(
        s"Starting image download worker — threads=$workerThreads, queueCapacity=$queueCapacity"
      )
      running.set(true)
      var i = 0
      while (i < workerThreads) {
        executor.submit(new Runnable {
          override def run(): Unit = drainLoop()
        })
        i += 1
      }
      metricExecutor.submit(new Runnable {
        override def run(): Unit = metricLoop()
      })
    }
  }

  /** Best-effort enqueue. Returns false if the queue is full — the article
    * row keeps its content-addressed URL and the servlet will serve the
    * fallback for it until something else (a later identical-URL job, or a
    * backfill) downloads the bytes. */
  def enqueue(sourceUrl: String): Boolean = {
    val trimmed = Option(sourceUrl).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) return false
    if (!running.get()) {
      val total = dropped.incrementAndGet()
      logger.info(s"image worker not running; dropping url=$trimmed droppedTotal=$total")
      return false
    }
    val ok = queue.offer(trimmed)
    if (!ok) {
      val total = dropped.incrementAndGet()
      logger.info(s"image-download queue full; dropped url=$trimmed droppedTotal=$total")
    }
    ok
  }

  def stop(): Unit = {
    if (started.compareAndSet(true, false)) {
      logger.info("Stopping image download worker...")
      running.set(false)
      // Push one poison pill per worker so each take() returns. Using
      // offer() (not put()) — if the queue is full we'll just rely on the
      // running flag check inside drainLoop after the next take.
      var i = 0
      while (i < workerThreads) {
        queue.offer(PoisonPill)
        i += 1
      }
      executor.shutdown()
      metricExecutor.shutdown()
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          logger.warn("image worker did not terminate cleanly within 10s; forcing")
          executor.shutdownNow()
        }
        if (!metricExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
          metricExecutor.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          executor.shutdownNow()
          metricExecutor.shutdownNow()
          Thread.currentThread().interrupt()
      }
    }
  }

  /** Visible for tests — count of jobs that couldn't be enqueued because
    * the queue was full. */
  def droppedCount(): Long = dropped.get()

  /** Visible for tests — current queue depth. */
  def queueDepth(): Int = queue.size()

  private def drainLoop(): Unit = {
    while (running.get()) {
      try {
        val job = queue.take()
        if (job eq PoisonPill) {
          // Bail out of this worker; other workers exit independently
          // when they see their own poison.
          return
        }
        try {
          imageCacheService.resolveOrFetch(job) match {
            case Right(_) =>
              logger.debug(s"image fetched ok url=$job")
            case Left(_: ImageCacheService.RetryNotDueException) =>
              // Already logged at debug inside the service.
            case Left(e: ClassifiedDownloadException) if e.cls.retryableSlow =>
              logger.info(
                s"image fetch failed url=$job reason=${e.cls.reason}; handing off to slow retry"
              )
              try {
                retryProducer.send(
                  ImageRetryEvent(
                    eventType      = ImageRetryEvent.EventTypeRetryRequested,
                    sourceUrl      = job,
                    originalReason = e.cls.reason,
                    firstAttemptAt = OffsetDateTime.now(ZoneOffset.UTC).toString
                  )
                )
              } catch {
                case t: Throwable =>
                  // A producer send failure shouldn't tear down the worker —
                  // the row is already 'failed' in image_cache, so the
                  // servlet will keep serving the fallback. The slow tier
                  // just won't see this particular URL this round.
                  logger.warn(s"image-retry producer.send failed url=$job: ${t.getMessage}")
              }
            case Left(e: ClassifiedDownloadException) =>
              // Non-retryable — leave the row 'failed' and move on.
              logger.info(s"image fetch failed permanently url=$job reason=${e.cls.reason}")
            case Left(e) =>
              logger.info(s"image fetch failed url=$job: ${e.getMessage}")
          }
        } catch {
          case e: Throwable =>
            logger.error(s"unexpected image-worker error url=$job: ${e.getMessage}", e)
        }
      } catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          return
      }
    }
  }

  private def metricLoop(): Unit = {
    while (running.get()) {
      try {
        Thread.sleep(metricLogIntervalMs)
      } catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          return
      }
      // Single line so it's grep-able as a unit and aggregatable via
      // simple log scraping.
      logger.info(
        s"image worker metrics: queueDepth=${queue.size()} droppedTotal=${dropped.get()}"
      )
    }
  }
}
