package com.snelnieuws.service

import com.snelnieuws.model.ImageCacheStatus
import com.snelnieuws.repository.ImageCacheRepository
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration => JDuration}
import scala.util.{Failure, Success, Try}

/** Configuration for the slow-tier downloader. Connect / read timeouts
  * are deliberately larger than the fast path's `images.download-timeout-ms`
  * — the whole point of this tier is to absorb slow upstream CDNs that
  * the fast path can't wait for. */
case class ImageSlowConfig(
  connectTimeoutMs: Long,
  readTimeoutMs: Long
)

/** One slow-tier download attempt. Owns its own HttpClient so the
  * generous timeouts don't bleed into the fast path's connection pool.
  * Writes through ImageCacheService.writeAtomicPublic so the on-disk
  * layout + traversal guard + atomic-move logic is shared with the
  * fast path — no duplicate code, and the slow path can never write
  * to a location the fast path wouldn't have written to.
  *
  * Idempotency: caller (ImageRetrySlowConsumer) already checks the
  * image_cache row's status before invoking; this class assumes the
  * row is 'failed' and will overwrite the bytes on success. On
  * failure, attempts/last_attempt_at are bumped but the original
  * fast-path failure-reason is preserved — we don't reclassify here. */
class ImageSlowDownloader(
  imageCacheService: ImageCacheService,
  imageCacheRepo: ImageCacheRepository,
  config: ImageCacheConfig,
  slowConfig: ImageSlowConfig
) {

  private val logger = LoggerFactory.getLogger(classOf[ImageSlowDownloader])

  private val slowClient: HttpClient = HttpClient
    .newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(JDuration.ofMillis(slowConfig.connectTimeoutMs))
    .build()

  def downloadAndStore(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
    // Defense-in-depth — the producer-side check (SummarizedArticleConsumer
    // + ImageCacheService.download) should never let unsupported schemes
    // get here, but a stray event from a future producer version mustn't
    // be able to bypass that guard.
    val scheme = ImageCacheService.schemeOf(sourceUrl)
    if (!ImageCacheService.SupportedSchemes.contains(scheme)) {
      return Left(
        new IllegalArgumentException(s"unsupported scheme '$scheme' for slow retry: $sourceUrl")
      )
    }

    val relPath = imageCacheService.pathFor(sourceUrl)
    Try {
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(sourceUrl))
        .timeout(JDuration.ofMillis(slowConfig.readTimeoutMs))
        .header("User-Agent", config.userAgent)
        .GET()
        .build()
      val resp = slowClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
      if (resp.statusCode() / 100 != 2) {
        throw new RuntimeException(
          s"non-2xx status ${resp.statusCode()} on slow retry $sourceUrl"
        )
      }
      val bytes = resp.body()
      if (bytes.length.toLong > config.maxBytes) {
        throw new RuntimeException(
          s"image exceeds max-bytes on slow retry: ${bytes.length} > ${config.maxBytes}"
        )
      }
      val contentType = Option(resp.headers().firstValue("Content-Type").orElse(null))
        .map(_.split(";")(0).trim)
        .filter(_.nonEmpty)
      imageCacheService.writeAtomicPublic(relPath, bytes)
      ImageDownloadResult(
        relativePath = relPath,
        contentType  = contentType,
        sizeBytes    = bytes.length.toLong
      )
    } match {
      case Success(result) =>
        imageCacheRepo.upsertDownloaded(
          sourceUrl    = sourceUrl,
          relativePath = result.relativePath,
          contentType  = result.contentType,
          sizeBytes    = result.sizeBytes
        ) match {
          case Right(_) => Right(result)
          case Left(e) =>
            // Bytes are on NFS but the bookkeeping write failed — same
            // tradeoff as the fast path: keep the bytes, log the
            // bookkeeping miss. A subsequent fast-path attempt (or
            // re-summarize event) will upsert the row to 'downloaded'.
            logger.warn(
              s"slow retry: bytes landed but image_cache upsert failed url=$sourceUrl: ${e.getMessage}"
            )
            Right(result)
        }
      case Failure(e) =>
        // Preserve the fast path's failure classification — just bump
        // attempts/last_attempt_at so ops can see this URL has been
        // tried again. The row stays 'failed', no further re-emit.
        imageCacheRepo.bumpAttempt(sourceUrl) match {
          case Right(_) => ()
          case Left(e2) =>
            logger.warn(
              s"slow retry: bumpAttempt failed url=$sourceUrl: ${e2.getMessage}"
            )
        }
        Left(e)
    }
  }
}

object ImageSlowDownloader {
  // Convenience pattern used by the consumer to avoid hitting the network
  // for rows that already flipped to 'downloaded' (a parallel attempt
  // landed bytes first). Kept here so the consumer doesn't need to
  // import ImageCacheStatus separately.
  def isAlreadyDownloaded(statusOpt: Option[String]): Boolean =
    statusOpt.contains(ImageCacheStatus.Downloaded)
}
