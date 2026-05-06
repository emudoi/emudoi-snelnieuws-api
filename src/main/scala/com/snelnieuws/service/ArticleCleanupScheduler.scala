package com.snelnieuws.service

import com.snelnieuws.repository.ArticleRepository
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically deletes articles whose `published_at` is older than `retentionHours`.
 * Driven by a single-thread daemon scheduler — survives transient DB errors by logging
 * and waiting for the next tick.
 */
class ArticleCleanupScheduler(
  articleRepository: ArticleRepository,
  retentionHours: Long,
  intervalMinutes: Long
) {

  private val logger  = LoggerFactory.getLogger(classOf[ArticleCleanupScheduler])
  private val started = new AtomicBoolean(false)

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "article-cleanup-scheduler")
        t.setDaemon(true)
        t
      }
    })

  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      logger.info(
        s"Starting article cleanup scheduler — retentionHours=$retentionHours, intervalMinutes=$intervalMinutes"
      )
      scheduler.scheduleAtFixedRate(
        () => runOnce(),
        0L,
        intervalMinutes,
        TimeUnit.MINUTES
      )
    }
  }

  def stop(): Unit = {
    if (started.compareAndSet(true, false)) {
      logger.info("Stopping article cleanup scheduler...")
      scheduler.shutdown()
      try {
        if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
          scheduler.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          scheduler.shutdownNow()
          Thread.currentThread().interrupt()
      }
    }
  }

  private def runOnce(): Unit = {
    val cutoff = OffsetDateTime.now().minusHours(retentionHours)
    articleRepository.deletePublishedBefore(cutoff) match {
      case Right(deleted) if deleted > 0 =>
        logger.info(s"Cleanup: deleted $deleted article(s) older than $cutoff")
      case Right(_) =>
        logger.debug(s"Cleanup: no articles older than $cutoff")
      case Left(e) =>
        logger.error(s"Article cleanup tick failed: ${e.getMessage}", e)
    }
  }
}
