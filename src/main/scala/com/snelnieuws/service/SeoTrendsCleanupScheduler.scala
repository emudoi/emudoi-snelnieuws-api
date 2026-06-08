package com.snelnieuws.service

import com.snelnieuws.repository.{ArticleTrendingScoresRepository, SeoTrendsRepository}
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically prunes the trending tables. Each tick deletes `seo_trends`
 * batches older than `retentionHours` and `article_trending_scores` rows past
 * their `expires_at`. Single-thread daemon scheduler — survives transient DB
 * errors by logging and waiting for the next tick. Mirrors
 * ArticleCleanupScheduler.
 */
class SeoTrendsCleanupScheduler(
  seoTrendsRepository: SeoTrendsRepository,
  articleTrendingScoresRepository: ArticleTrendingScoresRepository,
  retentionHours: Long,
  intervalMinutes: Long
) {

  private val logger  = LoggerFactory.getLogger(classOf[SeoTrendsCleanupScheduler])
  private val started = new AtomicBoolean(false)

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "seo-trends-cleanup-scheduler")
        t.setDaemon(true)
        t
      }
    })

  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      logger.info(
        s"Starting seo-trends cleanup scheduler — retentionHours=$retentionHours, intervalMinutes=$intervalMinutes"
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
      logger.info("Stopping seo-trends cleanup scheduler...")
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
    seoTrendsRepository.deleteCollectedBefore(cutoff) match {
      case Right(deleted) if deleted > 0 =>
        logger.info(s"Seo-trends cleanup: deleted $deleted trend row(s) older than $cutoff")
      case Right(_) =>
        logger.debug(s"Seo-trends cleanup: no trend rows older than $cutoff")
      case Left(e) =>
        logger.error(s"Seo-trends cleanup tick failed (seo_trends): ${e.getMessage}", e)
    }
    articleTrendingScoresRepository.deleteExpired() match {
      case Right(deleted) if deleted > 0 =>
        logger.info(s"Seo-trends cleanup: deleted $deleted expired score row(s)")
      case Right(_) =>
        logger.debug("Seo-trends cleanup: no expired score rows")
      case Left(e) =>
        logger.error(s"Seo-trends cleanup tick failed (article_trending_scores): ${e.getMessage}", e)
    }
  }
}
