package com.snelnieuws.service

import com.snelnieuws.model.{ArticleCreate, ArticleRow}
import com.snelnieuws.repository.{AppClientRepository, ArticleRepository, FeatureFlagRepository, ImageCacheRepository}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.http.HttpClient
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** Pure unit tests for the read- and write-path URL rewriting that
  * ArticleService applies on top of ArticleRepository. No DB, no HTTP —
  * stubs replace the repo, image-cache service, and worker. */
class ArticleServiceSpec extends AnyWordSpec with Matchers {

  // ─────────────────────────── stubs / fakes ────────────────────────────

  // ArticleRepository whose only working operation is `create`, recording
  // what it was asked to persist and echoing it back through ArticleRow.
  private class RecordingArticleRepository
      extends ArticleRepository({ throw new AssertionError("transactor must not be used") }) {
    val captured = new AtomicReference[Option[ArticleCreate]](None)
    private val nextId = new AtomicInteger(1)
    override def create(article: ArticleCreate): Either[Throwable, ArticleRow] = {
      captured.set(Some(article))
      Right(
        ArticleRow(
          id          = nextId.getAndIncrement().toLong,
          author      = article.author,
          title       = article.title,
          description = article.description,
          url         = article.url,
          urlToImage  = article.urlToImage,
          publishedAt = "2026-01-01T00:00:00Z",
          content     = article.content,
          category    = article.category
        )
      )
    }
    override def findAll(limit: Int): Either[Throwable, List[ArticleRow]] =
      Right(List.empty)
    override def findById(id: Long): Either[Throwable, Option[ArticleRow]] =
      Right(None)
  }

  // ImageCacheRepository that explodes if used — proves the test path
  // never reaches the DB layer of the cache service.
  private def explodingImageRepo(): ImageCacheRepository =
    new ImageCacheRepository({ throw new AssertionError("image repo must not be used") })

  private val baseImageConfig = ImageCacheConfig(
    rootDir             = "/tmp/article-spec",
    downloadTimeoutMs   = 1000,
    maxBytes            = 1024,
    userAgent           = "test/1.0",
    maxAttempts         = 3,
    retryBackoffMinutes = 30
  )

  // Noop producer — the RecordingWorker overrides enqueue so the worker's
  // drainLoop never runs; this is wired just to satisfy the constructor.
  private def noopRetryProducer(): KafkaImageRetryProducer =
    new KafkaImageRetryProducer(bootstrapServers = "stub:0", topic = "stub") {
      override def send(event: com.snelnieuws.model.ImageRetryEvent): Unit = ()
      override def close(): Unit                                            = ()
    }

  // Records enqueue calls for assertions.
  private class RecordingWorker
      extends ImageDownloadWorker(
        new ImageCacheService(explodingImageRepo(), HttpClient.newHttpClient(), baseImageConfig),
        noopRetryProducer(),
        workerThreads = 1,
        queueCapacity = 8
      ) {
    val enqueued = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    override def enqueue(sourceUrl: String): Boolean = {
      val t = Option(sourceUrl).map(_.trim).getOrElse("")
      if (t.nonEmpty) enqueued.add(t)
      t.nonEmpty
    }
  }

  private def newImageCacheService(): ImageCacheService =
    new ImageCacheService(explodingImageRepo(), HttpClient.newHttpClient(), baseImageConfig)

  // Stubs for the personalised-feed dependencies. The legacy code paths
  // exercised by this suite never reach the flag check (they go directly
  // through findById / create), so the bodies are unreachable. Using
  // throwing stubs documents that and would surface any accidental call.
  private def explodingAppClientRepo(): AppClientRepository =
    new AppClientRepository({ throw new AssertionError("app-client repo must not be used") })
  private def explodingFlagRepo(): FeatureFlagRepository =
    new FeatureFlagRepository({ throw new AssertionError("feature-flag repo must not be used") })

  // ─────────────────────────── create() ─────────────────────────────────

  "ArticleService.create" should {
    "store the content-addressed local path and enqueue the source URL" in {
      val articleRepo = new RecordingArticleRepository()
      val imgSvc      = newImageCacheService()
      val worker      = new RecordingWorker()
      val service     = new ArticleService(
        articleRepo,
        explodingAppClientRepo(),
        explodingFlagRepo(),
        imgSvc,
        worker,
        publicBaseUrl = "https://api.test"
      )

      val srcUrl = "https://cdn.example.com/photo.jpg"
      val req = ArticleCreate(
        author      = None,
        title       = "T1",
        description = None,
        url         = "https://news.example.com/article-1",
        urlToImage  = Some(srcUrl),
        content     = None,
        category    = None
      )
      val result = service.create(req)
      result shouldBe a[Right[_, _]]

      val stored = articleRepo.captured.get().get.urlToImage.get
      stored should startWith("/v2/images/")
      stored shouldBe imgSvc.relativeUrlFor(srcUrl)

      // toArticle prepends the public-base-url for the response.
      result.toOption.get.urlToImage shouldBe Some("https://api.test" + stored)

      // Worker received the original source URL, not the rewritten path.
      val enqueuedList = new java.util.ArrayList(worker.enqueued)
      enqueuedList.size shouldBe 1
      enqueuedList.get(0) shouldBe srcUrl
    }

    "store the fallback path and skip enqueue when source is missing" in {
      val articleRepo = new RecordingArticleRepository()
      val imgSvc      = newImageCacheService()
      val worker      = new RecordingWorker()
      val service     = new ArticleService(
        articleRepo,
        explodingAppClientRepo(),
        explodingFlagRepo(),
        imgSvc,
        worker,
        publicBaseUrl = "https://api.test"
      )

      val req = ArticleCreate(
        author = None, title = "T2", description = None,
        url = "https://news.example.com/article-2",
        urlToImage = None, content = None, category = None
      )
      val result = service.create(req)
      result shouldBe a[Right[_, _]]
      articleRepo.captured.get().get.urlToImage shouldBe Some(imgSvc.fallbackRelativeUrl)
      worker.enqueued.size shouldBe 0

      result.toOption.get.urlToImage shouldBe
        Some("https://api.test" + imgSvc.fallbackRelativeUrl)
    }

    "leave already-local /v2/images/ paths untouched and skip enqueue" in {
      val articleRepo = new RecordingArticleRepository()
      val imgSvc      = newImageCacheService()
      val worker      = new RecordingWorker()
      val service     = new ArticleService(
        articleRepo,
        explodingAppClientRepo(),
        explodingFlagRepo(),
        imgSvc,
        worker,
        publicBaseUrl = ""
      )

      val existing = "/v2/images/aa/bb/deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef.jpg"
      val req = ArticleCreate(
        author = None, title = "T3", description = None,
        url = "https://news.example.com/article-3",
        urlToImage = Some(existing), content = None, category = None
      )
      val result = service.create(req)
      result shouldBe a[Right[_, _]]
      articleRepo.captured.get().get.urlToImage shouldBe Some(existing)
      worker.enqueued.size shouldBe 0
    }
  }

  // ─────────────────────────── findById() ───────────────────────────────

  "ArticleService.findById (toArticle)" should {
    "prepend the public base URL to relative url_to_image values" in {
      val rowSrc = ArticleRow(
        id = 1L, author = None, title = "T", description = None,
        url = "https://news.example.com/article-1",
        urlToImage = Some("/v2/images/aa/bb/cafe.jpg"),
        publishedAt = "2026-01-01T00:00:00Z", content = None, category = None
      )
      val articleRepo = new RecordingArticleRepository() {
        override def findById(id: Long): Either[Throwable, Option[ArticleRow]] =
          Right(Some(rowSrc))
      }
      val service = new ArticleService(
        articleRepo,
        explodingAppClientRepo(),
        explodingFlagRepo(),
        newImageCacheService(),
        new RecordingWorker(),
        publicBaseUrl = "https://api.test"
      )
      val out = service.findById(1L).toOption.flatten.get
      out.urlToImage shouldBe Some("https://api.test/v2/images/aa/bb/cafe.jpg")
    }

    "leave legacy absolute URLs untouched" in {
      val legacyUrl = "https://cdn.legacy.example.com/old.jpg"
      val rowSrc = ArticleRow(
        id = 2L, author = None, title = "T", description = None,
        url = "https://news.example.com/article-2",
        urlToImage = Some(legacyUrl),
        publishedAt = "2026-01-01T00:00:00Z", content = None, category = None
      )
      val articleRepo = new RecordingArticleRepository() {
        override def findById(id: Long): Either[Throwable, Option[ArticleRow]] =
          Right(Some(rowSrc))
      }
      val service = new ArticleService(
        articleRepo,
        explodingAppClientRepo(),
        explodingFlagRepo(),
        newImageCacheService(),
        new RecordingWorker(),
        publicBaseUrl = "https://api.test"
      )
      val out = service.findById(2L).toOption.flatten.get
      out.urlToImage shouldBe Some(legacyUrl)
    }

    "preserve a None url_to_image (no fabricated value on read)" in {
      val rowSrc = ArticleRow(
        id = 3L, author = None, title = "T", description = None,
        url = "https://news.example.com/article-3",
        urlToImage = None,
        publishedAt = "2026-01-01T00:00:00Z", content = None, category = None
      )
      val articleRepo = new RecordingArticleRepository() {
        override def findById(id: Long): Either[Throwable, Option[ArticleRow]] =
          Right(Some(rowSrc))
      }
      val service = new ArticleService(
        articleRepo,
        explodingAppClientRepo(),
        explodingFlagRepo(),
        newImageCacheService(),
        new RecordingWorker(),
        publicBaseUrl = "https://api.test"
      )
      service.findById(3L).toOption.flatten.get.urlToImage shouldBe None
    }
  }
}
