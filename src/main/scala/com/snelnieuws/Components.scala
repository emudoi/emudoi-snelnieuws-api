package com.snelnieuws

import cats.effect.IO
import com.snelnieuws.api.{HealthServlet, NewsServlet}
import com.snelnieuws.db.Database
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.repository.{
  ArticleRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository
}
import com.snelnieuws.service.{
  ApnsConfig,
  ApnsMessagingService,
  ArticleCleanupScheduler,
  ArticleService,
  NotificationService,
  PushyApnsMessagingService,
  SummarizedArticleConsumer
}
import com.typesafe.config.{Config, ConfigFactory}
import doobie.hikari.HikariTransactor
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}

class Components(
  provideTransactor: => HikariTransactor[IO],
  rootConfig: Config,
  apns: Option[ApnsMessagingService]
) {

  private val logger = LoggerFactory.getLogger(classOf[Components])

  // Repositories
  lazy val articleRepository: ArticleRepository =
    new ArticleRepository(provideTransactor)
  lazy val notificationSubscriptionRepository: NotificationSubscriptionRepository =
    new NotificationSubscriptionRepository(provideTransactor)
  lazy val notificationDispatchRepository: NotificationDispatchRepository =
    new NotificationDispatchRepository(provideTransactor)

  // Notification config (api-key needed by servlet for transport-level auth)
  private val notificationsConfig         = rootConfig.getConfig("notifications")
  val notificationsApiKey: String         = notificationsConfig.getString("api-key")

  // Services
  lazy val articleService: ArticleService =
    new ArticleService(articleRepository)

  lazy val notificationService: NotificationService =
    new NotificationService(
      articleRepository,
      notificationSubscriptionRepository,
      notificationDispatchRepository,
      apns
    )

  // Schedulers / consumers
  private val cleanupCfg = rootConfig.getConfig("articles.cleanup")
  lazy val articleCleanupScheduler: Option[ArticleCleanupScheduler] =
    if (cleanupCfg.getBoolean("enabled")) {
      Some(
        new ArticleCleanupScheduler(
          articleRepository = articleRepository,
          retentionHours    = cleanupCfg.getLong("retention-hours"),
          intervalMinutes   = cleanupCfg.getLong("interval-minutes")
        )
      )
    } else {
      logger.info("Article cleanup scheduler is disabled (articles.cleanup.enabled=false)")
      None
    }

  lazy val summarizedArticleConsumer: Option[SummarizedArticleConsumer] = {
    val kafkaCfg = SummarizedImportKafkaConfig.load(rootConfig)
    if (kafkaCfg.enabled) {
      try Some(new SummarizedArticleConsumer(articleRepository, kafkaCfg))
      catch {
        case e: Exception =>
          // Don't crash the API if Kafka is down — just log it.
          logger.error(s"Failed to construct summarized-article consumer: ${e.getMessage}", e)
          None
      }
    } else {
      logger.info("Summarized-article Kafka consumer is disabled (kafka.summarized-import.enabled=false)")
      None
    }
  }

  // Servlets
  lazy val newsServlet: NewsServlet =
    new NewsServlet(articleService, notificationService, notificationsApiKey)
  lazy val healthServlet: HealthServlet =
    new HealthServlet

  /** Eagerly resolve background workers and start them. Idempotent. */
  def startBackgroundWorkers(): Unit = {
    articleCleanupScheduler.foreach(_.start())
    summarizedArticleConsumer.foreach(_.start())
  }

  def close(): Unit = {
    logger.info("Shutting down components...")
    summarizedArticleConsumer.foreach(_.stop())
    articleCleanupScheduler.foreach(_.stop())
  }
}

object Components {

  private val logger = LoggerFactory.getLogger(classOf[Components])

  def default(): Components = {
    val cfg  = ConfigFactory.load()
    val apns = buildApnsFromConfig(cfg)
    new Components(
      provideTransactor = Database.transactor,
      rootConfig = cfg,
      apns = apns
    )
  }

  // APNs is only built when notifications are enabled AND config is valid AND
  // the .p8 key file exists. Init failures are fail-soft: dispatch will report
  // "disabled" until the next deploy fixes the config.
  private def buildApnsFromConfig(cfg: Config): Option[ApnsMessagingService] = {
    val notifCfg = cfg.getConfig("notifications")
    if (!notifCfg.getBoolean("enabled")) {
      logger.info("Notifications are disabled (notifications.enabled=false)")
      None
    } else {
      val apnsCfg = notifCfg.getConfig("apns")
      val ac = ApnsConfig(
        keyPath  = apnsCfg.getString("key-path"),
        keyId    = apnsCfg.getString("key-id"),
        teamId   = apnsCfg.getString("team-id"),
        bundleId = apnsCfg.getString("bundle-id"),
        sandbox  = apnsCfg.getBoolean("sandbox")
      )
      if (ac.keyId.isEmpty || ac.teamId.isEmpty) {
        logger.warn("Notifications enabled but APNs key-id or team-id missing — dispatch will be a no-op")
        None
      } else if (!Files.exists(Paths.get(ac.keyPath))) {
        logger.warn(s"Notifications enabled but APNs key file not found at ${ac.keyPath} — dispatch will be a no-op")
        None
      } else {
        try {
          val subRepo = new NotificationSubscriptionRepository(Database.transactor)
          Some(new PushyApnsMessagingService(subRepo, ac))
        } catch {
          case e: Exception =>
            logger.error(s"Failed to initialize APNs client: ${e.getMessage}", e)
            None
        }
      }
    }
  }
}
