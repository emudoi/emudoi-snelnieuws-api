package com.snelnieuws.service

import com.snelnieuws.repository.ArticleRepository
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

object ArticleCleanupScheduler {
  /** Floor on the per-language row count — cleanup is skipped for a
    * given language while its count is below this. Sized to keep the
    * personalised-feed filter (see docs/personalised-feed-plan.md)
    * with at least 400 candidates available *per language* even if
    * ingestion for that language stalls. The earlier global 400-row
    * floor was applied table-wide, which broke down once a second
    * language could share the table: a language with 300 rows could
    * still be pruned because the global total exceeded 400, leaving
    * that language's feed starved. */
  val MinArticleCount: Int = 400
}

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
    articleRepository.distinctLanguages() match {
      case Left(e) =>
        logger.error(s"Article cleanup tick failed (distinctLanguages): ${e.getMessage}", e)
      case Right(Nil) =>
        logger.debug("Cleanup: table is empty, nothing to do")
      case Right(langs) =>
        val cutoff = OffsetDateTime.now().minusHours(retentionHours)
        // Per-language loop: short-circuits per language, not across
        // the whole tick — a failure on language X does not block
        // language Y in the same tick.
        langs.foreach { lang =>
          articleRepository.countByLanguage(lang) match {
            case Right(total) if total < ArticleCleanupScheduler.MinArticleCount =>
              logger.info(
                s"Cleanup[$lang]: skipped — only $total article(s), below floor of " +
                  s"${ArticleCleanupScheduler.MinArticleCount}"
              )
            case Right(_) =>
              articleRepository.deletePublishedBeforeForLanguage(lang, cutoff) match {
                case Right(deleted) if deleted > 0 =>
                  logger.info(s"Cleanup[$lang]: deleted $deleted article(s) older than $cutoff")
                case Right(_) =>
                  logger.debug(s"Cleanup[$lang]: no articles older than $cutoff")
                case Left(e) =>
                  logger.error(s"Article cleanup tick failed for $lang: ${e.getMessage}", e)
              }
            case Left(e) =>
              logger.error(s"Article cleanup tick failed (count $lang): ${e.getMessage}", e)
          }
        }
    }
  }
}
