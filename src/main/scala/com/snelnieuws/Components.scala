package com.snelnieuws

import cats.effect.IO
import com.snelnieuws.api.{
  AndroidNotificationBroadcastServlet,
  AndroidNotificationDispatchServlet,
  AndroidNotificationsServletV2,
  HealthServlet,
  ImageServlet,
  NewsServlet,
  NewsServletV2,
  NewsServletV3,
  NotificationBroadcastServlet,
  NotificationDispatchServlet,
  StaticContentServlet
}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.repository.{
  AndroidNotificationSubscriptionRepository,
  AppClientRepository,
  ArticleRepository,
  FeatureFlagRepository,
  ImageCacheRepository,
  NotificationCandidateRepository,
  NotificationSubscriptionRepository,
  UserRepository,
  UserSemanticQueryRepository
}
import com.snelnieuws.service.{
  AndroidNotificationService,
  ApnsConfig,
  ApnsMessagingService,
  ArticleCleanupScheduler,
  ArticleService,
  FcmConfig,
  FcmMessagingService,
  FirebaseFcmMessagingService,
  ImageCacheCleanupScheduler,
  ImageCacheConfig,
  ImageCacheService,
  ImageDownloadWorker,
  ImageRetrySlowConsumer,
  ImageSlowConfig,
  ImageSlowDownloader,
  IngestionApiClient,
  KafkaImageRetryProducer,
  NotificationService,
  PushyApnsMessagingService,
  SemanticQueryService,
  SummarizedArticleConsumer,
  UserService
}
import com.typesafe.config.{Config, ConfigFactory}
import doobie.hikari.HikariTransactor
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}
import scala.util.Try

class Components(
  provideTransactor: => HikariTransactor[IO],
  rootConfig: Config,
  apns: Option[ApnsMessagingService],
  apnsSandbox: Option[ApnsMessagingService],
  fcm: Option[FcmMessagingService] = None,
  verifierOverride: Option[FirebaseTokenVerifier] = None
) {

  private val logger = LoggerFactory.getLogger(classOf[Components])

  // Repositories
  lazy val articleRepository: ArticleRepository =
    new ArticleRepository(provideTransactor)
  // Eulang (native Dutch-language) track — same query surface, separate table.
  lazy val eulangArticleRepository: ArticleRepository =
    new ArticleRepository(provideTransactor, tableName = "eulang_articles")
  lazy val notificationSubscriptionRepository: NotificationSubscriptionRepository =
    new NotificationSubscriptionRepository(provideTransactor)
  lazy val androidNotificationSubscriptionRepository: AndroidNotificationSubscriptionRepository =
    new AndroidNotificationSubscriptionRepository(provideTransactor)
  lazy val userRepository: UserRepository =
    new UserRepository(provideTransactor)
  lazy val appClientRepository: AppClientRepository =
    new AppClientRepository(provideTransactor)
  lazy val imageCacheRepository: ImageCacheRepository =
    new ImageCacheRepository(provideTransactor)
  lazy val featureFlagRepository: FeatureFlagRepository =
    new FeatureFlagRepository(provideTransactor)

  // Image cache config — single source of truth read once on construct.
  private val imagesCfg = rootConfig.getConfig("images")
  val imagesPublicBaseUrl: String = imagesCfg.getString("public-base-url")
  private val imageCacheServiceConfig: ImageCacheConfig = ImageCacheConfig(
    rootDir             = imagesCfg.getString("root-dir"),
    downloadTimeoutMs   = imagesCfg.getLong("download-timeout-ms"),
    maxBytes            = imagesCfg.getLong("max-bytes"),
    userAgent           = imagesCfg.getString("user-agent"),
    maxAttempts         = imagesCfg.getInt("max-attempts"),
    retryBackoffMinutes = imagesCfg.getLong("retry-backoff-minutes")
  )

  // Notification config (api-key needed by servlet for transport-level auth)
  private val notificationsConfig         = rootConfig.getConfig("notifications")
  val notificationsApiKey: String         = notificationsConfig.getString("api-key")

  // Firebase ID token verifier. Tests pass a Stub via verifierOverride.
  // Production reads firebase.project-id from config; if empty we fall
  // back to RejectAll so the auth-required endpoints stay locked rather
  // than letting unverified requests through.
  lazy val firebaseVerifier: FirebaseTokenVerifier = {
    verifierOverride.getOrElse {
      val firebaseCfg = rootConfig.getConfig("firebase")
      val projectId   = firebaseCfg.getString("project-id")
      if (projectId.isEmpty) {
        logger.warn(
          "firebase.project-id is empty — auth-required endpoints will reject all requests. " +
            "Set FIREBASE_PROJECT_ID to enable Firebase ID token verification."
        )
        FirebaseTokenVerifier.RejectAll
      } else {
        try {
          new FirebaseTokenVerifier.FirebaseAdmin(
            projectId          = projectId,
            serviceAccountPath = firebaseCfg.getString("service-account-path")
          )
        } catch {
          case e: Exception =>
            logger.error(
              s"Failed to initialize Firebase Admin SDK: ${e.getMessage}. " +
                "Falling back to RejectAll.",
              e
            )
            FirebaseTokenVerifier.RejectAll
        }
      }
    }
  }

  // Services
  lazy val imageCacheService: ImageCacheService =
    new ImageCacheService(
      repository = imageCacheRepository,
      httpClient = ImageCacheService.defaultHttpClient(imageCacheServiceConfig),
      config     = imageCacheServiceConfig
    )

  // Slow-retry tier — fast-path failures classified as retryableSlow
  // (timeout / connection error / 5xx / "other") get handed off to the
  // image-retry-slow Kafka topic, and ImageRetrySlowConsumer (below)
  // tries one more time with longer timeouts. Shares the
  // kafka.summarized-import cluster (it's the same Kafka broker).
  private val slowRetryKafka: com.snelnieuws.kafka.SummarizedImportKafkaConfig =
    com.snelnieuws.kafka.SummarizedImportKafkaConfig.load(rootConfig)
  private val slowRetryCfg: com.typesafe.config.Config =
    imagesCfg.getConfig("slow-retry")

  lazy val kafkaImageRetryProducer: KafkaImageRetryProducer =
    new KafkaImageRetryProducer(
      bootstrapServers = slowRetryKafka.bootstrapServers,
      topic            = slowRetryCfg.getString("topic")
    )

  lazy val imageDownloadWorker: ImageDownloadWorker =
    new ImageDownloadWorker(
      imageCacheService = imageCacheService,
      retryProducer     = kafkaImageRetryProducer,
      workerThreads     = imagesCfg.getInt("worker-threads"),
      queueCapacity     = imagesCfg.getInt("queue-capacity")
    )

  lazy val imageSlowDownloader: ImageSlowDownloader =
    new ImageSlowDownloader(
      imageCacheService = imageCacheService,
      imageCacheRepo    = imageCacheRepository,
      config            = imageCacheServiceConfig,
      slowConfig = ImageSlowConfig(
        connectTimeoutMs = slowRetryCfg.getLong("connect-timeout-ms"),
        readTimeoutMs    = slowRetryCfg.getLong("read-timeout-ms")
      )
    )

  lazy val imageRetrySlowConsumer: Option[ImageRetrySlowConsumer] =
    if (slowRetryKafka.enabled) {
      try Some(
        new ImageRetrySlowConsumer(
          imageCacheRepo   = imageCacheRepository,
          slowDownloader   = imageSlowDownloader,
          bootstrapServers = slowRetryKafka.bootstrapServers,
          topic            = slowRetryCfg.getString("topic"),
          consumerGroup    = slowRetryCfg.getString("consumer-group"),
          autoOffsetReset  = slowRetryKafka.autoOffsetReset
        )
      )
      catch {
        case e: Exception =>
          logger.error(s"Failed to construct image-retry-slow consumer: ${e.getMessage}", e)
          None
      }
    } else {
      logger.info(
        "image-retry-slow consumer is disabled (kafka.summarized-import.enabled=false)"
      )
      None
    }

  lazy val imageCacheCleanupScheduler: Option[ImageCacheCleanupScheduler] = {
    val cleanupCfg = imagesCfg.getConfig("cleanup")
    if (cleanupCfg.getBoolean("enabled")) {
      Some(
        new ImageCacheCleanupScheduler(
          imageCacheRepository = imageCacheRepository,
          rootDir              = imagesCfg.getString("root-dir"),
          retentionHours       = cleanupCfg.getLong("retention-hours"),
          intervalMinutes      = cleanupCfg.getLong("interval-minutes")
        )
      )
    } else {
      logger.info("Image cache cleanup scheduler is disabled (images.cleanup.enabled=false)")
      None
    }
  }

  lazy val articleService: ArticleService =
    new ArticleService(
      repository            = articleRepository,
      appClientRepository   = appClientRepository,
      featureFlagRepository = featureFlagRepository,
      imageCacheService     = imageCacheService,
      imageDownloadWorker   = imageDownloadWorker,
      publicBaseUrl         = imagesPublicBaseUrl,
      eulangRepository      = Some(eulangArticleRepository)
    )

  // ── Per-language candidate pool. Shared across iOS + Android since
  //    the table itself is platform-agnostic; each service writes its
  //    own per-language pool independently (rows are keyed by run_id
  //    + language, so the two services never collide).
  lazy val notificationCandidateRepository: NotificationCandidateRepository =
    new NotificationCandidateRepository(provideTransactor)

  lazy val notificationService: NotificationService =
    new NotificationService(
      articleRepository,
      notificationSubscriptionRepository,
      featureFlagRepository,
      notificationCandidateRepository,
      apnsProd    = apns,
      apnsSandbox = apnsSandbox
    )

  lazy val androidNotificationService: AndroidNotificationService =
    new AndroidNotificationService(
      articleRepository,
      androidNotificationSubscriptionRepository,
      featureFlagRepository,
      notificationCandidateRepository,
      fcm = fcm
    )

  lazy val userService: UserService =
    new UserService(
      userRepository,
      notificationSubscriptionRepository,
      androidNotificationSubscriptionRepository
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

  // Retention parity for eulang_articles — same schedule/config as the main
  // table, just pointed at the eulang repository.
  lazy val eulangArticleCleanupScheduler: Option[ArticleCleanupScheduler] =
    if (cleanupCfg.getBoolean("enabled")) {
      Some(
        new ArticleCleanupScheduler(
          articleRepository = eulangArticleRepository,
          retentionHours    = cleanupCfg.getLong("retention-hours"),
          intervalMinutes   = cleanupCfg.getLong("interval-minutes")
        )
      )
    } else None

  lazy val summarizedArticleConsumer: Option[SummarizedArticleConsumer] = {
    val kafkaCfg = SummarizedImportKafkaConfig.load(rootConfig)
    if (kafkaCfg.enabled) {
      try Some(
        new SummarizedArticleConsumer(
          articleRepository   = articleRepository,
          kafkaConfig         = kafkaCfg,
          imageCacheService   = imageCacheService,
          imageDownloadWorker = imageDownloadWorker
        )
      )
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

  // Eulang importer: same consumer class, eulang topic/group + eulang repo,
  // sharing the one image cache + download worker (both table-agnostic).
  lazy val eulangArticleConsumer: Option[SummarizedArticleConsumer] = {
    val kafkaCfg = SummarizedImportKafkaConfig.loadEulang(rootConfig)
    if (kafkaCfg.enabled) {
      try Some(
        new SummarizedArticleConsumer(
          articleRepository   = eulangArticleRepository,
          kafkaConfig         = kafkaCfg,
          imageCacheService   = imageCacheService,
          imageDownloadWorker = imageDownloadWorker
        )
      )
      catch {
        case e: Exception =>
          logger.error(s"Failed to construct eulang-article consumer: ${e.getMessage}", e)
          None
      }
    } else {
      logger.info("Eulang-article Kafka consumer is disabled (kafka.eulang-import.enabled=false)")
      None
    }
  }

  // Servlets
  // Read-only ArticleService bound to eulang_articles. Used by v1
  // /articles/:id to resolve "e<id>" share ids (link-preview crawlers hit
  // the public, unauthenticated v1 endpoint). Reuses the same read-time
  // image absolutising as the main service.
  lazy val eulangArticleService: ArticleService =
    new ArticleService(
      repository            = eulangArticleRepository,
      appClientRepository   = appClientRepository,
      featureFlagRepository = featureFlagRepository,
      imageCacheService     = imageCacheService,
      imageDownloadWorker   = imageDownloadWorker,
      publicBaseUrl         = imagesPublicBaseUrl
    )

  lazy val newsServlet: NewsServlet =
    new NewsServlet(
      articleService,
      notificationService,
      userService,
      firebaseVerifier,
      eulangArticleService = Some(eulangArticleService)
    )
  lazy val newsServletV2: NewsServletV2 =
    new NewsServletV2(
      articleService,
      notificationService,
      userService,
      appClientRepository,
      firebaseVerifier
    )
  // ── Semantic search (semantic_search/backend_tasks.txt) ──────────
  //
  // IngestionApiClient calls the X-API-Key-gated internal endpoints
  // exposed by emudoi-snelnieuws-ingestion-api at the in-cluster
  // service DNS hostname. The API key is rendered by Vault Agent at
  // /vault/secrets/semantic-search-key (same value as the
  // ingestion-api side's secret, vault path
  // secret/data/ingestion-api/semantic-search-key).
  lazy val ingestionApiSemanticSearchKey: String = {
    val pathStr = Try(rootConfig.getString("ingestion-api.semantic-search-key-path"))
      .toOption
      .filter(_.nonEmpty)
      .getOrElse("/vault/secrets/semantic-search-key")
    val path = java.nio.file.Paths.get(pathStr)
    if (java.nio.file.Files.exists(path)) {
      val raw = new String(java.nio.file.Files.readAllBytes(path), "UTF-8").trim
      if (raw.isEmpty) {
        logger.warn(
          s"ingestion-api semantic-search-key file at $pathStr is empty — /v3/embeddings/query " +
            "and /v3/feed/semantic will fail with 503"
        )
      }
      raw
    } else {
      logger.warn(
        s"ingestion-api semantic-search-key file not found at $pathStr — /v3/embeddings/query " +
          "and /v3/feed/semantic will fail with 503. Expected mount: " +
          "vault.hashicorp.com/agent-inject-secret-semantic-search-key in k8s/deployment.yaml."
      )
      ""
    }
  }

  lazy val ingestionApiClient: IngestionApiClient = new IngestionApiClient(
    baseUrl = rootConfig.getString("ingestion-api.base-url"),
    apiKey  = ingestionApiSemanticSearchKey
  )

  lazy val userSemanticQueryRepository: UserSemanticQueryRepository =
    new UserSemanticQueryRepository(provideTransactor)

  lazy val semanticQueryService: SemanticQueryService =
    new SemanticQueryService(userSemanticQueryRepository, ingestionApiClient)

  lazy val newsServletV3: NewsServletV3 =
    new NewsServletV3(
      articleRepository,
      articleService,
      notificationService,
      userService,
      appClientRepository,
      firebaseVerifier,
      imagesPublicBaseUrl,
      semanticQueryService,
      ingestionApiClient,
      eulangArticleRepository = Some(eulangArticleRepository)
    )
  lazy val notificationDispatchServlet: NotificationDispatchServlet =
    new NotificationDispatchServlet(
      notificationService,
      notificationsApiKey,
      environment = "production"
    )
  lazy val notificationDispatchSandboxServlet: NotificationDispatchServlet =
    new NotificationDispatchServlet(
      notificationService,
      notificationsApiKey,
      environment = "sandbox"
    )
  lazy val notificationBroadcastServlet: NotificationBroadcastServlet =
    new NotificationBroadcastServlet(notificationService, notificationsApiKey)
  lazy val androidNotificationsServletV2: AndroidNotificationsServletV2 =
    new AndroidNotificationsServletV2(
      androidNotificationService,
      appClientRepository,
      firebaseVerifier
    )
  lazy val androidNotificationDispatchServlet: AndroidNotificationDispatchServlet =
    new AndroidNotificationDispatchServlet(androidNotificationService, notificationsApiKey)
  lazy val androidNotificationBroadcastServlet: AndroidNotificationBroadcastServlet =
    new AndroidNotificationBroadcastServlet(androidNotificationService, notificationsApiKey)
  lazy val staticContentServlet: StaticContentServlet =
    new StaticContentServlet
  lazy val healthServlet: HealthServlet =
    new HealthServlet
  lazy val imageServlet: ImageServlet =
    new ImageServlet(imageCacheService)

  /** Eagerly resolve background workers and start them. Idempotent. */
  def startBackgroundWorkers(): Unit = {
    articleCleanupScheduler.foreach(_.start())
    eulangArticleCleanupScheduler.foreach(_.start())
    imageDownloadWorker.start()
    imageCacheCleanupScheduler.foreach(_.start())
    summarizedArticleConsumer.foreach(_.start())
    eulangArticleConsumer.foreach(_.start())
    imageRetrySlowConsumer.foreach(_.start())
  }

  def close(): Unit = {
    logger.info("Shutting down components...")
    // Order matters: stop the producer of work first, then drain the
    // worker before letting the JVM exit so in-flight downloads either
    // finish or are abandoned cleanly. Cleanup schedulers are
    // independent and can stop in any order. The slow-retry consumer
    // is stopped after the fast worker so any in-flight hand-offs
    // can land on the topic before the consumer's poll loop exits.
    summarizedArticleConsumer.foreach(_.stop())
    eulangArticleConsumer.foreach(_.stop())
    imageDownloadWorker.stop()
    imageRetrySlowConsumer.foreach(_.stop())
    kafkaImageRetryProducer.close()
    imageCacheCleanupScheduler.foreach(_.stop())
    articleCleanupScheduler.foreach(_.stop())
    eulangArticleCleanupScheduler.foreach(_.stop())
  }
}

object Components {

  private val logger = LoggerFactory.getLogger(classOf[Components])

  def default(): Components = {
    val cfg = ConfigFactory.load()
    val (apns, apnsSandbox) = buildApnsBoth(cfg)
    val fcm = buildFcm(cfg)
    new Components(
      provideTransactor = Database.transactor,
      rootConfig = cfg,
      apns = apns,
      apnsSandbox = apnsSandbox,
      fcm = fcm
    )
  }

  // Build the FCM client. Returns None when notifications are disabled,
  // FCM is opted out (notifications.fcm.enabled=false), or init throws.
  // The Android dispatch servlet maps `None` to 503 so an Airflow trigger
  // gets a clear failure rather than a silent no-op.
  //
  // Uses `firebase.service-account-path` by default — same JSON the ID-token
  // verifier loads — but can be overridden by `notifications.fcm.service-account-path`
  // if you want to scope FCM to a separate SA with only messaging perms.
  private def buildFcm(cfg: Config): Option[FcmMessagingService] = {
    val notifCfg = cfg.getConfig("notifications")
    if (!notifCfg.getBoolean("enabled")) {
      logger.info("Notifications are disabled (notifications.enabled=false) — FCM not initialized")
      return None
    }
    val fcmCfg = notifCfg.getConfig("fcm")
    if (!fcmCfg.getBoolean("enabled")) {
      logger.info("FCM disabled (notifications.fcm.enabled=false)")
      return None
    }
    val firebaseCfg = cfg.getConfig("firebase")
    val projectId   = firebaseCfg.getString("project-id")
    if (projectId.isEmpty) {
      logger.warn("FCM enabled but firebase.project-id is empty — Android dispatch will be a no-op")
      return None
    }
    val saPathOverride = fcmCfg.getString("service-account-path")
    val saPath =
      if (saPathOverride.nonEmpty) saPathOverride
      else firebaseCfg.getString("service-account-path")
    val dryRun = fcmCfg.getBoolean("dry-run")
    try {
      val subRepo = new AndroidNotificationSubscriptionRepository(Database.transactor)
      val client = new FirebaseFcmMessagingService(
        subRepo,
        FcmConfig(projectId = projectId, serviceAccountPath = saPath, dryRun = dryRun)
      )
      logger.info(s"FCM client initialized (project=$projectId, dryRun=$dryRun)")
      Some(client)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to initialize FCM client: ${e.getMessage}", e)
        None
    }
  }

  // Build both APNs clients from one config block. The sandbox config-level
  // `sandbox` flag is overridden — prod always targets api.push.apple.com,
  // sandbox always targets api.sandbox.push.apple.com. Same .p8 signs both;
  // Apple .p8 keys are environment-agnostic.
  //
  // Both clients are built only when notifications are enabled AND keyId/
  // teamId/.p8 are valid. Init failures are fail-soft: the affected
  // dispatch endpoint will report "disabled" until the next deploy fixes
  // the config.
  private def buildApnsBoth(
    cfg: Config
  ): (Option[ApnsMessagingService], Option[ApnsMessagingService]) = {
    val notifCfg = cfg.getConfig("notifications")
    if (!notifCfg.getBoolean("enabled")) {
      logger.info("Notifications are disabled (notifications.enabled=false)")
      (None, None)
    } else {
      val apnsCfg = notifCfg.getConfig("apns")
      val baseCfg = ApnsConfig(
        keyPath  = apnsCfg.getString("key-path"),
        keyId    = apnsCfg.getString("key-id"),
        teamId   = apnsCfg.getString("team-id"),
        bundleId = apnsCfg.getString("bundle-id"),
        sandbox  = false
      )
      if (baseCfg.keyId.isEmpty || baseCfg.teamId.isEmpty) {
        logger.warn("Notifications enabled but APNs key-id or team-id missing — dispatch will be a no-op")
        (None, None)
      } else if (!Files.exists(Paths.get(baseCfg.keyPath))) {
        logger.warn(s"Notifications enabled but APNs key file not found at ${baseCfg.keyPath} — dispatch will be a no-op")
        (None, None)
      } else {
        try {
          val subRepo = new NotificationSubscriptionRepository(Database.transactor)
          val prod    = new PushyApnsMessagingService(subRepo, baseCfg)
          val sand    = new PushyApnsMessagingService(subRepo, baseCfg.copy(sandbox = true))
          (Some(prod), Some(sand))
        } catch {
          case e: Exception =>
            logger.error(s"Failed to initialize APNs clients: ${e.getMessage}", e)
            (None, None)
        }
      }
    }
  }
}
